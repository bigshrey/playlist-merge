package com.amazonmusic.scraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service for scraping Amazon Music playlists and songs using Playwright.
 */
public class ScraperService implements ScraperServiceInterface {
    private static final Logger logger = LoggerFactory.getLogger(ScraperService.class);
    private final AuthServiceInterface authService;
    private final PostgresServiceInterface postgresService;

    public ScraperService(AuthServiceInterface authService) {
        this.authService = authService == null ? new AuthService() : authService;
        // Prefer existing PostgresService if DB configured
        String dbUrl = envOrProp("DB_URL", "");
        if (dbUrl != null && !dbUrl.isBlank()) {
            String dbUser = envOrProp("DB_USER", "postgres");
            String dbPass = envOrProp("DB_PASS", "");
            PostgresService ps = new PostgresService(dbUrl, dbUser, dbPass);
            try { ps.createTables(); } catch (Exception ignored) {}
            this.postgresService = ps;
        } else {
            this.postgresService = null;
        }
    }

    public ScraperService() {
        this(new AuthService());
    }

    private static String envOrProp(String key, String defaultVal) {
        try {
            String ev = System.getenv(key);
            if (ev != null) return ev;
        } catch (Exception ignored) {}
        String prop = System.getProperty(key);
        return prop != null ? prop : defaultVal;
    }

    private static final boolean SAVE_DEBUG_ARTIFACTS = Boolean.parseBoolean(envOrProp("SCRAPER_SAVE_DEBUG", "false"));
    private static final int DEBUG_HTML_MAX_BYTES = 200_000;
    private static final String DEBUG_ARTIFACT_DIR = envOrProp("SCRAPER_DEBUG_DIR", "scraped-debug");
    private static final int DEBUG_MAX_ELEMENTS = 8;
    private static final int DEFAULT_PLAYLIST_WAIT_MS = 5000;
    private static final int DEFAULT_ELEMENT_WAIT_MS = 3000;
    private static final int DEFAULT_NAVIGATION_WAIT_MS = 1000;
    private static final String DEFAULT_BASE_URL = "https://music.amazon.com.au";
    private static final String SOUND_CLOUD_CLIENT_ID = envOrProp("SOUND_CLOUD_CLIENT_ID", "");

    private static final String[] SONG_SELECTOR_CANDIDATES = new String[]{
        "role=row",
        "[data-testid='song-row']",
        "[data-testid='track-row']",
        "music-track-list-row",
        ".music-track-list-row",
        "music-image-row",
        ".music-image-row",
        ".track-list__item",
        ".song-item",
        ".track-item",
        "[class*='track-row']",
        "[class*='song-row']",
        "tr[role='row']",
        "div[role='row']",
        ".song-row",
        ".track-row",
        ".track",
        "music-track",
        "a[data-test='track-title']",
        "div.tracklist-row"
    };

    private static final String[] PLAYLIST_TILE_SELECTORS = new String[]{
        "[data-test='playlist']",
        "[data-testid='playlist']",
        "a[href*='/playlist']",
        "music-vertical-item",
        ".music-image-row",
        "music-horizontal-item"
    };

    private static String joinSelectors(String[] arr) {
        return String.join(", ", arr);
    }

    // --- Remove CSV / known title helpers ---
    // All CSV-based validation and enrichment logic is removed.
    // Only site data and keywords are used for validation and enrichment.

    // --- Helper utilities to reduce repetitive try/catch ---
    private String safeInnerText(Locator l) {
        try {
            if (l != null && l.count() > 0) {
                String s = l.first().innerText();
                return s == null ? "" : s.trim();
            }
        } catch (Exception e) {
            logger.debug("Failed to get inner text: {}", e.getMessage());
        }
        return "";
    }

    private String safeAttr(Locator l, String attr) {
        try {
            if (l != null && l.count() > 0) {
                String s = l.first().getAttribute(attr);
                return s == null ? "" : s.trim();
            }
        } catch (Exception e) {
            logger.debug("Failed to get attribute '{}': {}", attr, e.getMessage());
        }
        return "";
    }

    private boolean safeClick(Locator locator, String description) {
        try {
            if (locator != null && locator.count() > 0) {
                locator.first().scrollIntoViewIfNeeded();
                locator.first().click();
                logger.debug("Successfully clicked: {}", description);
                return true;
            }
        } catch (Exception e) {
            logger.debug("Failed to click {}: {}", description, e.getMessage());
        }
        return false;
    }

