package com.amazonmusic.scraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

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

    // --- CSV / known title helpers ---
    // Cache of known titles (normalized -> original) and known artists
    private static volatile Map<String, String> KNOWN_TITLES = null;
    private static volatile Map<String, String> KNOWN_ARTISTS = null;
    // Map of track ASIN -> title/artist from CSV URLs
    private static volatile Map<String, String> TRACK_ASIN_TO_TITLE = null;
    private static volatile Map<String, String> TRACK_ASIN_TO_ARTIST = null;

    private static String normalizeKey(String s) {
        if (s == null) return "";
        String low = s.toLowerCase(Locale.ROOT).trim();
        low = low.replaceAll("[\\p{Punct}&&[^'&]]+", " ");
        low = low.replaceAll("\\s+", " ");
        return low.trim();
    }

    private static List<String> parseCsvLine(String line) {
        if (line == null) return Collections.emptyList();
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString());
        cols.replaceAll(String::trim);
        return cols;
    }

    private static synchronized void ensureKnownTitlesLoaded() {
        if (KNOWN_TITLES != null) return;
        Map<String, String> map = new HashMap<>();
        Map<String, String> artists = new HashMap<>();
        Map<String, String> trackTitle = new HashMap<>();
        Map<String, String> trackArtist = new HashMap<>();
        try {
            var cwd = Paths.get(System.getProperty("user.dir"));
            if (Files.exists(cwd) && Files.isDirectory(cwd)) {
                try (var stream = Files.list(cwd)) {
                    List<Path> csvs = stream.filter(p -> p.toString().toLowerCase().endsWith(".csv")).collect(Collectors.toList());
                    for (var csv : csvs) {
                        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
                            String ln;
                            boolean firstLine = true;
                            while ((ln = reader.readLine()) != null) {
                                if (ln.isBlank()) continue;
                                if (firstLine && ln.toLowerCase().contains("title") && ln.toLowerCase().contains("artist")) { firstLine = false; continue; }
                                firstLine = false;
                                List<String> cols = parseCsvLine(ln);
                                if (cols.isEmpty()) continue;
                                String first = cols.get(0).trim();
                                if (first.startsWith("\"") && first.endsWith("\"")) first = first.substring(1, first.length() - 1);
                                if (first.isBlank()) continue;
                                String key = normalizeKey(first);
                                if (!map.containsKey(key)) map.put(key, first);
                                if (cols.size() > 1) {
                                    String maybeArtist = cols.get(1).trim();
                                    if (maybeArtist.startsWith("\"") && maybeArtist.endsWith("\"")) maybeArtist = maybeArtist.substring(1, maybeArtist.length() - 1);
                                    if (!maybeArtist.isBlank() && !artists.containsKey(key)) artists.put(key, maybeArtist);
                                }
                                if (cols.size() > 3) {
                                    String rawUrl = cols.get(3).trim();
                                    String asin = extractTrackAsinFromUrl(rawUrl);
                                    if (!asin.isEmpty()) {
                                        if (!trackTitle.containsKey(asin)) trackTitle.put(asin, first);
                                        if (cols.size() > 1) {
                                            String maybeArtist = cols.get(1).trim();
                                            if (maybeArtist.startsWith("\"") && maybeArtist.endsWith("\"")) maybeArtist = maybeArtist.substring(1, maybeArtist.length() - 1);
                                            if (!maybeArtist.isBlank() && !trackArtist.containsKey(asin)) trackArtist.put(asin, maybeArtist);
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        KNOWN_TITLES = map;
        KNOWN_ARTISTS = artists;
        TRACK_ASIN_TO_TITLE = trackTitle;
        TRACK_ASIN_TO_ARTIST = trackArtist;
    }

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

    private static String[] parseAriaLabel(String aria) {
        if (aria == null) return new String[0];
        String[] parts = aria.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    private String findKnownTitleInText(String text) {
        if (text == null || text.isBlank()) return "";
        ensureKnownTitlesLoaded();
        if (KNOWN_TITLES == null || KNOWN_TITLES.isEmpty()) return "";
        String norm = normalizeKey(text);
        String bestKey = "";
        for (var entry : KNOWN_TITLES.entrySet()) {
            String key = entry.getKey();
            if (norm.contains(key)) {
                if (key.length() > bestKey.length()) bestKey = key;
            }
        }
        return bestKey.isEmpty() ? "" : KNOWN_TITLES.get(bestKey);
    }

    private String getKnownArtistForTitle(String title) {
        if (title == null || title.isBlank()) return "";
        ensureKnownTitlesLoaded();
        if (KNOWN_ARTISTS == null || KNOWN_ARTISTS.isEmpty()) return "";
        String key = normalizeKey(title);
        return KNOWN_ARTISTS.getOrDefault(key, "");
    }

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
                if ("role=row".equals(sel)) {
                    Locator r = page.getByRole(com.microsoft.playwright.options.AriaRole.ROW);
                    if (r != null && r.count() > 0) return r;
                } else {
                    Locator loc = page.locator(sel);
                    if (loc != null && loc.count() > 0) return loc;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Scrapes a playlist and its songs from the given URL.
     */
    public Playlist scrapePlaylist(Page page, String playlistUrl) {
        logger.info("Scraping playlist: {}", playlistUrl);
        try { authService.waitForAuthUi(page); } catch (Exception ignored) {}

        Utils.retryPlaywrightAction(() -> { page.navigate(playlistUrl); return true; }, 3, "navigate to playlist");

        page.setViewportSize(1920, 1080);

        // Wait for content or an empty state
        Utils.retryPlaywrightAction(() -> waitForPlaylistContent(page), 3, "wait for playlist content");

        // gentle scroll to help lazy-loading
        Utils.retryPlaywrightAction(() -> { page.evaluate("window.scrollTo(0, document.body.scrollHeight)"); page.waitForTimeout(1000); return true; }, 2, "initial scroll");

        int expectedSongCount = 0;
        try {
            Locator countLocator = page.getByText("songs", new Page.GetByTextOptions().setExact(false));
            if (countLocator.count() > 0) {
                String countText = countLocator.first().innerText().replaceAll("\\D", "");
                if (!countText.isEmpty()) expectedSongCount = Integer.parseInt(countText);
            }
        } catch (Exception ignored) {}

        String playlistName = page.title();
        var songs = new ArrayList<Song>();

        // load more content with bounded loop to avoid infinite scrolling
        Utils.retryPlaywrightAction(() -> {
            int attempts = 0;
            int maxAttempts = 10;
            int prevCount = -1;
            while (attempts++ < maxAttempts) {
                page.evaluate("window.scrollBy(0, window.innerHeight)");
                page.waitForTimeout(800);
                int curCount = 0;
                try { curCount = page.locator(joinSelectors(SONG_SELECTOR_CANDIDATES)).count(); } catch (Exception ignored) {}
                if (curCount == prevCount) break;
                prevCount = curCount;
            }
            return true;
        }, 3, "scroll to load songs");

        // wait for network idle
        try { 
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(DEFAULT_ELEMENT_WAIT_MS)); 
        } catch (Exception e) {
            logger.debug("Network idle wait timeout during song loading: {}", e.getMessage());
        }

        Locator songLocator = findFirstMatchingLocator(page);
        if (songLocator == null) {
            logger.warn("No songs found in playlist: {}", playlistName);
            saveDebugArtifactsIfAllowed(page, "debug-scrape-fail", joinSelectors(SONG_SELECTOR_CANDIDATES));
            return new Playlist(playlistName, songs);
        }

        int count = songLocator.count();
        logger.info("Found {} song elements. Beginning scraping.", count);
        for (int i = 0; i < count; i++) {
            Locator songEl = songLocator.nth(i);
            String title = "", artist = "", album = "", url = "", duration = "", imageUrl = "";
            Integer trackNumber = null;

            // Tightened selectors prioritizing col1..col4 structure and component attributes
            try {
                // Primary text attributes (some components expose primary-text / secondary-text-1/2)
                String prim = safeAttr(songEl, "primary-text");
                String sec1 = safeAttr(songEl, "secondary-text-1");
                String sec2 = safeAttr(songEl, "secondary-text-2");
                if (prim != null && !prim.isEmpty()) title = prim;
                if (sec1 != null && !sec1.isEmpty()) artist = sec1;
                if (sec2 != null && !sec2.isEmpty()) album = sec2;

                // DOM column-based selectors (prefer anchors with tabindex; col1=title, col2=artist, col3=album, col4=duration)
                if (title.isEmpty()) title = safeInnerText(songEl.locator("div.col1 a[tabindex], div.col1 a, div.col1"));
                if (artist.isEmpty()) artist = safeInnerText(songEl.locator("div.col2 a[tabindex], div.col2 a, div.col2"));
                if (album.isEmpty()) album = safeInnerText(songEl.locator("div.col3 a[tabindex], div.col3 a, div.col3"));
                if (duration.isEmpty()) duration = safeInnerText(songEl.locator("div.col4 span[tabindex], div.col4 span, div.col4"));

                // URL: prefer anchor within col1 or primary-href attribute
                url = safeAttr(songEl.locator("div.col1 a[href], div.col1 music-link a[href], a[href]"), "href");
                if ((url == null || url.isEmpty())) url = safeAttr(songEl, "primary-href");
                if ((url == null || url.isEmpty())) url = safeAttr(songEl, "href");
                if (url != null && url.startsWith("/")) url = DEFAULT_BASE_URL + url;

                // Track number: explicit span.index or .index
                String numTxt = safeInnerText(songEl.locator("span.index, .index, .track-number, .position, .num"));
                if (!numTxt.isEmpty()) {
                    numTxt = numTxt.replaceAll("\\D", "");
                    if (!numTxt.isEmpty()) trackNumber = Integer.parseInt(numTxt);
                }

                // Image: prefer image-src attr on component, then nested img
                String imgAttr = safeAttr(songEl, "image-src");
                if (imgAttr != null && !imgAttr.isEmpty()) imageUrl = imgAttr;
                if ((imageUrl == null || imageUrl.isEmpty())) imageUrl = safeAttr(songEl.locator("music-image, music-image img, img"), "src");
                if ((imageUrl == null || imageUrl.isEmpty())) imageUrl = safeAttr(songEl.locator("img"), "data-src");
                if (imageUrl == null) imageUrl = "";

            } catch (Exception ignored) {}

            // Fallback: parse aria label if needed
            try {
                if ((title == null || title.isEmpty())) {
                    String aria = safeAttr(songEl.locator("*[aria-label]"), "aria-label");
                    if (aria != null && !aria.isEmpty()) {
                        String[] parts = parseAriaLabel(aria);
                        if (parts.length >= 1) title = parts[0];
                        if (parts.length >= 2 && (artist == null || artist.isEmpty())) artist = parts[1];
                        if (parts.length >= 3 && (album == null || album.isEmpty())) album = parts[2];
                    }
                }
            } catch (Exception ignored) {}

            // fallback: use CSV trackAsin mapping if title missing
            if (title == null || title.isEmpty()) {
                String asin = extractTrackAsinFromUrl(url);
                if (!asin.isEmpty()) {
                    ensureKnownTitlesLoaded();
                    if (TRACK_ASIN_TO_TITLE != null && TRACK_ASIN_TO_TITLE.containsKey(asin)) {
                        title = TRACK_ASIN_TO_TITLE.get(asin);
                        if ((artist == null || artist.isEmpty()) && TRACK_ASIN_TO_ARTIST != null) {
                            artist = TRACK_ASIN_TO_ARTIST.getOrDefault(asin, artist);
                        }
                    }
                }
            }

            // Use SoundCloud to enrich metadata if client id provided and we are missing image/duration/album
            try {
                if (SOUND_CLOUD_CLIENT_ID != null && !SOUND_CLOUD_CLIENT_ID.isBlank() && title != null && !title.isBlank()) {
                    boolean needDur = (duration == null || duration.isBlank());
                    boolean needImg = (imageUrl == null || imageUrl.isBlank());
                    boolean needAlbum = (album == null || album.isBlank());
                    if (needDur || needImg || needAlbum) {
                        Map<String, String> sc = fetchSoundCloudMetadata(title, artist);
                        if (sc != null && !sc.isEmpty()) {
                            if (needDur && sc.containsKey("duration")) duration = sc.get("duration");
                            if (needImg && sc.containsKey("artwork_url")) imageUrl = sc.get("artwork_url");
                            if (needAlbum && sc.containsKey("album")) album = sc.get("album");
                            // prefer permalink_url if we lack url
                            if ((url == null || url.isEmpty()) && sc.containsKey("permalink_url")) url = sc.get("permalink_url");
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (title == null || title.isEmpty()) {
                logger.warn("Skipping song with empty title at position {} in playlist {}", i + 1, playlistName);
                continue;
            }

            Song songObj = new Song(
                title,
                artist,
                album,
                url,
                duration,
                trackNumber,
                i + 1,
                null,
                imageUrl,
                "",
                ""
            );

            songs.add(songObj);
         }

        // Persist playlist and songs in bulk using PostgresService if available
        try {
            if (postgresService != null && !songs.isEmpty()) {
                postgresService.createTables();
                int pid = postgresService.insertPlaylist(playlistName, playlistUrl);
                if (pid > 0) postgresService.insertSongs(pid, songs);
            }
        } catch (Exception e) {
            logger.debug("DB persistence skipped or failed: {}", e.getMessage());
        }

        if (songs.isEmpty()) {
            logger.warn("No songs were scraped. Check page structure or network.");
            saveDebugArtifactsIfAllowed(page, "debug-scrape-fail", joinSelectors(SONG_SELECTOR_CANDIDATES));
        }

        if (expectedSongCount > 0 && songs.size() < expectedSongCount * 0.9) {
            logger.warn("Scraped song count ({}) is lower than expected ({}).", songs.size(), expectedSongCount);
        } else {
            logger.info("Successfully scraped {} songs.", songs.size());
        }

        return new Playlist(playlistName, songs);
    }

    /**
     * Scrapes playlist links from the user's library page.
     */
    public List<Map<String, String>> scrapePlaylistLinks(Page page) {
        logger.info("Scraping playlist links...");
        try { authService.waitForAuthUi(page); } catch (Exception ignored) {}
        var playlists = new ArrayList<Map<String, String>>();
        var seen = new HashSet<String>();

        try { 
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(DEFAULT_ELEMENT_WAIT_MS)); 
        } catch (Exception e) {
            logger.debug("Network idle wait timeout or failed: {}", e.getMessage());
        }

        // collect candidate elements from the page using tile selectors and anchors
        List<ElementHandle> candidates = new ArrayList<>();
        try {
            var found = page.querySelectorAll(joinSelectors(PLAYLIST_TILE_SELECTORS) + ", a[href*='/playlist']");
            if (found != null) candidates.addAll(found);
            // also include anchors with playlist-like hrefs
            var anchors = page.querySelectorAll("a[href*='/playlist'], a[href*='playlist']");
            if (anchors != null) candidates.addAll(anchors);
        } catch (Exception ignored) {}

        for (var it : candidates) {
            try {
                String url = "";
                String name = "";
                try { url = it.getAttribute("primary-href"); } catch (Exception ignored) {}
                if (url == null || url.isEmpty()) {
                    try { var a = it.querySelector("a[href], music-link, [role='link']"); if (a != null) url = a.getAttribute("href"); } catch (Exception ignored) {}
                }
                try { name = it.getAttribute("primary-text"); } catch (Exception ignored) {}
                if ((name == null || name.isEmpty())) {
                    try { name = it.innerText(); } catch (Exception ignored) {}
                }
                if (url == null || url.isEmpty()) continue;
                if (!url.startsWith("http")) url = DEFAULT_BASE_URL + url;
                String lower = url.toLowerCase();
                boolean looksLikePlaylist = lower.contains("/playlist") || lower.contains("/playlists") || lower.contains("/my/playlists");
                boolean isAlbumOrStation = lower.contains("/albums") || lower.contains("/stations");
                if (looksLikePlaylist && !isAlbumOrStation && !seen.contains(url)) {
                    if (name == null || name.trim().isEmpty()) name = "(unknown)";
                    var m = new HashMap<String, String>();
                    m.put("name", name.trim());
                    m.put("url", url);
                    playlists.add(m);
                    seen.add(url);
                }
            } catch (Exception ignored) {}
        }

        if (playlists.isEmpty()) {
            logger.error("No playlist items found after heuristics.");
            saveDebugArtifactsIfAllowed(page, "debug-playlist-links", joinSelectors(PLAYLIST_TILE_SELECTORS));
        } else {
            logger.info("Found {} playlists.", playlists.size());
        }

        return playlists;
    }

    /**
     * Navigates to the Library and Playlists tabs using robust selectors.
     */
    public void goToLibraryPlaylists(Page page) {
        logger.info("Navigating to Library...");
        try { authService.waitForAuthUi(page); } catch (Exception ignored) {}

        try {
            // Try visible text first
            Locator libraryButton = page.getByText("Library", new Page.GetByTextOptions().setExact(false));
            if (safeClick(libraryButton, "Library button")) {
                safeWait(page, DEFAULT_NAVIGATION_WAIT_MS);
            }

            // Try Playlists tab by text then selectors
            Locator playlistsTab = page.getByText("Playlists", new Page.GetByTextOptions().setExact(false));
            if (safeClick(playlistsTab, "Playlists tab")) {
                logger.info("Successfully navigated to Playlists via text");
                return;
            }

            String[] selectors = new String[]{"[aria-label='Playlists']", "[data-icon='playlists']", "a[href*='/playlists']", "[data-testid*='playlists']"};
            for (String sel : selectors) {
                Locator loc = page.locator(sel);
                if (safeClick(loc, "Playlists selector: " + sel)) {
                    logger.info("Successfully navigated to Playlists via selector: {}", sel);
                    return;
                }
            }

            // Fuzzy fallback: scan clickable elements for 'playlist' text
            try {
                var candidates = page.querySelectorAll("a, button, music-link, music-pill-item, [role='button'], [role='link']");
                for (var el : candidates) {
                    try {
                        String aria = el.getAttribute("aria-label");
                        String txt = el.innerText();
                        String combined = ((aria == null) ? "" : aria) + " " + ((txt == null) ? "" : txt);
                        if (combined.toLowerCase().contains("playlist")) {
                            el.scrollIntoViewIfNeeded();
                            el.click();
                            return;
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            logger.error("Failed to find Playlists tab by all selectors.");
            saveDebugArtifactsIfAllowed(page, "debug-playlists-tab", "music-pill-item");
        } catch (Exception e) {
            logger.error("Failed navigating library/playlists: {}", e.getMessage());
        }
    }

    /**
     * Checks if the user is signed in by looking for profile/account elements and absence of 'Sign in' button.
     */
    public boolean isSignedIn(Page page) {
        try {
            // Quick checks
            Locator signInBtn = null;
            try { signInBtn = page.getByText("Sign in", new Page.GetByTextOptions().setExact(false)); } catch (Exception ignored) {}
            if (signInBtn != null && signInBtn.count() > 0) return false;

            for (String sel : new String[]{"[aria-label='Account']", "[data-test-id='profile-menu']", "button:has([data-icon='profile'])", "img[alt*='profile']"}) {
                try { Locator loc = page.locator(sel); if (loc != null && loc.count() > 0) return true; } catch (Exception ignored) {}
            }

            // cookies/localStorage heuristics
            try {
                var cookies = page.context().cookies();
                for (var c : cookies) {
                    String cname = c.name == null ? "" : c.name.toLowerCase();
                    if (cname.contains("session") || cname.contains("ubid") || cname.contains("sso") || cname.contains("csrf")) return true;
                }
            } catch (Exception ignored) {}

            try {
                Object keysObj = page.evaluate("() => Object.keys(window.localStorage || {}).join('|')");
                if (keysObj != null) {
                    String keys = keysObj.toString().toLowerCase();
                    if (keys.contains("auth") || keys.contains("session") || keys.contains("token") || keys.contains("user")) return true;
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            logger.warn("Error checking sign-in status: {}", e.getMessage());
        }
        return false;
    }

    // Helper: save a focused debug HTML snippet and screenshot only when allowed; truncates large HTML
    private void saveDebugArtifactsIfAllowed(Page page, String baseName, String cssSelector) {
        if (!SAVE_DEBUG_ARTIFACTS) return;
        try {
            Files.createDirectories(Paths.get(DEBUG_ARTIFACT_DIR));
        } catch (IOException ignored) {}

        String timestamp = String.valueOf(System.currentTimeMillis());
        String safeBase = baseName.replaceAll("[^a-zA-Z0-9_.-]", "-");
        String combinedHtml = "";

        if (cssSelector != null && !cssSelector.trim().isEmpty()) {
            try {
                Object res = page.evaluate("(sel, max) => { const els = Array.from(document.querySelectorAll(sel)).slice(0, max); return els.map(e=>e.outerHTML).join('\n\n<!-- ELEMENT -->\n\n'); }", new Object[]{cssSelector, DEBUG_MAX_ELEMENTS});
                if (res != null) combinedHtml = res.toString();
            } catch (Exception ignored) {}
        }

        if (combinedHtml == null || combinedHtml.trim().isEmpty()) {
            try {
                combinedHtml = page.content();
            } catch (Exception ignored) { combinedHtml = ""; }
        }

        if (combinedHtml == null) combinedHtml = "";
        if (combinedHtml.length() > DEBUG_HTML_MAX_BYTES) combinedHtml = combinedHtml.substring(0, DEBUG_HTML_MAX_BYTES) + "\n<!-- TRUNCATED -->";

        if (!combinedHtml.trim().isEmpty()) {
            String htmlName = safeBase + "-" + timestamp + ".html";
            try { Files.writeString(Paths.get(DEBUG_ARTIFACT_DIR).resolve(htmlName), combinedHtml); } catch (IOException ignored) {}
        }

        String pngName = safeBase + "-" + timestamp + ".png";
        try {
            if (cssSelector != null && !cssSelector.trim().isEmpty()) {
                Locator loc = page.locator(cssSelector);
                if (loc != null && loc.count() > 0) {
                    loc.first().screenshot(new Locator.ScreenshotOptions().setPath(Paths.get(DEBUG_ARTIFACT_DIR).resolve(pngName)));
                    return;
                }
            }
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get(DEBUG_ARTIFACT_DIR).resolve(pngName)));
        } catch (Exception ignored) {}
    }

    private boolean waitForPlaylistContent(Page page) {
        String selectors = joinSelectors(SONG_SELECTOR_CANDIDATES);
        try {
            page.waitForFunction("(sel) => { try { const songElements = document.querySelectorAll(sel); const emptyMessage = document.querySelector('[data-testid=\\\"empty-playlist\\\"], .empty-state'); return songElements.length > 0 || emptyMessage !== null; } catch (e) { return false; } }", selectors, new Page.WaitForFunctionOptions().setTimeout((double) DEFAULT_PLAYLIST_WAIT_MS));
            return true;
        } catch (Exception ignored) {
            logger.warn("Timeout waiting for playlist content");
            return false;
        }
    }

    // --- SoundCloud lightweight lookup helpers ---
    private Map<String, String> fetchSoundCloudMetadata(String title, String artist) {
        Map<String, String> out = new HashMap<>();
        try {
            String q = title + (artist != null && !artist.isBlank() ? " " + artist : "");
            String enc = URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8.name());
            String urlStr = "https://api.soundcloud.com/tracks?client_id=" + SOUND_CLOUD_CLIENT_ID + "&q=" + enc + "&limit=1";
            URL u = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) return out;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            br.close();
            String body = sb.toString().trim();
            if (body.startsWith("[")) {
                // array - take first element
                int start = body.indexOf('{');
                int end = body.lastIndexOf('}');
                if (start >= 0 && end > start) body = body.substring(start, end + 1);
                else return out;
            }
            String artwork = getJsonString(body, "artwork_url");
            String permalink = getJsonString(body, "permalink_url");
            String genre = getJsonString(body, "genre");
            String durationMs = getJsonNumber(body, "duration");
            if (artwork != null && !artwork.isEmpty()) out.put("artwork_url", artwork);
            if (permalink != null && !permalink.isEmpty()) out.put("permalink_url", permalink);
            if (genre != null && !genre.isEmpty()) out.put("genre", genre);
            if (durationMs != null && !durationMs.isEmpty()) out.put("duration", msToDuration(durationMs));
            // SoundCloud doesn't provide album metadata reliably; leave album unset unless there's a tag
            return out;
        } catch (Exception e) {
            return out;
        }
    }

    private static String getJsonString(String body, String key) {
        try {
            Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
            Matcher m = p.matcher(body);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    private static String getJsonNumber(String body, String key) {
        try {
            Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(body);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    private static String msToDuration(String msStr) {
        try {
            long ms = Long.parseLong(msStr);
            long seconds = (ms + 500) / 1000;
            long mins = seconds / 60;
            long secs = seconds % 60;
            return String.format("%02d:%02d", mins, secs);
        } catch (Exception ignored) {}
        return "";
    }
}
