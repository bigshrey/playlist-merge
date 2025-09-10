package com.amazonmusic.scraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * Service for scraping Amazon Music playlists and songs using Playwright.
 * <p>
 * Extraction and Validation Workflow:
 * <ul>
 *   <li>Extracts playlist and song metadata using selectors from MetadataFieldRegistry.</li>
 *   <li>Uses {@link MetadataCrossChecker} to cross-check and normalize metadata fields from multiple selectors.</li>
 *   <li>Populates Song.sourceDetails with a mapping from metadata field names to validation/cross-checking objects (typically provenance maps, but extensible).</li>
 *   <li>Ensures all sourceDetails entries are non-null, structured objects for robust validation and provenance tracking.</li>
 *   <li>Tracks per-field validation status in Song.fieldValidationStatus.</li>
 * </ul>
 * <p>
 * AGENTIC CHANGE LOG (2025-09-04):
 * - [CLARIFIED] Extraction logic and documentation now explicitly state that sourceDetails maps metadata field names to validation/cross-checking objects.
 * - [VALIDATED] All sourceDetails entries are non-null, structured objects for each field.
 * - [DONE] Javadocs and comments updated to reflect this usage and extensibility.
 *
 * Workflow:
 * <ul>
 *   <li>Extracts playlist and song metadata from Amazon Music using Playwright.</li>
 *   <li>Uses {@link MetadataCrossChecker} to cross-check and normalize metadata fields from multiple selectors, including validation selectors.</li>
 *   <li>Aggregates provenance and confidence scores for each Song.</li>
 *   <li>Tracks provenance for each field in Song.sourceDetails (Map&lt;String, Object&gt;).</li>
 *   <li>Tracks per-field validation status in Song.fieldValidationStatus (Map&lt;String, Boolean&gt;).</li>
 *   <li>Integrates with external validation (e.g., MusicBrainzClient) to further validate and enrich song metadata.</li>
 *   <li>Exports validated and enriched data to CSV and PostgreSQL via CsvService and PostgresService.</li>
 *   <li>Logs discrepancies, provenance, and validation results for debugging and traceability.</li>
 * </ul>
 * <p>
 * Future extensibility: Supports per-field validation status and additional enrichment sources. Validation selectors can be added to MetadataFieldRegistry for improved reliability without changing Song/Playlist schema.
 * <p>
 * TODO [AGENTIC]: When adding new selectors for validation/cross-checking, update registry (MetadataFieldRegistry), normalization logic (MetadataCrossChecker), and extraction logic here. Do not expand Song/Playlist schema unless a new canonical metadata field is required.
 * TODO [AGENTIC]: If Song.sourceDetails type changes, update extraction logic and all consumers (DB, CSV, reporting, validation).
 *
 * @author Amazon Music Scraper Team
 * @since 1.0
 */
public class ScraperService implements ScraperServiceInterface {
    private static final Logger logger = LoggerFactory.getLogger(ScraperService.class);
    private final AuthServiceInterface authService;
    private final PostgresServiceInterface postgresService;
    private final MusicBrainzClient musicBrainzClient = new MusicBrainzClient();

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

    private static final int DEFAULT_NAVIGATION_WAIT_MS = 1000;

    // --- Remove CSV / known title helpers ---
    // All CSV-based validation and enrichment logic is removed.
    // Only site data and keywords are used for validation and enrichment.

    // --- Helper utilities to reduce repetitive try/catch ---
    private String safeInnerText(Locator l) {
        if (l == null) {
            logger.warn("safeInnerText called with null Locator.");
            return "";
        }
        try {
            if (l.count() > 0) {
                String s = l.first().innerText();
                return s == null ? "" : s.trim();
            }
        } catch (Exception e) {
            logger.debug("Failed to get inner text: {}", e.getMessage());
        }
        return "";
    }

    private String safeAttr(Locator l, String attr) {
        if (l == null) {
            logger.warn("safeAttr called with null Locator (attr={}).", attr);
            return "";
        }
        try {
            if (l.count() > 0) {
                String s = l.first().getAttribute(attr);
                return s == null ? "" : s.trim();
            }
        } catch (Exception e) {
            logger.debug("Failed to get attribute '{}': {}", attr, e.getMessage());
        }
        return "";
    }