    private void safeWait(Page page, int milliseconds) {
        try {
            page.waitForTimeout(milliseconds);
        } catch (Exception e) {
            logger.debug("Wait interrupted: {}", e.getMessage());
        }
    }

    // Uses the predefined SONG_SELECTOR_CANDIDATES to find a matching locator on the page.
    private Locator findFirstMatchingLocator(Page page) {
        for (String sel : SONG_SELECTOR_CANDIDATES) {
            try {
                Locator l = page.locator(sel);
                if (l != null && l.count() > 0) {
                    return l;
                }
            } catch (Exception e) {
                logger.debug("Selector error (ignored): {} - {}", sel, e.getMessage());
            }
        }
        return null;
    }

    // --- Playlists ---
    public List<Playlist> scrapePlaylists(String url, int maxPlaylists, boolean scrapeEmpty) {
        logger.info("Scraping playlists from: {}", url);
        List<Playlist> playlists = new ArrayList<>();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(getDefaultLaunchOptions());
            try {
                Page page = browser.newPage();
                try {
                    page.navigate(url);
                    page.setDefaultTimeout(30_000);
                    page.setDefaultNavigationTimeout(30_000);
                    // Wait for initial load
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                    safeWait(page, DEFAULT_NAVIGATION_WAIT_MS);
                    // Accept cookies if prompted
                    Locator acceptCookies = page.locator("text=Accept Cookies");
                    if (acceptCookies != null && acceptCookies.count() > 0) {
                        safeClick(acceptCookies, "Accept Cookies button");
                        safeWait(page, DEFAULT_NAVIGATION_WAIT_MS);
                    }
                    // Find all playlist links on the page
                    Locator playlistLinks = page.locator(joinSelectors(PLAYLIST_TILE_SELECTORS));
                    if (playlistLinks != null && playlistLinks.count() > 0) {
                        int count = 0;
                        for (int i = 0; i < playlistLinks.count(); i++) {
                            try {
                                Locator link = playlistLinks.nth(i);
                                String href = safeAttr(link, "href");
                                String title = safeInnerText(link);
                                if (href != null && !href.isEmpty() && title != null && !title.isEmpty()) {
                                    playlists.add(new Playlist(title, href));
                                    count++;
                                }
                            } catch (Exception e) {
                                logger.debug("Error processing playlist link (ignored): {}", e.getMessage());
                            }
                            // Respect the maxPlaylists limit
                            if (maxPlaylists > 0 && count >= maxPlaylists) {
                                break;
                            }
                        }
                    }
                    // Optionally scrape each playlist for details
                    if (!playlists.isEmpty() && scrapeEmpty) {
                        for (Playlist playlist : playlists) {
                            try {
                                scrapePlaylistDetails(playlist, page);
                                safeWait(page, DEFAULT_PLAYLIST_WAIT_MS);
                            } catch (Exception e) {
                                logger.warn("Error scraping playlist details: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error in playlist scraping process: {}", e.getMessage());
                } finally {
                    page.close();
                }
            } catch (Exception e) {
                logger.error("Error launching browser: {}", e.getMessage());
            } finally {
                browser.close();
            }
        } catch (Exception e) {
            logger.error("Playwright initialization error: {}", e.getMessage());
        }
        return playlists;
    }

    private void scrapePlaylistDetails(Playlist playlist, Page page) {
        logger.info("Scraping details for playlist: {}", playlist.title());
        try {
            page.navigate(playlist.url());
            page.setDefaultTimeout(30_000);
            page.setDefaultNavigationTimeout(30_000);
            // Wait for playlist page to load
            page.waitForLoadState(LoadState.NETWORKIDLE);
            safeWait(page, DEFAULT_NAVIGATION_WAIT_MS);
            // Accept cookies if prompted
            Locator acceptCookies = page.locator("text=Accept Cookies");
            if (acceptCookies != null && acceptCookies.count() > 0) {
                safeClick(acceptCookies, "Accept Cookies button");
                safeWait(page, DEFAULT_NAVIGATION_WAIT_MS);
            }
            // Extract playlist details
            String title = safeInnerText(page.locator("h1"));
            String description = safeInnerText(page.locator("div[data-testid='description']"));
            String imageUrl = safeAttr(page.locator("img[data-testid='playlist-image']"), "src");
            // Update playlist object with details
            playlist.setTitle(title);
            playlist.setDescription(description);
            playlist.setImageUrl(imageUrl);
            logger.info("Playlist details: {} - {} - {}", title, description, imageUrl);
        } catch (Exception e) {
            logger.warn("Error scraping playlist details: {}", e.getMessage());
        }
    }

    // --- Songs ---
    public List<Song> scrapeSongs(String url, int maxSongs) {
        logger.info("Scraping songs from: {}", url);
        List<Song> songs = new ArrayList<>();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(getDefaultLaunchOptions());
            try {
                Page page = browser.newPage();
                try {
                    page.navigate(url);
                    page.setDefaultTimeout(30_000);
                    page.setDefaultNavigationTimeout(30_000);
                    // Wait for initial load
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                    safeWait(page, DEFAULT_NAVIGATION_WAIT_MS);
                    // Accept cookies if prompted
                    Locator acceptCookies = page.locator("text=Accept Cookies");
                    if (acceptCookies != null && acceptCookies.count() > 0) {
                        safeClick(acceptCookies, "Accept Cookies button");
                        safeWait(page, DEFAULT_NAVIGATION_WAIT_MS);
                    }
                    // Find all song elements on the page
                    Locator songElements = findFirstMatchingLocator(page);
                    if (songElements != null && songElements.count() > 0) {
                        int count = 0;
                        for (int i = 0; i < songElements.count(); i++) {
                            try {
                                Locator songElement = songElements.nth(i);
                                Song song = processSongCandidate(songElement, i + 1);
                                if (song != null) {
                                    songs.add(song);
                                    count++;
                                }
                            } catch (Exception e) {
                                logger.debug("Error processing song element (ignored): {}", e.getMessage());
                            }
                            // Respect the maxSongs limit
                            if (maxSongs > 0 && count >= maxSongs) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error in song scraping process: {}", e.getMessage());
                } finally {
                    page.close();
                }
            } catch (Exception e) {
                logger.error("Error launching browser: {}", e.getMessage());
            } finally {
                browser.close();
            }
        } catch (Exception e) {
            logger.error("Playwright initialization error: {}", e.getMessage());
        }
        return songs;
    }

    // --- Site-based metadata extraction helpers ---
    private String extractTitleFromElement(Locator element) {
        String title = safeInnerText(element.locator("a[data-test='track-title'], .track-title, [data-testid*='title'], .title, h2, h3"));
        if (title == null || title.isEmpty()) {
            title = safeAttr(element, "aria-label");
        }
        if (title == null || title.isEmpty()) {
            title = safeAttr(element, "alt");
        }
        return title != null ? title.trim() : "";
    }

    private String extractArtistFromElement(Locator element) {
        String artist = safeInnerText(element.locator("a[data-test='track-artist'], .track-artist, [data-testid*='artist'], .artist, h4, h5"));
        if (artist == null || artist.isEmpty()) {
            artist = safeAttr(element, "aria-label");
        }
        if (artist == null || artist.isEmpty()) {
            artist = safeAttr(element, "alt");
        }
        // Enrich with features/remix info
        if (artist != null) {
            if (artist.toLowerCase().contains("feat")) artist += " (feat.)";
            if (artist.toLowerCase().contains("remix")) artist += " (remix)";
        }
        return artist != null ? artist.trim() : "";
    }

    private String extractAlbumFromElement(Locator element) {
        String album = safeInnerText(element.locator("a[data-test='track-album'], .track-album, [data-testid*='album'], .album"));
        if (album == null || album.isEmpty()) {
            album = safeAttr(element, "aria-label");
        }
        return album != null ? album.trim() : "";
    }

    private Boolean extractExplicitFromElement(Locator element) {
        String text = safeInnerText(element);
        if (text != null && text.toLowerCase().contains("explicit")) return true;
        String aria = safeAttr(element, "aria-label");
        if (aria != null && aria.toLowerCase().contains("explicit")) return true;
        return null;
    }

    private String extractImageUrlFromElement(Locator element) {
        String imgUrl = safeAttr(element.locator("img, [data-testid*='image'], .image"), "src");
        if (imgUrl == null || imgUrl.isEmpty()) {
            imgUrl = safeAttr(element, "data-src");
        }
        return imgUrl != null ? imgUrl.trim() : "";
    }

    private String extractDurationFromElement(Locator element) {
        String duration = safeInnerText(element.locator("span[data-testid='duration'], .duration, .track-duration"));
        if (duration == null || duration.isEmpty()) {
            duration = safeAttr(element, "aria-label");
        }
        return duration != null ? duration.trim() : "";
    }

    private Integer extractTrackNumberFromElement(Locator element) {
        String trackNum = safeInnerText(element.locator("span[data-testid='track-number'], .track-number, .index, .position"));
        if (trackNum != null && !trackNum.isEmpty()) {
            try {
                return Integer.parseInt(trackNum.replaceAll("\\D", ""));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    // --- Refactored song extraction using site-based helpers ---
    private Song processSongCandidate(Locator songElement, int playlistPosition) {
        MetadataCrossChecker.CrossCheckResult titleRes = extractTitleCrossChecked(songElement);
        MetadataCrossChecker.CrossCheckResult artistRes = extractArtistCrossChecked(songElement);
        MetadataCrossChecker.CrossCheckResult albumRes = extractAlbumCrossChecked(songElement);
        MetadataCrossChecker.CrossCheckResult durationRes = extractDurationCrossChecked(songElement);
        MetadataCrossChecker.CrossCheckResult imageRes = extractImageCrossChecked(songElement);
        MetadataCrossChecker.CrossCheckResult trackNumRes = extractTrackNumberCrossChecked(songElement);
        MetadataCrossChecker.CrossCheckResult explicitRes = extractExplicitCrossChecked(songElement);

        String url = safeAttr(songElement.locator("a[data-test='track-title']"), "href");
        String trackAsin = extractTrackAsinFromUrl(url);

        // Aggregate confidence scores
        double confidenceScore = (titleRes.confidenceScore + artistRes.confidenceScore + albumRes.confidenceScore + durationRes.confidenceScore + imageRes.confidenceScore + trackNumRes.confidenceScore + explicitRes.confidenceScore) / 7.0;

        // Aggregate provenance/source details
        Map<String, String> sourceDetails = new LinkedHashMap<>();
        sourceDetails.put("title", titleRes.sourceDetails.toString());
        sourceDetails.put("artist", artistRes.sourceDetails.toString());
        sourceDetails.put("album", albumRes.sourceDetails.toString());
        sourceDetails.put("duration", durationRes.sourceDetails.toString());
        sourceDetails.put("imageUrl", imageRes.sourceDetails.toString());
        sourceDetails.put("trackNumber", trackNumRes.sourceDetails.toString());
        sourceDetails.put("explicit", explicitRes.sourceDetails.toString());

        // Use cross-checked values
        return new Song(
            titleRes.value,
            artistRes.value,
            albumRes.value,
            url,
            durationRes.value,
            trackNumRes.value.isEmpty() ? null : Integer.valueOf(trackNumRes.value.replaceAll("\\D", "")),
            playlistPosition,
            explicitRes.value.toLowerCase().contains("explicit"),
            imageRes.value,
            "", // release date
            "", // genre
            trackAsin,
            false, // validated (to be set after MusicBrainz validation)
            confidenceScore,
            sourceDetails
        );
    }

    // --- Music metadata API ---
    private Map<String, String> fetchEnhancedMetadata(String title, String artist) {
        Map<String, String> metadata = new HashMap<>();
        try {
            // Basic validation
            if (title == null || title.isEmpty()) return metadata;
            // Use site data and keywords for enrichment
            metadata.put("title", title);
            if (artist != null && !artist.isEmpty()) {
                metadata.put("artist", artist);
            }
            // TODO: Add genre and release date detection if needed
            // Optionally, call an external API for more metadata
        } catch (Exception e) {
            logger.warn("Error fetching enhanced metadata: {}", e.getMessage());
        }
        return metadata;
    }

    // --- Browser and page setup ---
    private BrowserType.LaunchOptions getDefaultLaunchOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions();
        options.setHeadless(true);
        options.setArgs(Arrays.asList(
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--window-size=1280x1696",
            "--lang=en-US"
        ));
        return options;
    }

    // --- URL and ASIN extraction ---
    private static String extractTrackAsinFromUrl(String url) {
        if (url == null || url.isBlank()) return "";
        int idx = url.indexOf("trackAsin=");
        if (idx >= 0) {
            int start = idx + "trackAsin=".length();
            int end = url.indexOf('&', start);
            if (end < 0) end = url.length();
            return url.substring(start, end).trim();
        }
        idx = url.toLowerCase(Locale.ROOT).indexOf("?trackasin=");
        if (idx >= 0) {
            int start = idx + "?trackasin=".length();
            int end = url.indexOf('&', start);
            if (end < 0) end = url.length();
            return url.substring(start, end).trim();
        }
        return "";
    }

    // Try to extract an artist slug from an artist href and turn it into a readable name
    private static String extractArtistNameFromHref(String href) {
        if (href == null || href.isBlank()) return "";
        try {
            // look for /artists/slug or /artists/ID/slug
            String path = href;
            // if full URL, strip domain
            int idx = path.indexOf("/artists/");
            if (idx >= 0) path = path.substring(idx + "/artists/".length());
            // path may start with ID then slug
            if (path.contains("/")) {
                String[] parts = path.split("/");
                // last part is most likely slug
                path = parts[parts.length - 1];
            }
            // remove query
            int q = path.indexOf('?'); if (q >= 0) path = path.substring(0, q);
            // decode and clean
            String decoded = java.net.URLDecoder.decode(path.replaceAll("\\.html$", ""), java.nio.charset.StandardCharsets.UTF_8.name());
            return slugToName(decoded);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String slugToName(String slug) {
        if (slug == null || slug.isBlank()) return "";
        // remove common id patterns
        slug = slug.replaceAll("[^a-zA-Z0-9\\- '&]", " ");
        slug = slug.replace('-', ' ');
        slug = slug.replaceAll("\\s+", " ").trim();
        // title case words
        StringBuilder sb = new StringBuilder();
        for (String w : slug.split(" ")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) sb.append(w.substring(1));
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    // --- Refactored playlist scraping using site-based song extraction ---
    @Override
    public Playlist scrapePlaylist(Page page, String playlistUrl) {
        logger.info("Scraping playlist: {}", playlistUrl);
        authService.waitForAuthUi(page);
        Utils.retryPlaywrightAction(() -> { page.navigate(playlistUrl); return true; }, 3, "navigate to playlist");
        page.setViewportSize(1920, 1080);
        Utils.retryPlaywrightAction(() -> { page.waitForLoadState(LoadState.NETWORKIDLE); return true; }, 2, "wait for network idle");
        page.waitForTimeout(1000);
        String playlistName = safeInnerText(page.locator("h1, [data-testid*='playlist-title'], .playlist-title"));
        if (playlistName == null || playlistName.isEmpty()) playlistName = page.title();
        Locator songElements = findFirstMatchingLocator(page);
        List<Song> songs = new ArrayList<>();
        if (songElements != null && songElements.count() > 0) {
            for (int i = 0; i < songElements.count(); i++) {
                try {
                    Song song = processSongCandidate(songElements.nth(i), i + 1);
                    if (song != null && song.title() != null && !song.title().isEmpty()) {
                        songs.add(song);
                    }
                } catch (Exception e) {
                    logger.warn("Error extracting song at position {}: {}", i + 1, e.getMessage());
                }
            }
        } else {
            logger.warn("No song elements found for playlist: {}", playlistName);
        }
        // Persist to database if available
        if (postgresService != null && !songs.isEmpty()) {
            postgresService.createTables();
            int pid = postgresService.insertPlaylist(playlistName, playlistUrl);
            if (pid > 0) {
                postgresService.insertSongs(pid, songs);
            }
        }
        logger.info("Scraped {} songs from playlist: {}", songs.size(), playlistName);
        return new Playlist(playlistName, songs);
    }

    // Implement required interface methods
    @Override
    public List<Map<String, String>> scrapePlaylistLinks(Page page) {
        List<Map<String, String>> playlists = new ArrayList<>();
        Locator playlistLinks = page.locator(joinSelectors(PLAYLIST_TILE_SELECTORS));
        int count = playlistLinks.count();
        for (int i = 0; i < count; i++) {
            Locator link = playlistLinks.nth(i);
            String href = safeAttr(link, "href");
            String name = safeInnerText(link);
            if (href != null && !href.isEmpty() && name != null && !name.isEmpty()) {
                Map<String, String> map = new HashMap<>();
                map.put("name", name.trim());
                map.put("url", href.trim());
                playlists.add(map);
            }
        }
        return playlists;
    }

    @Override
    public void goToLibraryPlaylists(Page page) {
        Locator libraryButton = page.getByText("Library", new Page.GetByTextOptions().setExact(false));
        if (libraryButton.count() > 0) {
            safeClick(libraryButton, "Library button");
            safeWait(page, DEFAULT_NAVIGATION_WAIT_MS);
        }
        Locator playlistsTab = page.getByText("Playlists", new Page.GetByTextOptions().setExact(false));
        if (playlistsTab.count() > 0) {
            safeClick(playlistsTab, "Playlists tab");
            safeWait(page, DEFAULT_NAVIGATION_WAIT_MS);
        }
    }

    @Override
    public boolean isSignedIn(Page page) {
        Locator signInBtn = page.getByText("Sign in", new Page.GetByTextOptions().setExact(false));
        if (signInBtn != null && signInBtn.count() > 0) return false;
        Locator profile = page.locator("[aria-label='Account'], [data-test-id='profile-menu'], img[alt*='profile'], img[alt*='avatar'], button:has([data-icon='profile'])");
        return profile != null && profile.count() > 0;
    }

    private static final List<String> TITLE_SELECTORS = Arrays.asList(
        "a[data-test='track-title']", ".track-title", "[data-testid*='title']", ".title", "h2", "h3", "[aria-label]", "[alt]"
    );
    private static final List<String> ARTIST_SELECTORS = Arrays.asList(
        "a[data-test='track-artist']", ".track-artist", "[data-testid*='artist']", ".artist", "h4", "h5", "[aria-label]", "[alt]"
    );
    private static final List<String> ALBUM_SELECTORS = Arrays.asList(
        "a[data-test='track-album']", ".track-album", "[data-testid*='album']", ".album", "[aria-label]"
    );
    private static final List<String> DURATION_SELECTORS = Arrays.asList(
        "span[data-testid='duration']", ".duration", ".track-duration", "[aria-label]"
    );
    private static final List<String> IMAGE_SELECTORS = Arrays.asList(
        "img[data-testid='playlist-image']", "img", "[data-src]", "[src]"
    );
    private static final List<String> TRACK_NUMBER_SELECTORS = Arrays.asList(
        "span[data-testid='track-number']", ".track-number", ".index", ".position"
    );
    private static final List<String> EXPLICIT_SELECTORS = Arrays.asList(
        ".explicit", "[aria-label*='explicit']", "[data-testid*='explicit']"
    );

    private MetadataCrossChecker crossChecker = new MetadataCrossChecker();

    private MetadataCrossChecker.CrossCheckResult extractTitleCrossChecked(Locator element) {
        return crossChecker.crossCheckField(element, TITLE_SELECTORS);
    }
    private MetadataCrossChecker.CrossCheckResult extractArtistCrossChecked(Locator element) {
        return crossChecker.crossCheckField(element, ARTIST_SELECTORS);
    }
    private MetadataCrossChecker.CrossCheckResult extractAlbumCrossChecked(Locator element) {
        return crossChecker.crossCheckField(element, ALBUM_SELECTORS);
    }
    private MetadataCrossChecker.CrossCheckResult extractDurationCrossChecked(Locator element) {
        return crossChecker.crossCheckField(element, DURATION_SELECTORS);
    }
    private MetadataCrossChecker.CrossCheckResult extractImageCrossChecked(Locator element) {
        return crossChecker.crossCheckField(element, IMAGE_SELECTORS);
    }
    private MetadataCrossChecker.CrossCheckResult extractTrackNumberCrossChecked(Locator element) {
        return crossChecker.crossCheckField(element, TRACK_NUMBER_SELECTORS);
    }
    private MetadataCrossChecker.CrossCheckResult extractExplicitCrossChecked(Locator element) {
        return crossChecker.crossCheckField(element, EXPLICIT_SELECTORS);
    }
}
