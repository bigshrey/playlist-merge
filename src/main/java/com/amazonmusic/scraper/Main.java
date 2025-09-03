package com.amazonmusic.scraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.BrowserContext;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;

/**
 * Main entry point for the Amazon Music playlist scraper.
 * This application scrapes playlists from Amazon Music and exports them to CSV files and a PostgreSQL database.
 * 
 * @author Amazon Music Scraper Team
 * @since 1.0
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * Sets process environment variables for this JVM (best-effort, uses reflection).
     * Configures debug system properties that will be used by ScraperService when
     * environment variables are not available.
     */
    private static void setEnvVarsAtStartup() {
        // Modifying System.getenv() via reflection is blocked by the Java module system on modern JVMs.
        // Use Java system properties as a reliable fallback; ScraperService will read from system
        // properties when environment variables are not present.
        try {
            System.setProperty("SCRAPER_SAVE_DEBUG", "true");
            System.setProperty("SCRAPER_SAVE_DEBUG_ALLOW_FULLPAGE", "true");
            logger.info("Set debug system properties: SCRAPER_SAVE_DEBUG and SCRAPER_SAVE_DEBUG_ALLOW_FULLPAGE");
        } catch (Exception e) {
            logger.warn("Failed to set debug system properties at startup: {}", e.getMessage());
        }
    }

    /**
     * Creates a PostgresService and ensures tables exist.
     * @param postgres EmbeddedPostgres instance
     * @return PostgresServiceInterface
     */
    private static PostgresServiceInterface createPostgresService(EmbeddedPostgres postgres) {
        String dbUrl = String.format("jdbc:postgresql://localhost:%d/postgres", postgres.getPort());
        String dbUser = "postgres";
        String dbPass = "postgres";
        PostgresService postgresService = new PostgresService(dbUrl, dbUser, dbPass);
        postgresService.createTables();
        return postgresService;
    }

    /**
     * Ensures the user is signed in, running sign-in workflow if needed.
     * @param context Playwright browser context
     * @param page Playwright page instance
     * @param authService Authentication service for handling sign-in workflows
     * @param scraperService Scraper service for checking sign-in status
     */
    private static void ensureSignedIn(BrowserContext context, Page page, AuthServiceInterface authService, ScraperServiceInterface scraperService) {
        boolean sessionExists = java.nio.file.Files.exists(java.nio.file.Paths.get("scraped-data/storage-state.json"));
        if (!sessionExists) {
            // attempt automated sign-in via provided AuthService
            authService.automateSignIn(page);
            authService.waitForUserToContinue();
            authService.printSessionCookies(context);
            authService.saveStorageState(context);
        }
        page.navigate("https://music.amazon.com.au");
        page.waitForLoadState();
        // Wait lazily and reactively for either sign-in UI or profile UI to appear before checking state
        authService.waitForAuthUi(page);
        if (!scraperService.isSignedIn(page)) {
            logger.warn("Session restored, but user is NOT signed in. Running sign-in workflow...");
            authService.automateSignIn(page);
            authService.waitForUserToContinue();
            authService.printSessionCookies(context);
            authService.saveStorageState(context);
        } else {
            logger.info("Session restored and user is signed in.");
        }
    }

    /**
     * Scrapes playlists and exports songs to CSV and database.
     * @param page Playwright page instance
     * @param postgresService PostgresService instance for database operations
     * @param scraperService Scraper service for playlist and song extraction
     * @param csvService CSV service for exporting songs to CSV files
     */
    private static void scrapeAndExportPlaylists(Page page, PostgresServiceInterface postgresService, ScraperServiceInterface scraperService, CsvServiceInterface csvService) {
        // Navigate using ScraperService which contains robust and maintained navigation heuristics
        scraperService.goToLibraryPlaylists(page);

        // Give the UI a chance to load playlist tiles; prefer network idle before querying
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5_000));
            page.waitForTimeout(800);
        } catch (Exception ignored) {}

        var playlists = scraperService.scrapePlaylistLinks(page);
        if (playlists == null || playlists.isEmpty()) {
            logger.info("No playlists found in your library.");
            try {
                String pageContent = page.content();
                java.nio.file.Files.writeString(java.nio.file.Paths.get("debug-main-playlist-links.html"), pageContent);
                page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("debug-main-playlist-links.png")));
                logger.info("Saved page content and screenshot for playlist link debugging.");
            } catch (Exception e) {
                logger.error("Failed to save playlist links debug artifacts: {}", e.getMessage());
            }
            return;
        }

        // TEMPORARY DEBUG: optionally process only the first playlist to speed up debugging runs
        String singleTest = System.getProperty("SCRAPER_SINGLE_PLAYLIST_TEST", System.getenv().getOrDefault("SCRAPER_SINGLE_PLAYLIST_TEST", "false"));
        if (Boolean.parseBoolean(singleTest) && !playlists.isEmpty()) {
            var testPlaylist = playlists.get(0);
            logger.info("Testing with single playlist: {}", testPlaylist.get("name"));
            try {
                var pl = scraperService.scrapePlaylist(page, testPlaylist.get("url"));
                var safeName = Utils.sanitizeFilename(testPlaylist.get("name"));
                if (!pl.songs().isEmpty()) {
                    try {
                        csvService.writeSongsToCSV(pl.songs(), safeName + ".csv");
                    } catch (IOException e) {
                        logger.error("Failed to write playlist CSV '{}': {}", safeName, e.getMessage());
                    }
                    int playlistId = postgresService.insertPlaylist(testPlaylist.get("name"), testPlaylist.get("url"));
                    if (playlistId > 0) {
                        try { postgresService.insertSongs(playlistId, pl.songs()); } catch (Exception e) { logger.error("Failed to insert songs into DB: {}", e.getMessage()); }
                    }
                } else {
                    logger.warn("Test playlist '{}' returned no songs.", testPlaylist.get("name"));
                }
            } catch (Exception e) {
                logger.error("Error scraping test playlist '{}': {}", testPlaylist.get("url"), e.getMessage());
            }
            return; // exit after single-playlist test when enabled
        }

        var allSongs = new ArrayList<Song>();
        for (var playlist : playlists) {
            var playlistName = playlist.get("name");
            var playlistUrl = playlist.get("url");
            if (playlistName == null || playlistName.trim().isEmpty()) {
                logger.warn("Skipping playlist with invalid name: {}", playlistUrl);
                continue;
            }
            try {
                var pl = scraperService.scrapePlaylist(page, playlistUrl);
                var safeName = Utils.sanitizeFilename(playlistName);
                if (pl.songs().isEmpty()) {
                    logger.warn("Playlist '{}' has no songs, skipping CSV export and DB insert.", playlistName);
                    continue;
                }
                try {
                    csvService.writeSongsToCSV(pl.songs(), safeName + ".csv");
                } catch (IOException e) {
                    logger.error("Failed to write playlist CSV '{}': {}", safeName, e.getMessage());
                }
                int playlistId = postgresService.insertPlaylist(playlistName, playlistUrl);
                if (playlistId > 0) {
                    try {
                        postgresService.insertSongs(playlistId, pl.songs());
                        allSongs.addAll(pl.songs());
                    } catch (Exception e) {
                        logger.error("Failed to insert songs into database for playlist {}: {}", playlistName, e.getMessage());
                    }
                } else {
                    logger.error("Failed to insert playlist into database, skipping song inserts.");
                }
            } catch (Exception e) {
                logger.error("Error scraping playlist '{}': {}", playlistUrl, e.getMessage());
            }
        }
        logger.info("Scraping and export complete. Total songs exported: {}", allSongs.size());
    }

    /**
     * Main application entry point.
     * @param args Command-line arguments
     */
    public static void main(String[] args) {
        EmbeddedPostgres postgres = null;
        try {
            // Ensure debug env vars are present so debug artifacts are saved during development runs
            setEnvVarsAtStartup();

            // determine mode: args or interactive prompt
            String mode = (args != null && args.length > 0) ? args[0].trim().toLowerCase() : "";
            if (mode.isBlank()) {
                System.out.println("Choose mode:\n  1) db  - start embedded Postgres only\n  2) scrape - run scraping workflow\nEnter choice (db/scrape) or press Enter for 'scrape': ");
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                    String input = br.readLine();
                    if (input != null) mode = input.trim().toLowerCase();
                    if (mode.isBlank()) mode = "scrape";
                } catch (Exception e) {
                    mode = "scrape";
                }
            }

            // determine embedded Postgres port (allow override via env or system property)
            String portStr = System.getProperty("EMBEDDED_PG_PORT", System.getenv().getOrDefault("EMBEDDED_PG_PORT", "5432"));
            int embeddedPort = 5432;
            try { embeddedPort = Integer.parseInt(portStr); } catch (Exception ignored) {}
            String pgDataDir = System.getProperty("EMBEDDED_PG_DATA_DIR", "scraped-data/pgdata");

            if (mode.equals("db") || mode.equals("1") || mode.equals("db-only") || mode.equals("database")) {
                // start embedded DB and allow user to inspect
                try {
                    postgres = PostgresService.startEmbedded(pgDataDir, embeddedPort);
                    // write IntelliJ datasource files so the IDE recognises this DB automatically
                    writeIntelliJDataSource(embeddedPort, pgDataDir);
                    PostgresServiceInterface postgresService = createPostgresService(postgres);
                    String jdbc = String.format("jdbc:postgresql://localhost:%d/postgres", embeddedPort);
                    System.out.println("Embedded Postgres started.");
                    System.out.println("JDBC URL: " + jdbc);
                    System.out.println("DB user: postgres");
                    System.out.println("DB password: postgres");
                    System.out.println("Data directory: scraped-data/pgdata");
                    System.out.println("The DB will keep running until you press Enter. Connect with your DB client to inspect data.");
                    System.out.println("Press Enter to stop the embedded DB and exit.");
                    try { System.in.read(); } catch (Exception ignored) {}
                    return;
                } catch (Exception e) {
                    logger.error("Failed to start embedded Postgres in db-only mode: {}", e.getMessage());
                    throw e;
                }
            }

            // Default: run full scraping workflow
            // instantiate services at runtime for DI and testability (typed to interfaces)
            AuthServiceInterface authService = new AuthService();
            ScraperServiceInterface scraperService = new ScraperService(authService);
            CsvServiceInterface csvService = new CsvService();

            // initialize helpers and ensure artifact dirs
            authService.init();
            postgres = PostgresService.startEmbedded(pgDataDir, embeddedPort);
            // write IntelliJ datasource files so the IDE recognises this DB automatically
            writeIntelliJDataSource(embeddedPort, pgDataDir);
            PostgresServiceInterface postgresService = createPostgresService(postgres);

            try (Playwright playwright = Playwright.create()) {
                BrowserContext context = authService.setupBrowserContext(playwright);
                Page page = context.newPage();
                ensureSignedIn(context, page, authService, scraperService);
                scrapeAndExportPlaylists(page, postgresService, scraperService, csvService);
                context.browser().close();
            } catch (Exception e) {
                logger.error("Fatal error: {}", e.getMessage());
                logger.error("Stack trace: {}", java.util.Arrays.toString(e.getStackTrace()));
            }
        } catch (Exception e) {
            logger.error("Failed to start embedded PostgreSQL or run scraper: {}", e.getMessage());
        } finally {
            if (postgres != null) {
                try {
                    postgres.close();
                    logger.info("Embedded PostgreSQL stopped.");
                } catch (Exception e) {
                    logger.warn("Failed to stop embedded PostgreSQL: {}", e.getMessage());
                }
            }
        }
    }

    // Write IntelliJ IDEA Data Source config files (.idea/dataSources.xml and dataSources.local.xml)
    private static void writeIntelliJDataSource(int port, String dataDir) {
        try {
            Path idea = Paths.get(".idea");
            if (!Files.exists(idea)) Files.createDirectories(idea);
            String jdbc = String.format("jdbc:postgresql://localhost:%d/postgres", port);
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<data-sources version=\"21\">\n" +
                    "  <data-source>\n" +
                    "    <name>Embedded Postgres (playlist-merge)</name>\n" +
                    "    <driver-ref>PostgreSQL</driver-ref>\n" +
                    "    <url>" + jdbc + "</url>\n" +
                    "    <user>postgres</user>\n" +
                    "    <password>postgres</password>\n" +
                    "  </data-source>\n" +
                    "</data-sources>\n";
            Files.writeString(idea.resolve("dataSources.xml"), xml);
            Files.writeString(idea.resolve("dataSources.local.xml"), xml);
            System.out.println("Wrote .idea/dataSources.xml and dataSources.local.xml for IntelliJ Database tool.");
        } catch (Exception e) {
            logger.warn("Failed to write IntelliJ data source files: {}", e.getMessage());
        }
    }
}