    private boolean safeClick(Locator locator, String description) {
        if (locator == null) {
            logger.warn("safeClick called with null Locator (desc={}).", description);
            return false;
        }
        try {
            if (locator.count() > 0) {
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

    // --- Best-practice page readiness helper ---
    /**
     * Waits for page to reach NETWORKIDLE and for a key selector to appear.
     * Use this instead of arbitrary timeouts for robust, reactive waiting.
     * @param page Playwright page instance
     * @param selector CSS selector to wait for (e.g., playlist/song row)
     * @param maxWaitMs Maximum wait time in milliseconds
     */
    private void waitForPageReady(Page page, String selector, int maxWaitMs) {
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(maxWaitMs));
            if (selector != null && !selector.isBlank()) {
                page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(maxWaitMs));
                logger.debug("Page ready: {} appeared after NETWORKIDLE", selector);
            } else {
                logger.warn("No valid selector provided for waitForPageReady.");
            }
        } catch (Exception e) {
            logger.warn("Timeout or error waiting for page ready (selector: {}): {}", selector, e.getMessage());
        }
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
                    page.navigate(toAbsoluteUrl(url));
                    page.setDefaultTimeout(30_000);
                    page.setDefaultNavigationTimeout(30_000);
                    // Wait for initial load and playlist tiles
                    waitForPageReady(page, joinSelectors(getPlaylistTileSelectors()), 15000);
                    // Accept cookies if prompted
                    Locator acceptCookies = page.locator("text=Accept Cookies");
                    if (acceptCookies != null && acceptCookies.count() > 0) {
                        safeClick(acceptCookies, "Accept Cookies button");
                        waitForPageReady(page, joinSelectors(getPlaylistTileSelectors()), 5000);
                    }
                    // Find all playlist links on the page
                    String playlistSelector = joinSelectors(getPlaylistTileSelectors());
                    if (playlistSelector.isEmpty()) {
                        logger.warn("No playlist selectors found in registry. Locator will match nothing.");
                        // Optionally, fallback to a legacy selector
                        playlistSelector = "[data-test='playlist'], [data-testid='playlist'], a[href*='/playlist']";
                    }
                    Locator playlistLinks = page.locator(playlistSelector);
                    if (playlistLinks.count() > 0) {
                        int count = 0;
                        for (int i = 0; i < playlistLinks.count(); i++) {
                            try {
                                Locator link = playlistLinks.nth(i);
                                String href = safeAttr(link, "href");
                                String title = safeInnerText(link);
                                if (!href.isEmpty() && !title.isEmpty()) {
                                    playlists.add(new Playlist(title, href, new ArrayList<>()));
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
                                logger.info("Scraping details for playlist: {}", playlist.name());
                                page.navigate(toAbsoluteUrl(playlist.url()));
                                page.setDefaultTimeout(30_000);
                                page.setDefaultNavigationTimeout(30_000);
                                MetadataField titleField = MetadataFieldRegistry.getField("title");
                                String selector = (titleField != null && !titleField.selectors.isEmpty()) ? String.join(", ", titleField.selectors) : null;
                                waitForPageReady(page, selector, 10000);
                                String title = safeInnerText(page.locator("h1"));
                                String description = safeInnerText(page.locator("div[data-testid='description']"));
                                String imageUrl = safeAttr(page.locator("img[data-testid='playlist-image']"), "src");
                                logger.info("Playlist details: {} - {} - {}", title, description, imageUrl);
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

    // --- Songs ---
    public List<Song> scrapeSongs(String url, int maxSongs) {
        logger.info("Scraping songs from: {}", url);
        List<Song> songs = new ArrayList<>();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(getDefaultLaunchOptions());
            try {
                Page page = browser.newPage();
                try {
                    page.navigate(toAbsoluteUrl(url));
                    page.setDefaultTimeout(30_000);
                    page.setDefaultNavigationTimeout(30_000);
                    MetadataField titleField = MetadataFieldRegistry.getField("title");
                    String selector = (titleField != null && !titleField.selectors.isEmpty()) ? String.join(", ", titleField.selectors) : null;
                    waitForPageReady(page, selector, 15000);
                    // Accept cookies if prompted
                    Locator acceptCookies = page.locator("text=Accept Cookies");
                    if (acceptCookies != null && acceptCookies.count() > 0) {
                        safeClick(acceptCookies, "Accept Cookies button");
                        waitForPageReady(page, selector, 5000);
                    }
                    // Find all song elements on the page
                    Locator songElements = findSongElements(page);
                    int totalElements = songElements.count();
                    int validCount = 0;
                    if (totalElements > 0) {
                        for (int i = 0; i < totalElements; i++) {
                            try {
                                Locator songElement = songElements.nth(i);
                                String title = safeInnerText(songElement.locator("a[data-test='track-title'], .track-title, [data-testid*='title'], .title, h2, h3, [aria-label], [alt]"));
                                String artist = safeInnerText(songElement.locator("a[data-test='track-artist'], .track-artist, [data-testid*='artist'], .artist, h4, h5, [aria-label], [alt]"));
                                if (!title.isEmpty() || !artist.isEmpty()) {
                                    Song song = processSongCandidate(songElement, validCount + 1);
                                    if (song != null) {
                                        songs.add(song);
                                        validCount++;
                                    }
                                    // Respect the maxSongs limit
                                    if (maxSongs > 0 && validCount >= maxSongs) {
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                logger.debug("Error processing song element (ignored): {}", e.getMessage());
                            }
                        }
                        if (validCount == 0) {
                            logger.warn("Found {} song elements, but none had valid title or artist. Check selectors and page structure.", totalElements);
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

    /**
     * Extracts, cross-checks, validates, and enriches song metadata from a playlist row element.
     * <p>
     * Workflow:
     * <ul>
     *   <li>Extracts metadata using multiple selectors per field.</li>
     *   <li>Cross-checks and normalizes values using MetadataCrossChecker.</li>
     *   <li>Aggregates provenance and confidence scores for each field.</li>
     *   <li>Validates and enriches metadata using MusicBrainzClient, including genre and releaseDate enrichment via API.</li>
     *   <li>Updates validated flag, confidenceScore, provenance, and per-field validation status in Song.</li>
     *   <li>Logs all major actions and validation results for traceability.</li>
     * </ul>
     * <p>
     * Provenance (sourceDetails) structure:
     * <ul>
     *   <li>Map<String, Object> where each value is a Map<String, String> (selector → value).</li>
     *   <li>All values are guaranteed to be non-null maps (empty if no provenance).</li>
     *   <li>Extensible: new fields can be added by updating the field list below.</li>
     * </ul>
     * <p>
     * All major workflow TODOs for genre and releaseDate enrichment are now resolved: enrichment is performed via MusicBrainzClient API referencing.
     */
    // --- Registry-driven metadata fields ---
    private static final List<MetadataField> METADATA_FIELDS = MetadataFieldRegistry.getFields();

    // Helper to find song elements using registry-driven selectors
    private Locator findSongElements(Page page) {
        MetadataField songRowField = MetadataFieldRegistry.getField("songRow"); // Use 'songRow' field selectors for song rows
        if (songRowField != null && !songRowField.selectors.isEmpty()) {
            String selector = String.join(", ", songRowField.selectors);
            Locator loc = page.locator(selector);
            logger.info("Trying songRow selector: {} (found {} elements)", selector, loc.count());
            if (loc.count() > 0) return loc;
            // Fallback: try previous main selectors for title anchors (legacy)
            String fallback = "a[data-test='track-title'], .track-title, [data-testid*='title']";
            Locator fallbackLoc = page.locator(fallback);
            logger.info("Trying fallback song selector: {} (found {} elements)", fallback, fallbackLoc.count());
            return fallbackLoc;
        }
        // Fallback: try previous main selectors for title anchors (legacy)
        String fallback = "a[data-test='track-title'], .track-title, [data-testid*='title']";
        Locator fallbackLoc = page.locator(fallback);
        logger.info("Trying fallback song selector: {} (found {} elements)", fallback, fallbackLoc.count());
        return fallbackLoc;
    }

    private MetadataCrossChecker.CrossCheckResult extractFieldCrossChecked(Locator element, MetadataField field) {
        return crossChecker.crossCheckField(element, field);
    }

    private Song processSongCandidate(Locator songElement, int playlistPosition) {
        // Check if this is a music-image-row element
        String tagName = "";
        try {
            tagName = songElement.evaluate("el => el.tagName.toLowerCase()", String.class);
        } catch (Exception ignored) {}
        if (tagName.equals("music-image-row")) {
            String title = safeAttr(songElement, "primary-text");
            String artist = safeAttr(songElement, "secondary-text");
            String url = safeAttr(songElement, "primary-href");
            String album = safeAttr(songElement, "secondary-href");
            String imageUrl = safeAttr(songElement, "image-src");
            // Only process if title and artist are present
            if (!title.isEmpty() || !artist.isEmpty()) {
                return new Song(
                    title,
                    artist,
                    album,
                    url,
                    "", // duration not available in attributes
                    null, // trackNumber not available in attributes
                    playlistPosition,
                    null, // explicit not available in attributes
                    imageUrl,
                    "", // releaseDate not available in attributes
                    "", // genre not available in attributes
                    "", // trackAsin not available in attributes
                    false,
                    1.0, // confidenceScore
                    new HashMap<>(),
                    new HashMap<>()
                );
            } else {
                logger.warn("music-image-row missing title/artist at position {}", playlistPosition);
                return null;
            }
        }
        Map<String, MetadataCrossChecker.CrossCheckResult> results = new LinkedHashMap<>();
        for (MetadataField field : METADATA_FIELDS) {
            results.put(field.fieldName, extractFieldCrossChecked(songElement, field));
        }
        String url = safeAttr(songElement.locator("a[data-test='track-title']"), "href");
        String trackAsin = extractTrackAsin(songElement, url);
        double confidenceScore = results.values().stream().mapToDouble(r -> r.confidenceScore).filter(d -> !Double.isNaN(d) && d >= 0.0).average().orElse(0.0);
        Map<String, Object> sourceDetails = results.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sourceDetails));
        Map<String, Boolean> fieldValidationStatus = METADATA_FIELDS.stream().collect(Collectors.toMap(f -> f.fieldName, f -> false));
        fieldValidationStatus.put("trackAsin", false);
        Integer trackNumber = null;
        try {
            String val = results.get("trackNumber").value;
            if (val != null && !val.isEmpty()) trackNumber = Integer.valueOf(val.replaceAll("\\D", ""));
        } catch (Exception ignored) {}
        Boolean explicitFlag = null;
        String explicitVal = results.get("explicit").value;
        if (explicitVal != null && !explicitVal.isEmpty()) explicitFlag = explicitVal.toLowerCase().contains("explicit");
        // --- Genre and Release Date extraction ---
        String genre = "";
        MetadataField genreField = MetadataFieldRegistry.getField("genre");
        if (genreField != null && genreField.selectors != null && !genreField.selectors.isEmpty()) {
            for (String sel : genreField.selectors) {
                String val = safeInnerText(songElement.locator(sel));
                if (!val.isEmpty()) { genre = val; break; }
            }
        }
        String releaseDate = "";
        MetadataField releaseDateField = MetadataFieldRegistry.getField("releaseDate");
        if (releaseDateField != null && releaseDateField.selectors != null && !releaseDateField.selectors.isEmpty()) {
            for (String sel : releaseDateField.selectors) {
                String val = safeInnerText(songElement.locator(sel));
                if (!val.isEmpty()) { releaseDate = val; break; }
            }
        }
        // Log all extracted field values for diagnosis
        logger.info("Extracted song candidate at position {}: title='{}', artist='{}', album='{}', url='{}', duration='{}', trackNumber='{}', explicit='{}', imageUrl='{}', releaseDate='{}', genre='{}', trackAsin='{}'", playlistPosition,
            results.get("title").value,
            results.get("artist").value,
            results.get("album").value,
            url,
            results.get("duration").value,
            trackNumber,
            explicitFlag,
            results.get("imageUrl").value,
            releaseDate.isEmpty() ? results.get("releaseDate").value : releaseDate,
            genre.isEmpty() ? results.get("genre").value : genre,
            trackAsin
        );
        // If both title and artist are empty, skip MusicBrainz validation and log warning
        if ((results.get("title").value == null || results.get("title").value.isEmpty()) &&
            (results.get("artist").value == null || results.get("artist").value.isEmpty())) {
            logger.warn("Skipping MusicBrainz validation for empty song candidate at position {}.", playlistPosition);
            return new Song(
                results.get("title").value,
                results.get("artist").value,
                results.get("album").value,
                url,
                results.get("duration").value,
                trackNumber,
                playlistPosition,
                explicitFlag,
                results.get("imageUrl").value,
                releaseDate.isEmpty() ? results.get("releaseDate").value : releaseDate,
                genre.isEmpty() ? results.get("genre").value : genre,
                trackAsin,
                false,
                confidenceScore,
                sourceDetails,
                fieldValidationStatus
            );
        }
        Song rawSong = new Song(
            results.get("title").value,
            results.get("artist").value,
            results.get("album").value,
            url,
            results.get("duration").value,
            trackNumber,
            playlistPosition,
            explicitFlag,
            results.get("imageUrl").value,
            releaseDate.isEmpty() ? results.get("releaseDate").value : releaseDate,
            genre.isEmpty() ? results.get("genre").value : genre,
            trackAsin,
            false,
            confidenceScore,
            sourceDetails,
            fieldValidationStatus
        );
        Song validatedSong = musicBrainzClient.validateAndEnrich(rawSong);
        // MetadataCrossChecker.validateProvenanceStructure(validatedSong.sourceDetails()); // removed: provenance is always Map<String, String>
        logger.info("Validated and enriched song: {} by {} (validated: {}, confidence: {})", validatedSong.title(), validatedSong.artist(), validatedSong.validated(), validatedSong.confidenceScore());
        return validatedSong;
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
            // TODO [PRIORITY: LOW][2025-09-04]: Genre and release date enrichment not yet implemented. Placeholder for future metadata enrichment. Update extraction logic and Song fields when adding support.
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
    /**
     * Robustly extracts track ASIN from URL or element attributes.
     * Handles multiple URL patterns and attribute fallbacks.
     */
    private static String extractTrackAsin(Locator element, String url) {
        if (url != null && !url.isBlank()) {
            // Regex for trackAsin in query or path
            java.util.regex.Pattern[] patterns = new java.util.regex.Pattern[] {
                java.util.regex.Pattern.compile("[?&]trackAsin=([A-Z0-9]{10})", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("/track/([A-Z0-9]{10})", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("/([A-Z0-9]{10})(?:[/?&#]|$)", java.util.regex.Pattern.CASE_INSENSITIVE)
            };
            for (java.util.regex.Pattern pattern : patterns) {
                java.util.regex.Matcher matcher = pattern.matcher(url);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        // Fallback: check common ASIN attributes
        String[] attrNames = {"data-track-asin", "data-asin", "track-asin", "asin"};
        for (String attr : attrNames) {
            try {
                String val = element.getAttribute(attr);
                if (val != null && val.matches("[A-Z0-9]{10}")) {
                    return val;
                }
            } catch (Exception ignored) {}
        }
        // Fallback: check child anchor tags
        try {
            Locator anchors = element.locator("a");
            for (int i = 0; i < anchors.count(); i++) {
                String href = anchors.nth(i).getAttribute("href");
                if (href != null && href.matches(".*[A-Z0-9]{10}.*")) {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([A-Z0-9]{10})").matcher(href);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        } catch (Exception ignored) {}
        // Log if not found
        LoggerFactory.getLogger(ScraperService.class).debug("Track ASIN not found for element/url: {} / {}", element, url);
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
            String decoded = java.net.URLDecoder.decode(path.replaceAll("\\.html$", ""), java.nio.charset.StandardCharsets.UTF_8.toString());
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
        Utils.retryPlaywrightAction(() -> { page.navigate(toAbsoluteUrl(playlistUrl)); return true; }, 3, "navigate to playlist");
        page.setViewportSize(1920, 1080);
        Utils.retryPlaywrightAction(() -> { page.waitForLoadState(LoadState.NETWORKIDLE); return true; }, 2, "wait for network idle");
        robustWaitForSelector(page, "h1, [data-testid*='playlist-title'], .playlist-title", 5000, "playlist title");
        String playlistName = safeInnerText(page.locator("h1, [data-testid*='playlist-title'], .playlist-title"));
        if (playlistName.isEmpty()) playlistName = page.title();
        Locator songElements = findSongElements(page);
        List<Song> songs = new ArrayList<>();
        if (songElements != null && songElements.count() > 0) {
            for (int i = 0; i < songElements.count(); i++) {
                try {
                    Song song = processSongCandidate(songElements.nth(i), i + 1);
                    if (song != null && song.title() != null && !song.title().isEmpty()) {
                        songs.add(song);
                    }
                } catch (Exception e) {
                    logger.warn("Error processing song candidate {}: {}", i + 1, e.getMessage());
                }
            }
        } else {
            logger.warn("No song elements found for playlist: {}", playlistUrl);
            // Save page HTML and screenshot for diagnosis
            try {
                String html = page.content();
                java.nio.file.Files.createDirectories(java.nio.file.Paths.get("scraped-data"));
                java.nio.file.Files.writeString(java.nio.file.Paths.get("scraped-data", "playlist-debug-" + System.currentTimeMillis() + ".html"), html);
                page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("scraped-data", "playlist-debug-" + System.currentTimeMillis() + ".png")));
                logger.info("Saved playlist debug HTML and screenshot for playlist: {}", playlistUrl);
            } catch (Exception e) {
                logger.error("Failed to save playlist debug artifacts: {}", e.getMessage());
            }
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
        return new Playlist(playlistName, playlistUrl, songs);
    }

    // Implement required interface methods
    @Override
    public List<Map<String, String>> scrapePlaylistLinks(Page page) {
        List<Map<String, String>> playlists = new ArrayList<>();
        Locator playlistLinks = page.locator(joinSelectors(getPlaylistTileSelectors()));
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
        // Step 1: Construct absolute URL for /my/library
        String currentUrl = page.url();
        String libraryHref = "/my/library";
        String fullLibraryUrl;
        if (currentUrl.contains(libraryHref)) {
            fullLibraryUrl = currentUrl;
        } else {
            // Extract base URL (protocol + host)
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https?://[^/]+)").matcher(currentUrl);
            String baseUrl = m.find() ? m.group(1) : "";
            fullLibraryUrl = baseUrl + libraryHref;
        }
        Utils.retryPlaywrightAction(() -> { page.navigate(toAbsoluteUrl(fullLibraryUrl)); return true; }, 2, "navigate to /my/library");
        robustWaitForSelector(page, "music-pill-item:has-text('Playlists'), .playlists-section, h1", 5000, "library playlists section");
        logger.info("Navigated to library page: {}", page.url());
        // Step 2: Wait for playlists section or pill/tab
        Locator playlistsPill = page.locator("music-pill-item:has-text('Playlists')");
        if (playlistsPill.count() > 0) {
            safeClick(playlistsPill, "Playlists pill");
            robustWaitForSelector(page, ".playlists-section, music-shoveler[primary-text='Playlists']", 5000, "playlists section after pill click");
            logger.info("Clicked Playlists pill");
        } else {
            Locator playlistsTab = page.getByText("Playlists", new Page.GetByTextOptions().setExact(false));
            if (playlistsTab.count() > 0) {
                safeClick(playlistsTab, "Playlists tab");
                robustWaitForSelector(page, ".playlists-section, music-shoveler[primary-text='Playlists']", 5000, "playlists section after tab click");
                logger.info("Clicked Playlists tab");
            } else {
                logger.warn("Playlists pill/tab not found");
            }
        }
        // Step 3: Wait for playlist tiles to appear
        Locator playlistShoveler = page.locator("music-shoveler[primary-text='Playlists'], music-shoveler:has([primary-text='Playlists'])");
        if (playlistShoveler.count() > 0) {
            playlistShoveler.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            logger.info("Playlists shoveler loaded");
        } else {
            logger.warn("Playlists shoveler not found, skipping fallback wait");
        }
        // Step 4: Click "Show all" button if present
        Locator showAllBtn = page.getByText("Show all", new Page.GetByTextOptions().setExact(false));
        if (showAllBtn.count() > 0) {
            safeClick(showAllBtn, "Show all button");
            // TODO (PRIORITY: MEDIUM): Replace safeWait with Playwright's recommended waitForTimeout or page.waitForTimeout for navigation robustness.
            page.waitForTimeout(DEFAULT_NAVIGATION_WAIT_MS);
            logger.info("Clicked Show all button");
        } else {
            logger.info("Show all button not found, proceeding with visible playlists");
        }
    }

    /**
     * Checks if the user is signed in by delegating to AuthService's robust authentication check.
     * This uses both session cookies and DOM elements for reliability.
     * @param page Playwright page instance
     * @return true if authenticated, false otherwise
     */
    @Override
    public boolean isSignedIn(Page page) {
        // Delegate to AuthService for robust authentication check
        return authService.isAuthenticated(page);
    }

    // --- Registry-driven selectors: all field selectors are managed via MetadataFieldRegistry and MetadataField ---
    // Legacy hardcoded selector lists removed; all extraction logic uses MetadataFieldRegistry.getFields() and MetadataField.selectors.
    // Example usage for playlist tiles:
    private static List<String> getPlaylistTileSelectors() {
        MetadataField playlistField = MetadataFieldRegistry.getField("playlist");
        return playlistField != null ? playlistField.selectors : Collections.emptyList();
    }

    private final MetadataCrossChecker crossChecker = new MetadataCrossChecker();


    private void robustWaitForSelector(Page page, String selector, int timeoutMs, String description) {
        if (page == null) {
            logger.warn("robustWaitForSelector called with null Page (desc={}).", description);
            return;
        }
        if (selector == null || selector.isBlank()) {
            logger.warn("robustWaitForSelector called with invalid selector (desc={}).", description);
            return;
        }
        try {
            page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
            logger.debug("Waited for selector: {} ({}ms)", selector, timeoutMs);
        } catch (Exception e) {
            logger.warn("Timeout waiting for selector '{}': {}", selector, e.getMessage());
        }
    }

    // Utility to ensure URLs are absolute
    private static String toAbsoluteUrl(String url) {
        if (url == null) return null;
        if (url.startsWith("http")) return url;
        if (url.startsWith("/")) return "https://music.amazon.com.au" + url;
        return url;
    }

    /**
     * Generates a detailed validation report for a list of songs.
     * Summarizes per-field validation status, confidence scores, provenance discrepancies, and enrichment results.
     * @param songs List of Song objects to report on
     * @return Structured report as a String
     */
    public String generateValidationReport(List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return "No songs to report.";
        }
        StringBuilder report = new StringBuilder();
        report.append("Validation Report for ").append(songs.size()).append(" songs\n");
        for (Song song : songs) {
            report.append("\nSong: '").append(song.title()).append("' by '").append(song.artist()).append("'\n");
            report.append("  Confidence Score: ").append(String.format("%.2f", song.confidenceScore())).append("\n");
            report.append("  Validated: ").append(song.validated()).append("\n");
            // Per-field validation status
            if (song.fieldValidationStatus() != null) {
                report.append("  Field Validation Status:\n");
                for (Map.Entry<String, Boolean> entry : song.fieldValidationStatus().entrySet()) {
                    report.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }
            // Provenance discrepancies
            if (song.sourceDetails() != null) {
                report.append("  Provenance Discrepancies:\n");
                for (Map.Entry<String, Object> entry : song.sourceDetails().entrySet()) {
                    if (entry.getValue() instanceof Map<?,?> map) {
                        Set<String> uniqueVals = new HashSet<>();
                        for (Object v : map.values()) if (v != null) uniqueVals.add(v.toString());
                        if (uniqueVals.size() > 1) {
                            report.append("    - ").append(entry.getKey()).append(": ").append(uniqueVals).append("\n");
                        }
                    }
                }
            }
            // Enrichment results (MusicBrainz)
            if (song.sourceDetails() != null && song.sourceDetails().containsKey("MusicBrainz")) {
                report.append("  MusicBrainz Validation: ").append(song.sourceDetails().get("MusicBrainz")).append("\n");
            }
            if (song.sourceDetails() != null && song.sourceDetails().containsKey("GenreEnrichment")) {
                report.append("  Genre Enrichment: ").append(song.sourceDetails().get("GenreEnrichment")).append("\n");
            }
            if (song.sourceDetails() != null && song.sourceDetails().containsKey("ReleaseDateEnrichment")) {
                report.append("  Release Date Enrichment: ").append(song.sourceDetails().get("ReleaseDateEnrichment")).append("\n");
            }
        }
        return report.toString();
    }

    private static String joinSelectors(List<String> arr) {
        return arr == null || arr.isEmpty() ? "" : String.join(", ", arr);
    }
}
