package com.amazonmusic.scraper;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Service for scraping Amazon Music playlists and songs using Playwright.
 */
public class ScraperService implements ScraperServiceInterface {
    private static final Logger logger = LoggerFactory.getLogger(ScraperService.class);
    private final AuthServiceInterface authService;

    public ScraperService(AuthServiceInterface authService) {
        this.authService = authService == null ? new AuthService() : authService;
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
    private static final boolean ALLOW_FULL_PAGE_SAVE = Boolean.parseBoolean(envOrProp("SCRAPER_SAVE_DEBUG_ALLOW_FULLPAGE", "false"));
    private static final int DEFAULT_PLAYLIST_WAIT_MS = 5000;

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

    // --- Helper utilities to reduce repetitive try/catch ---
    private String safeInnerText(Locator l) {
        try {
            if (l != null && l.count() > 0) {
                String s = l.first().innerText();
                return s == null ? "" : s.trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String safeAttr(Locator l, String attr) {
        try {
            if (l != null && l.count() > 0) {
                String s = l.first().getAttribute(attr);
                return s == null ? "" : s.trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private Locator findFirstMatchingLocator(Page page, String[] selectors) {
        for (String sel : selectors) {
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
        try { page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(3000)); } catch (Exception ignored) {}

        Locator songLocator = findFirstMatchingLocator(page, SONG_SELECTOR_CANDIDATES);
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

            // Try common structured selectors first
            title = safeInnerText(songEl.locator(".title, .track-title, .song-title, [data-test='track-title'], h3, .trackName, .track-name, .trackTitle, a[data-test='title']"));
            if (title.isEmpty()) title = safeInnerText(songEl.locator("a"));

            artist = safeInnerText(songEl.locator("music-link a, .artist, .track-artist, [data-test='artist'], a[href*='/artists']"));
            if (artist.isEmpty()) {
                String text = safeInnerText(songEl);
                if (!text.isEmpty()) {
                    String[] lines = text.split("\\r?\\n");
                    if (lines.length >= 2 && !lines[1].matches(".*\\d{1,2}:\\d{2}.*")) {
                        artist = lines[1].trim();
                    }
                }
            }

            album = safeInnerText(songEl.locator(".album, [data-test='album'], .track-album, a[href*='/albums']"));

            // URL
            url = safeAttr(songEl.locator("a[href]"), "href");
            if (url.startsWith("/")) url = "https://music.amazon.com.au" + url;

            // Duration: look for time pattern
            try {
                String txt = safeInnerText(songEl);
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\b(\\d{1,2}:\\d{2})\\b");
                java.util.regex.Matcher m = p.matcher(txt);
                String last = "";
                while (m.find()) last = m.group(1);
                if (!last.isEmpty()) duration = last;
            } catch (Exception ignored) {}

            // Track number heuristics
            try {
                String numTxt = safeInnerText(songEl.locator(".track-number, .position, .index, .num"));
                if (!numTxt.isEmpty()) {
                    numTxt = numTxt.replaceAll("\\D", "");
                    if (!numTxt.isEmpty()) trackNumber = Integer.parseInt(numTxt);
                } else {
                    String txt = safeInnerText(songEl);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d{1,4})\\b").matcher(txt);
                    if (m.find()) trackNumber = Integer.parseInt(m.group(1));
                }
            } catch (Exception ignored) {}

            try { imageUrl = safeAttr(songEl.locator("img"), "src"); } catch (Exception ignored) {}

            if (title.isEmpty()) {
                logger.warn("Skipping song with empty title at position {} in playlist {}", i + 1, playlistName);
                continue;
            }

            songs.add(new Song(
                title,
                artist == null ? "" : artist,
                album == null ? "" : album,
                url == null ? "" : url,
                duration == null ? "" : duration,
                trackNumber,
                i + 1,
                null,
                imageUrl == null ? "" : imageUrl,
                "",
                ""
            ));
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

        try { page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000)); } catch (Exception ignored) {}

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
                if (!url.startsWith("http")) url = "https://music.amazon.com.au" + url;
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
            try {
                Locator libraryButton = page.getByText("Library", new Page.GetByTextOptions().setExact(false));
                if (libraryButton != null && libraryButton.count() > 0) {
                    libraryButton.first().scrollIntoViewIfNeeded();
                    libraryButton.first().click();
                    page.waitForTimeout(800);
                }
            } catch (Exception ignored) {}

            // Try Playlists tab by text then selectors
            try {
                Locator playlistsTab = page.getByText("Playlists", new Page.GetByTextOptions().setExact(false));
                if (playlistsTab != null && playlistsTab.count() > 0) {
                    playlistsTab.first().scrollIntoViewIfNeeded();
                    playlistsTab.first().click();
                    return;
                }
            } catch (Exception ignored) {}

            String[] selectors = new String[]{"[aria-label='Playlists']", "[data-icon='playlists']", "a[href*='/playlists']", "[data-testid*='playlists']"};
            for (String sel : selectors) {
                try {
                    Locator loc = page.locator(sel);
                    if (loc != null && loc.count() > 0) {
                        loc.first().scrollIntoViewIfNeeded();
                        loc.first().click();
                        return;
                    }
                } catch (Exception ignored) {}
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
        long deadline = System.currentTimeMillis() + DEFAULT_PLAYLIST_WAIT_MS;
        String selectors = joinSelectors(SONG_SELECTOR_CANDIDATES);
        while (System.currentTimeMillis() < deadline) {
            try {
                Object res = page.evaluate("(sel) => { try { const songElements = document.querySelectorAll(sel); const emptyMessage = document.querySelector('[data-testid=\\"empty-playlist\\"], .empty-state'); return songElements.length > 0 || emptyMessage !== null; } catch (e) { return false; } }", selectors);
                if (res instanceof Boolean && (Boolean) res) return true;
            } catch (Exception ignored) {}
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        logger.warn("Timeout waiting for playlist content");
        return false;
    }
}
