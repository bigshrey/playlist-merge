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
 * Handles navigation, robust element selection, and retry logic.
 */
public class ScraperService implements ScraperServiceInterface {
    private static final Logger logger = LoggerFactory.getLogger(ScraperService.class);
    private final AuthServiceInterface authService;

    /**
     * Construct ScraperService with provided AuthServiceInterface (dependency injection friendly).
     */
    public ScraperService(AuthServiceInterface authService) {
        this.authService = authService == null ? new AuthService() : authService;
    }

    /**
     * Default constructor maintains backward compatibility and creates its own AuthService.
     */
    public ScraperService() {
        this(new AuthService());
    }

    // Add helper methods to read from env OR system properties, and update debug constants to use them
    // New: control whether debug artifacts are written by environment variable SCRAPER_SAVE_DEBUG (true/false)
    private static String envOrProp(String key, String defaultVal) {
        try {
            String ev = System.getenv(key);
            if (ev != null) return ev;
        } catch (Exception ignored) {}
        String prop = System.getProperty(key);
        return prop != null ? prop : defaultVal;
    }

    private static boolean envOrPropBool(String key, boolean defaultVal) {
        String v = envOrProp(key, Boolean.toString(defaultVal));
        return Boolean.parseBoolean(v);
    }

    private static final boolean SAVE_DEBUG_ARTIFACTS = envOrPropBool("SCRAPER_SAVE_DEBUG", false);
    private static final int DEBUG_HTML_MAX_BYTES = 200_000; // truncate large pages
    // Conservative defaults for focused debug captures
    private static final String DEBUG_ARTIFACT_DIR = envOrProp("SCRAPER_DEBUG_DIR", "scraped-debug");
    private static final int DEBUG_MAX_ELEMENTS = 8; // max elements to capture for a selector
    private static final boolean ALLOW_FULL_PAGE_SAVE = envOrPropBool("SCRAPER_SAVE_DEBUG_ALLOW_FULLPAGE", false);

    /**
     * Scrapes a playlist and its songs from the given URL.
     * @param page Playwright page instance
     * @param playlistUrl URL of the playlist
     * @return Playlist record with name and songs
     */
    public Playlist scrapePlaylist(Page page, String playlistUrl) {
        logger.info("Scraping playlist: {}", playlistUrl);
        // Ensure auth UI has settled before attempting playlist navigation
        try {
            authService.waitForAuthUi(page);
        } catch (Exception ignored) {}
        Utils.retryPlaywrightAction(() -> {
            page.navigate(playlistUrl);
            return true;
        }, 3, "navigate to playlist");

        page.setViewportSize(1920, 1080);

        // Wait for song elements using semantic selectors first, then fallback
        Utils.retryPlaywrightAction(() -> {
            String[] candidates = new String[]{
                "role=row",
                ".song-row",
                ".trackList__item",
                ".tracklist__item",
                ".track-list__item",
                ".track-row",
                ".track",
                ".trackListRow",
                ".trackList__row",
                ".trackListItem",
                ".track-list__row",
                ".tracklist__row",
                "music-track",
                "[data-test='song-row']",
                "tr[data-test='song-row']",
                "div.tracklist-row",
                "div.track-list-row"
            };
            boolean found = false;
            for (String sel : candidates) {
                try {
                    if (sel.equals("role=row")) {
                        try {
                            Locator r = page.getByRole(com.microsoft.playwright.options.AriaRole.ROW);
                            if (r != null && r.count() > 0) { found = true; break; }
                        } catch (Exception ignored) {}
                    } else {
                        try {
                            page.waitForSelector(sel, new Page.WaitForSelectorOptions().setTimeout(3000));
                            Locator loc = page.locator(sel);
                            if (loc != null && loc.count() > 0) { found = true; break; }
                        } catch (Exception ignored) {
                            // no match for this selector, try next
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (!found) throw new RuntimeException("No song element selectors matched");
            return true;
        }, 3, "wait for playlist content");

        // Scroll to load all entries
        Utils.retryPlaywrightAction(() -> {
            page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
            page.waitForTimeout(2000);
            return true;
        }, 3, "scroll to load entries");

        int expectedSongCount = 0;
        try {
            Locator countLocator = page.getByText("songs", new Page.GetByTextOptions().setExact(false));
            if (countLocator.count() > 0) {
                String countText = countLocator.first().innerText().replaceAll("\\D", "");
                expectedSongCount = Integer.parseInt(countText);
                logger.info("Expected song count: {}", expectedSongCount);
            }
        } catch (Exception e) {
            logger.warn("Could not determine expected song count: {}", e.getMessage());
        }

        String playlistName = page.title();
        var songs = new ArrayList<Song>();

        // Robust scrolling to load all songs
        Utils.retryPlaywrightAction(() -> {
            boolean hasMoreContent = true;
            while (hasMoreContent) {
                page.evaluate("window.scrollBy(0, window.innerHeight)");
                page.waitForTimeout(1000);
                hasMoreContent = (Boolean) page.evaluate("document.body.scrollHeight > window.scrollY + window.innerHeight");
            }
            return true;
        }, 3, "scroll to load all songs dynamically");

        // Wait for network idle
        Utils.retryPlaywrightAction(() -> {
            page.waitForLoadState(LoadState.NETWORKIDLE);
            return true;
        }, 3, "wait for network idle state");

        // Use Locator API for song elements
        Locator songLocator = null;
        // Try semantic role first
        try {
            Locator r = page.getByRole(com.microsoft.playwright.options.AriaRole.ROW);
            if (r != null && r.count() > 0) songLocator = r;
        } catch (Exception ignored) {}
        // If not, probe several CSS selectors and pick the first that actually matches elements
        if (songLocator == null) {
            String[] selectors = new String[]{
                ".song-row",
                ".trackList__item",
                ".tracklist__item",
                ".track-list__item",
                ".track-row",
                ".track",
                ".trackListRow",
                ".trackList__row",
                ".trackListItem",
                ".track-list__row",
                ".tracklist__row",
                "music-track",
                "[data-test='song-row']",
                "tr[data-test='song-row']",
                "div.tracklist-row",
                "div.track-list-row"
            };
            for (String s : selectors) {
                try {
                    Locator loc = page.locator(s);
                    if (loc != null && loc.count() > 0) { songLocator = loc; break; }
                } catch (Exception ignored) {}
            }
        }
        if (songLocator == null || songLocator.count() == 0) {
            logger.warn("No songs found in playlist: {}", playlistName);
        } else {
            logger.info("Found {} song elements. Beginning detailed scraping.", songLocator.count());
            for (int i = 0; i < songLocator.count(); i++) {
                Locator songEl = songLocator.nth(i);
                String title = "";
                String artist = "";
                String album = "";
                String url = "";
                String duration = "";
                Integer trackNumber = null;
                Integer playlistPosition = i + 1;
                Boolean explicit = null;
                String imageUrl = "";
                String releaseDate = "";
                String genre = "";
                try {
                    // Title: try several likely title selectors, then fall back to the first descriptive anchor
                    String[] titleSelectors = new String[]{".title", ".track-title", ".song-title", "[data-test='track-title']", "h3", ".trackName", ".track-name", ".trackTitle", "a[data-test='title']"};
                    for (String s : titleSelectors) {
                        try {
                            Locator l = songEl.locator(s);
                            if (l != null && l.count() > 0) { title = l.first().innerText(); break; }
                        } catch (Exception ignored) {}
                    }
                    if ((title == null || title.trim().isEmpty())) {
                        try {
                            Locator anchors = songEl.locator("a");
                            for (int ai = 0; ai < anchors.count(); ai++) {
                                try {
                                    Locator a = anchors.nth(ai);
                                    String ahref = a.getAttribute("href");
                                    String atext = a.innerText();
                                    if (atext != null && !atext.trim().isEmpty() && (ahref != null && (ahref.contains("/tracks") || ahref.contains("/songs") || ahref.contains("/album") || ahref.contains("/playlists") || ahref.contains("/artists")))) {
                                        title = atext.trim();
                                        url = ahref;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                            // final fallback: take first anchor text
                            if ((title == null || title.trim().isEmpty()) && anchors.count() > 0) {
                                try { title = anchors.first().innerText(); if (title == null) title = ""; } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}

                try {
                    // Artist: handle <music-link><a>Artist</a></music-link> and common artist selectors
                    String[] artistSelectors = new String[]{"music-link a", "music-link > a", ".artist", ".track-artist", "[data-test='artist']", "a[href*='/artists']"};
                    for (String s : artistSelectors) {
                        try {
                            Locator l = songEl.locator(s);
                            if (l != null && l.count() > 0) { artist = l.first().innerText(); break; }
                        } catch (Exception ignored) {}
                    }
                    // If still empty, try to pick a small text node under the row (heuristic)
                    if ((artist == null || artist.trim().isEmpty())) {
                        try {
                            String txt = songEl.innerText();
                            if (txt != null) {
                                // try to extract artist by heuristics: often appears on the second line under title
                                String[] lines = txt.split("\\r?\\n");
                                if (lines.length >= 2) {
                                    String cand = lines[1].trim();
                                    // ignore time strings
                                    if (!cand.matches(".*\\d{1,2}:\\d{2}.*")) {
                                        artist = cand;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}

                try {
                    // Album: common selectors
                    String[] albumSelectors = new String[]{".album", "[data-test='album']", ".track-album", "a[href*='/albums']"};
                    for (String s : albumSelectors) {
                        try {
                            Locator l = songEl.locator(s);
                            if (l != null && l.count() > 0) { album = l.first().innerText(); break; }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}

                try {
                    // URL: prefer a track/album link if not already set
                    if (url == null || url.isEmpty()) {
                        try {
                            Locator anchors = songEl.locator("a");
                            for (int ai = 0; ai < anchors.count(); ai++) {
                                try {
                                    Locator a = anchors.nth(ai);
                                    String ahref = a.getAttribute("href");
                                    if (ahref != null && (ahref.contains("/tracks") || ahref.contains("/albums") || ahref.contains("/playlist") || ahref.contains("/artists") || ahref.contains("/playlists") )) {
                                        url = ahref;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}

                try {
                    // Duration: find any time-like token in the row text (last match is likely the length)
                    try {
                        String txt = songEl.innerText();
                        if (txt != null) {
                            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\b(\\d{1,2}:\\d{2})\\b");
                            java.util.regex.Matcher m = p.matcher(txt);
                            String last = "";
                            while (m.find()) last = m.group(1);
                            if (!last.isEmpty()) duration = last;
                        }
                    } catch (Exception ignored) {}
                } catch (Exception ignored) {}

                try {
                    // Track number/position detection: look for obvious elements or parse leading number
                    try {
                        Locator num = songEl.locator(".track-number, .position, .index, .num");
                        if (num != null && num.count() > 0) {
                            try {
                                String numTxt = num.first().innerText().replaceAll("\\D", "");
                                if (!numTxt.isEmpty()) trackNumber = Integer.parseInt(numTxt);
                            } catch (Exception ignored) {}
                        } else {
                            // fallback: parse the very start of the row text for a leading number
                            String txt = songEl.innerText();
                            if (txt != null) {
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d{1,4})\\b").matcher(txt);
                                if (m.find()) trackNumber = Integer.parseInt(m.group(1));
                            }
                        }
                    } catch (Exception ignored) {}
                } catch (Exception ignored) {}

                try {
                    Locator imgEl = songEl.locator("img");
                    if (imgEl.count() > 0) imageUrl = imgEl.first().getAttribute("src");
                } catch (Exception ignored) {}

                try {
                    Locator dateEl = songEl.getByText("release", new Locator.GetByTextOptions().setExact(false));
                    if (dateEl.count() > 0) releaseDate = dateEl.first().innerText();
                } catch (Exception ignored) {}
                try {
                    Locator genreEl = songEl.getByText("genre", new Locator.GetByTextOptions().setExact(false));
                    if (genreEl.count() > 0) genre = genreEl.first().innerText();
                } catch (Exception ignored) {}
                if (title == null || title.trim().isEmpty()) {
                    logger.warn("Skipping song with empty title in playlist: {}", playlistName);
                    continue;
                }
                songs.add(new Song(
                    title.trim(),
                    artist == null ? "" : artist.trim(),
                    album == null ? "" : album.trim(),
                    url == null ? "" : url.trim(),
                    duration == null ? "" : duration.trim(),
                    trackNumber,
                    playlistPosition,
                    explicit,
                    imageUrl == null ? "" : imageUrl.trim(),
                    releaseDate == null ? "" : releaseDate.trim(),
                    genre == null ? "" : genre.trim()
                ));
            }
        }

        // Save debug artifacts if scraping fails
        if (songs.isEmpty()) {
            logger.warn("No songs were scraped. Please check the page structure or network conditions.");
            // Use guarded saver which respects SCRAPER_SAVE_DEBUG and truncates large HTML
            saveDebugArtifactsIfAllowed(page, "debug-scrape-fail", ".trackList__item, .song-row, [data-test='song-row'], .track-row, .track, music-track, .tracklist__row");
        }
        if (songs.size() < expectedSongCount * 0.9) {
            logger.warn("Scraped song count ({}) is significantly lower than expected ({})", songs.size(), expectedSongCount);
            logger.info("Retrying scraping process to ensure data integrity.");
            scrapePlaylist(page, playlistUrl);
        } else {
            logger.info("Successfully scraped {} songs, matching expected count.", songs.size());
        }
        return new Playlist(playlistName, songs);
    }

    /**
     * Scrapes playlist links from the user's library page.
     * @param page Playwright page instance
     * @return List of playlist metadata maps (name, url)
     */
    public List<Map<String, String>> scrapePlaylistLinks(Page page) {
        logger.info("Scraping playlist links...");
        // Ensure auth state resolved before scraping links
        try {
            authService.waitForAuthUi(page);
        } catch (Exception ignored) {}
        var playlists = new ArrayList<Map<String, String>>();

        // Wait for the page to settle (network idle) before querying
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000));
        } catch (Exception ignored) {}

        // Helper to collect items from a container element using many common playlist tile selectors
        java.util.function.Function<ElementHandle, List<ElementHandle>> collectFromContainer = (container) -> {
            var out = new ArrayList<ElementHandle>();
            try {
                // First: prefer platform component tiles that explicitly expose primary-href/primary-text (observed in page HTML)
                try {
                    var primaryTiles = container.querySelectorAll(
                        "music-vertical-item[primary-href], music-vertical-item[label='Playlist'], music-vertical-item[data-key][primary-href], [primary-href], [primary-text]"
                    );
                    if (primaryTiles != null && !primaryTiles.isEmpty()) {
                        out.addAll(primaryTiles);
                        return out;
                    }
                } catch (Exception ignored) {}

                // Next: try precise anchor matches inside the container (prefer updated, specific logic)
                try {
                    var anchors = container.querySelectorAll("a");
                    for (var a : anchors) {
                        try {
                            String href = a.getAttribute("href");
                            String txt = a.innerText();
                            if (href != null && txt != null && !txt.trim().isEmpty()) {
                                String lowerHref = href.toLowerCase();
                                // Prefer anchors that explicitly look like playlist links
                                if (lowerHref.contains("/playlist") || lowerHref.contains("/playlists") || lowerHref.contains("my/playlists") || lowerHref.contains("playlist")) {
                                    out.add(a);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    if (!out.isEmpty()) {
                        return out;
                    }
                } catch (Exception ignored) {}

                // Next: try several likely tile selectors within the container, ordered by specificity
                String[] tileSelectors = new String[]{
                    "[data-test='playlist']",
                    "[data-testid='playlist']",
                    "a[href*='/playlist']",
                    "a[href*='/my/playlists']",
                    "music-vertical-item",
                    ".music-image-row",
                    "music-image-row",
                    "music-horizontal-item",
                    "[data-key]"
                };
                for (String s : tileSelectors) {
                    try {
                        var found = container.querySelectorAll(s);
                        if (found != null && !found.isEmpty()) {
                            out.addAll(found);
                        }
                    } catch (Exception ignored) {}
                }

                // If still none found, try any anchor tiles inside as a last resort (broader 'play' heuristic)
                if (out.isEmpty()) {
                    try {
                        var anchors = container.querySelectorAll("a");
                        for (var a : anchors) {
                            try {
                                String href = a.getAttribute("href");
                                String txt = a.innerText();
                                if (href != null && txt != null && !txt.trim().isEmpty() && href.toLowerCase().contains("play")) {
                                    out.add(a);
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            return out;
        };

        // Track URLs to dedupe
        var seen = new HashSet<String>();

        try {
            // Strategy A: find sections headed by a title that contains 'Playlists' (case-insensitive)
            var headings = page.querySelectorAll("h1, h2, h3, h4, h5, .title, .headline-3, .headline-4, div, span");
            for (var h : headings) {
                try {
                    String txt = h.innerText();
                    if (txt != null && txt.trim().toLowerCase().contains("playlists")) {
                        logger.info("Found Playlists heading element with text: {}", txt.trim());
                        // Determine a reasonable container for tiles: look for nearest section/parent with many children
                        ElementHandle section = null;
                        try { section = (ElementHandle) h.evaluateHandle("el=>el.closest('section')"); } catch (Exception ignored) {}
                        if (section == null) {
                            try { section = (ElementHandle) h.evaluateHandle("el=>el.parentElement"); } catch (Exception ignored) {}
                        }
                        if (section != null) {
                            // Try clicking a local 'See all' / 'Show all' control inside this section
                            try {
                                String[] seeAllTexts = new String[]{"See all", "SEE ALL", "Show all", "View all", "See more", "Show more"};
                                boolean clicked = false;
                                for (String s : seeAllTexts) {
                                    try {
                                        // Use ElementHandle query to find buttons/links inside the section and match text case-insensitively
                                        var controls = section.querySelectorAll("button, a, [role='button'], [role='link']");
                                        for (var c : controls) {
                                            try {
                                                String ctext = c.innerText();
                                                if (ctext != null && !ctext.trim().isEmpty() && ctext.trim().equalsIgnoreCase(s)) {
                                                    c.scrollIntoViewIfNeeded();
                                                    c.click();
                                                    logger.info("Clicked local '{}' control to reveal playlists.", s);
                                                    page.waitForTimeout(800);
                                                    clicked = true;
                                                    break;
                                                }
                                            } catch (Exception ignored) {}
                                        }
                                        if (clicked) break;
                                    } catch (Exception ignored) {}
                                }
                                // If clicked, try to collect from the revealed area (retrying)
                                var items = new ArrayList<ElementHandle>();
                                if (clicked) {
                                    try {
                                        items.addAll(collectFromContainer.apply(section));
                                    } catch (Exception ignored) {}
                                }

                                // If not clicked or no items, attempt to collect from any scrollable/carousel inside the section
                                if (items.isEmpty()) {
                                    try {
                                        var carousels = section.querySelectorAll("[role='list'], .shoveler, .music-shoveler, .carousel, .shimmer, .infinite-scroller, .overflow-x, .horizontal-scroll, .music-image-row");
                                        for (var c : carousels) {
                                            try {
                                                var found = collectFromContainer.apply(c);
                                                if (!found.isEmpty()) items.addAll(found);
                                                // Try clicking next arrow inside carousel to expose more items
                                                try {
                                                    var nexts = c.querySelectorAll("button[aria-label*='Next'], button:has-text('>'), button:has-text('›'), button:has-text('→')");
                                                    for (var n : nexts) {
                                                        try { n.click(); page.waitForTimeout(300); } catch (Exception ignored) {}
                                                    }
                                                    // re-collect
                                                    var found2 = collectFromContainer.apply(c);
                                                    if (!found2.isEmpty()) items.addAll(found2);
                                                } catch (Exception ignored) {}
                                            } catch (Exception ignored) {}
                                        }
                                    } catch (Exception ignored) {}
                                }

                                // Add items to result
                                for (var it : items) {
                                    try {
                                        String name = null;
                                        String url = null;
                                        try { name = it.getAttribute("primary-text"); } catch (Exception ignored) {}
                                        try { url = it.getAttribute("primary-href"); } catch (Exception ignored) {}
                                        if ((name == null || name.isEmpty())) {
                                            try { name = it.innerText(); } catch (Exception ignored) {}
                                        }
                                        if ((url == null || url.isEmpty())) {
                                            try {
                                                var a = it.querySelector("a, music-link, [role='link']");
                                                if (a != null) url = a.getAttribute("href");
                                            } catch (Exception ignored) {}
                                        }
                                        if (url != null && !url.isEmpty()) {
                                            String lowerUrl = url.toLowerCase();
                                            // Normalize
                                            if (!url.startsWith("http")) url = "https://music.amazon.com.au" + url;
                                            // Heuristic: accept only playlist-like URLs, exclude albums/stations
                                            boolean looksLikePlaylist = lowerUrl.contains("/playlist") || lowerUrl.contains("/playlists") || lowerUrl.contains("/my/playlists");
                                            boolean isAlbumOrStation = lowerUrl.contains("/albums") || lowerUrl.contains("/stations");
                                            if (looksLikePlaylist && !isAlbumOrStation && !seen.contains(url)) {
                                                if (name == null || name.trim().isEmpty()) name = "(unknown)";
                                                var m = new HashMap<String, String>();
                                                m.put("name", name.trim());
                                                m.put("url", url);
                                                playlists.add(m);
                                                seen.add(url);
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }

                            } catch (Exception e) {
                                logger.debug("Error processing Playlists section: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Strategy B: if we didn't find enough via sections, try global shoveler / show-all buttons at page-level
            if (playlists.isEmpty()) {
                try {
                    // Click global 'See all' buttons and collect
                    Locator globalSeeAll = page.locator("button:has-text('See all'), a:has-text('See all'), button:has-text('SEE ALL'), a:has-text('SEE ALL')");
                    if (globalSeeAll != null && globalSeeAll.count() > 0) {
                        for (int i = 0; i < globalSeeAll.count(); i++) {
                            try {
                                var btn = globalSeeAll.nth(i);
                                btn.scrollIntoViewIfNeeded();
                                btn.click();
                                logger.info("Clicked global 'See all' button index {}", i);
                                page.waitForTimeout(1000);
                                // After clicking, search for playlist tiles globally
                                var found = page.querySelectorAll("music-vertical-item, music-image-row, .music-image-row, .music-vertical-item, [data-test='playlist'], a[href*='/playlist']");
                                if (found != null && !found.isEmpty()) {
                                    for (var it : found) {
                                        try {
                                            String name = null;
                                            String url = null;
                                            try { name = it.getAttribute("primary-text"); } catch (Exception ignored) {}
                                            try { url = it.getAttribute("primary-href"); } catch (Exception ignored) {}
                                            if ((name == null || name.isEmpty())) {
                                                try { name = it.innerText(); } catch (Exception ignored) {}
                                            }
                                            if ((url == null || url.isEmpty())) {
                                                try {
                                                    var a = it.querySelector("a, music-link, [role='link']");
                                                    if (a != null) url = a.getAttribute("href");
                                                } catch (Exception ignored) {}
                                            }
                                            if (url != null && !url.isEmpty()) {
                                                String lowerUrl = url.toLowerCase();
                                                // Normalize
                                                if (!url.startsWith("http")) url = "https://music.amazon.com.au" + url;
                                                // Heuristic: accept only playlist-like URLs, exclude albums/stations
                                                boolean looksLikePlaylist = lowerUrl.contains("/playlist") || lowerUrl.contains("/playlists") || lowerUrl.contains("/my/playlists");
                                                boolean isAlbumOrStation = lowerUrl.contains("/albums") || lowerUrl.contains("/stations");
                                                if (looksLikePlaylist && !isAlbumOrStation && !seen.contains(url)) {
                                                    if (name == null || name.trim().isEmpty()) name = "(unknown)";
                                                    var m = new HashMap<String, String>();
                                                    m.put("name", name.trim());
                                                    m.put("url", url);
                                                    playlists.add(m);
                                                    seen.add(url);
                                                }
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Global See all logic failed: {}", e.getMessage());
                }
            }

            // Strategy C: final fallback - broad selectors and heuristics
            if (playlists.isEmpty()) {
                try {
                    var items = page.querySelectorAll("music-vertical-item, music-image-row, .music-image-row, .music-vertical-item, [data-test='playlist'], a[href*='/playlist']");
                    if (items != null && !items.isEmpty()) {
                        for (var it : items) {
                            try {
                                String name = null;
                                String url = null;
                                try { name = it.getAttribute("primary-text"); } catch (Exception ignored) {}
                                try { url = it.getAttribute("primary-href"); } catch (Exception ignored) {}
                                if ((name == null || name.isEmpty())) {
                                    try { name = it.innerText(); } catch (Exception ignored) {}
                                }
                                if ((url == null || url.isEmpty())) {
                                    try {
                                        var a = it.querySelector("a, music-link, [role='link']");
                                        if (a != null) url = a.getAttribute("href");
                                    } catch (Exception ignored) {}
                                }
                                if (url != null && !url.isEmpty()) {
                                    String lowerUrl = url.toLowerCase();
                                    // Normalize
                                    if (!url.startsWith("http")) url = "https://music.amazon.com.au" + url;
                                    // Heuristic: accept only playlist-like URLs, exclude albums/stations
                                    boolean looksLikePlaylist = lowerUrl.contains("/playlist") || lowerUrl.contains("/playlists") || lowerUrl.contains("/my/playlists");
                                    boolean isAlbumOrStation = lowerUrl.contains("/albums") || lowerUrl.contains("/stations");
                                    if (looksLikePlaylist && !isAlbumOrStation && !seen.contains(url)) {
                                        if (name == null || name.trim().isEmpty()) name = "(unknown)";
                                        var m = new HashMap<String, String>();
                                        m.put("name", name.trim());
                                        m.put("url", url);
                                        playlists.add(m);
                                        seen.add(url);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Fallback playlist extraction failed: {}", e.getMessage());
                }
            }

            logger.info("Found {} playlists.", playlists.size());

            if (playlists.isEmpty()) {
                logger.error("No playlist items found after all heuristics.");
                // Save a focused snippet (playlist tiles) when debug is enabled
                saveDebugArtifactsIfAllowed(page, "debug-playlist-links", "music-vertical-item, music-image-row, [data-test='playlist']");
            }

        } catch (Exception e) {
            logger.error("Unexpected error scraping playlist links: {}", e.getMessage());
        }

        return playlists;
    }

    /**
     * Navigates to the Library and Playlists tabs using robust selectors.
     * @param page Playwright page instance
     */
    public void goToLibraryPlaylists(Page page) {
        logger.info("Navigating to Library...");
        // Ensure auth state is present before navigation
        try {
            authService.waitForAuthUi(page);
        } catch (Exception ignored) {}
        try {
            // Try robust selectors for Library tab
            Locator libraryButton = null;
            try {
                libraryButton = page.getByText("Library", new Page.GetByTextOptions().setExact(false));
                if (libraryButton.count() > 0) {
                    libraryButton.first().scrollIntoViewIfNeeded();
                    libraryButton.first().click();
                    logger.info("Clicked Library button via text selector.");
                } else {
                    libraryButton = null;
                }
            } catch (Exception ignored) {}
            if (libraryButton == null) {
                // Fallback selectors
                String[] selectors = new String[]{"[aria-label='Library']", "[data-icon='library']", "a[href*='/library']", "svg[role='img']"};
                for (String sel : selectors) {
                    Locator loc = page.locator(sel);
                    if (loc.count() > 0) {
                        loc.first().scrollIntoViewIfNeeded();
                        loc.first().click();
                        logger.info("Clicked Library button via selector: {}", sel);
                        libraryButton = loc.first();
                        break;
                    }
                }
            }
            if (libraryButton == null) {
                logger.error("Failed to find Library button by all selectors.");
                return;
            }
            // Give the Library view time to render its dynamic sub-nav/pills
            try {
                page.waitForSelector("music-pill-item, .nav-container, .huwFWuQ9IP0DpE5LSh_S", new Page.WaitForSelectorOptions().setTimeout(5000));
                page.waitForTimeout(1000);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            logger.error("Failed to navigate to Library: {}", e.getMessage());
            return;
        }

        logger.info("Navigating to Playlists tab...");
        try {
            Locator playlistsTab = null;
            try {
                playlistsTab = page.getByText("Playlists", new Page.GetByTextOptions().setExact(false));
                if (playlistsTab.count() > 0) {
                    playlistsTab.first().scrollIntoViewIfNeeded();
                    playlistsTab.first().click();
                    logger.info("Clicked Playlists tab via text selector.");
                    return;
                } else {
                    playlistsTab = null;
                }
            } catch (Exception ignored) {}
            if (playlistsTab == null) {
                // Try more robust selectors
                String[] selectors = new String[]{
                    "[aria-label='Playlists']", "[data-icon='playlists']", "a[href*='/playlists']", "svg[role='img']",
                    "[aria-label*='My Playlists']", "[aria-label*='Your Playlists']", "[data-testid*='playlists']"
                };
                for (String sel : selectors) {
                    Locator loc = page.locator(sel);
                    if (loc.count() > 0) {
                        loc.first().scrollIntoViewIfNeeded();
                        loc.first().click();
                        logger.info("Clicked Playlists tab via selector: {}", sel);
                        playlistsTab = loc.first();
                        break;
                    }
                }
            }
            if (playlistsTab == null) {
                // Fallback: perform a fuzzy scan of common clickable elements for any label/text containing "play" or "playlist"
                try {
                    logger.info("Playlists tab not found by specific selectors — trying fuzzy scan of clickable elements...");
                    var candidates = page.querySelectorAll("a, button, li, music-link, music-pill-item, [role='button'], [role='link']");
                    int candidateIndex = 0;
                    for (var el : candidates) {
                        try {
                            String aria = el.getAttribute("aria-label");
                            String txt = el.innerText();
                            String combined = (aria == null ? "" : aria) + " " + (txt == null ? "" : txt);
                            if (combined.toLowerCase().contains("playlist") || combined.toLowerCase().contains("playlists") || combined.toLowerCase().contains("play list")) {
                                el.scrollIntoViewIfNeeded();
                                el.click();
                                logger.info("Clicked Playlists tab via fuzzy match on element {}: {}", candidateIndex, combined.trim());
                                return;
                            }
                        } catch (Exception ignored) {
                            // ignore per-element errors
                        }
                        candidateIndex++;
                    }
                } catch (Exception e) {
                    logger.debug("Fuzzy scan for Playlists tab failed: {}", e.getMessage());
                }

                // NEW: additional robust attempts (case-insensitive attribute/text matching using XPath and role-based tab lookup)
                try {
                    // Try role=tab first (semantic)
                    try {
                        Locator tabRole = page.getByRole(com.microsoft.playwright.options.AriaRole.TAB, new Page.GetByRoleOptions().setName("Playlists").setExact(false));
                        if (tabRole != null && tabRole.count() > 0) {
                            tabRole.first().scrollIntoViewIfNeeded();
                            tabRole.first().click();
                            logger.info("Clicked Playlists tab via role=tab semantic selector.");
                            return;
                        }
                    } catch (Exception ignored) {}

                    // Use XPath to do case-insensitive search for 'playlist' in aria-label or visible text
                    String xpath = "//*[contains(translate(@aria-label,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'playlist') or contains(translate(normalize-space(string(.)),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'playlist')]";
                    Locator xpathLoc = page.locator("xpath=" + xpath);
                    if (xpathLoc != null && xpathLoc.count() > 0) {
                        xpathLoc.first().scrollIntoViewIfNeeded();
                        xpathLoc.first().click();
                        logger.info("Clicked Playlists tab via case-insensitive XPath match.");
                        return;
                    }

                    // Try pill/tab-like components specifically
                    Locator pills = page.locator("music-pill-item, .nav-container music-link, .nav-container music-link, .huwFWuQ9IP0DpE5LSh_S li");
                    if (pills != null && pills.count() > 0) {
                        for (int i = 0; i < pills.count(); i++) {
                            try {
                                Locator p = pills.nth(i);
                                String aria = p.getAttribute("aria-label");
                                String txt = p.innerText();
                                String combined = (aria == null ? "" : aria) + " " + (txt == null ? "" : txt);
                                if (combined.toLowerCase().contains("playlist")) {
                                    p.scrollIntoViewIfNeeded();
                                    p.click();
                                    logger.info("Clicked Playlists pill element index {} via combined text: {}", i, combined.trim());
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Additional Playlists tab heuristics failed: {}", e.getMessage());
                }
            }
            if (playlistsTab == null) {
                logger.error("Failed to find Playlists tab by all selectors.");
                // Log all available tab elements for debugging
                try {
                    var tabs = page.querySelectorAll("[role='tab'], .tab, nav a, button, [aria-label]");
                    logger.info("Found {} tab-like elements.", tabs.size());
                    for (int i = 0; i < Math.min(tabs.size(), 10); i++) {
                        var t = tabs.get(i);
                        logger.info("Tab {}: text={}, aria-label={}, id={}, class={}", i, t.innerText(), t.getAttribute("aria-label"), t.getAttribute("id"), t.getAttribute("class"));
                    }
                    saveDebugArtifactsIfAllowed(page, "debug-playlists-tab", "music-pill-item, .huwFWuQ9IP0DpE5LSh_S");
                } catch (Exception e) {
                    logger.error("Failed to save Playlists tab debug artifacts: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to navigate to Playlists tab: {}", e.getMessage());
        }
    }

    /**
     * Checks if the user is signed in by looking for profile/account elements and absence of 'Sign in' button.
     * @param page Playwright page instance
     * @return true if signed in, false otherwise
     */
    public boolean isSignedIn(Page page) {
        try {
            // Lazy-reactive polling: quickly check, then wait for either profile UI or sign-in control to appear.
            long maxWaitMs = 12_000; // total patience
            long deadline = System.currentTimeMillis() + maxWaitMs;
            int pollMs = 300; // start small
            int maxPoll = 2000;

            // Combined selector that matches common 'signed-in' or 'sign in' UI elements
            String profileSel = "[aria-label='Account'], [data-test-id='profile-menu'], img[alt*='profile'], img[alt*='avatar'], button:has([data-icon='profile'])";
            String signInSel = "button:has-text('Sign in'), button:has-text('Sign In'), a[href*='signin'], [data-test-id='sign-in-button']";
            String combined = profileSel + ", " + signInSel;

            while (System.currentTimeMillis() < deadline) {
                try {
                    // Quick non-wait checks first (cheap)
                    Locator signInBtn = page.getByText("Sign in", new Page.GetByTextOptions().setExact(false));
                    if (signInBtn.count() > 0) {
                        logger.info("Detected 'Sign in' control quickly; user NOT signed in.");
                        return false;
                    }
                    for (String sel : new String[]{"[aria-label='Account']", "[data-test-id='profile-menu']", "img[alt*='profile']", "img[alt*='avatar']", "button:has([data-icon='profile'])"}) {
                        try {
                            Locator loc = page.locator(sel);
                            if (loc != null && loc.count() > 0) {
                                logger.info("Detected profile element '{}' ; user is signed in.", sel);
                                return true;
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}

                // Reactive wait: wait for either sign-in or profile element to appear for a short interval
                try {
                    page.waitForSelector(combined, new Page.WaitForSelectorOptions().setTimeout(pollMs));
                    // If waitForSelector returned (no exception), loop will re-check and return accordingly
                } catch (PlaywrightException ignored) {
                    // timeout or other; we'll back off and retry
                }

                // Exponential backoff but remain lazy (don't hammer)
                try { page.waitForTimeout(Math.min(pollMs, 500)); } catch (Exception ignored) {}
                pollMs = Math.min(maxPoll, pollMs * 2);
            }

            // Final best-effort checks after polling window
            try {
                Locator signInBtn = page.getByText("Sign in", new Page.GetByTextOptions().setExact(false));
                if (signInBtn.count() > 0) {
                    logger.info("Sign in button detected after polling; user is NOT signed in.");
                    return false;
                }
            } catch (Exception ignored) {}
            try {
                for (String sel : new String[]{"[aria-label='Account']", "[data-test-id='profile-menu']", "button:has([data-icon='profile'])", "img[alt*='profile']", "img[alt*='avatar']", "[aria-label*='Account']"}) {
                    try {
                        Locator loc = page.locator(sel);
                        if (loc != null && loc.count() > 0) {
                            logger.info("Detected profile element '{}' after polling; user is signed in.", sel);
                            return true;
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            // Additional heuristic: check browser cookies and localStorage for auth/session indicators
            try {
                var cookies = page.context().cookies();
                if (cookies != null) {
                    for (var c : cookies) {
                        try {
                            String cname = c.name != null ? c.name.toLowerCase() : "";
                            if (cname.contains("session") || cname.contains("ubid") || cname.contains("sso") || cname.contains("x-main") || cname.contains("csrf") || cname.contains("session-id")) {
                                logger.info("Found auth cookie '{}' ; assuming signed in.", cname);
                                return true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            try {
                Object keysObj = page.evaluate("() => Object.keys(window.localStorage || {}).join('|')");
                if (keysObj != null) {
                    String keys = keysObj.toString().toLowerCase();
                    if (keys.contains("auth") || keys.contains("session") || keys.contains("token") || keys.contains("user") || keys.contains("cognito")) {
                        logger.info("Found localStorage keys indicating auth: {}", keys);
                        return true;
                    }
                }
            } catch (Exception ignored) {}

            // Check document.cookie for amazon-relevant cookies
            try {
                Object docCookiesObj = page.evaluate("() => document.cookie || ''");
                if (docCookiesObj != null) {
                    String docCookies = docCookiesObj.toString().toLowerCase();
                    if (!docCookies.isEmpty()) {
                        logger.info("document.cookie contains: {}", docCookies.length() > 200 ? docCookies.substring(0,200) + "..." : docCookies);
                        if (docCookies.contains("ubid") || docCookies.contains("session-id") || docCookies.contains("sso") || docCookies.contains("x-main") || docCookies.contains("csm-session") || docCookies.contains("csrf") ) {
                            logger.info("Found amazon-like cookie in document.cookie; assuming signed in.");
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Inspect common global JS objects used by the site for user/session info
            try {
                Object glob = page.evaluate("() => { try { const keys=['amznMusic','amznMusic','AmazonMusic','AMZN','amazonMusic']; for(const k of keys){ if(window[k]) return k + ':' + JSON.stringify(Object.keys(window[k]||{}).slice(0,10)); } return null; } catch(e){ return null; } }");
                if (glob != null) {
                    logger.info("Found global site object hinting at auth: {}", glob);
                     return true;
                 }
             } catch (Exception ignored) {}

        } catch (Exception e) {
            logger.warn("Error checking sign-in status: {}", e.getMessage());
        }
        logger.info("Could not confirm sign-in after polling; assuming NOT signed in.");
        return false;
    }

    // Helper: save a focused debug HTML snippet and screenshot only when allowed; truncates large HTML
    private void saveDebugArtifactsIfAllowed(Page page, String baseName, String cssSelector) {
        if (!SAVE_DEBUG_ARTIFACTS) {
            logger.info("Debug artifact saving disabled (SCRAPER_SAVE_DEBUG=false). Skipping save for {}.", baseName);
            return;
        }
        try {
            // ensure directory
            try { Files.createDirectories(Paths.get(DEBUG_ARTIFACT_DIR)); } catch (IOException ignored) {}

            String timestamp = String.valueOf(System.currentTimeMillis());
            String safeBase = baseName.replaceAll("[^a-zA-Z0-9_.-]", "-");
            String combinedHtml = null;

            // If a selector is provided, try to capture up to DEBUG_MAX_ELEMENTS of their outerHTML
            if (cssSelector != null && !cssSelector.trim().isEmpty()) {
                try {
                    Object res = page.evaluate("(sel, max) => { const els = Array.from(document.querySelectorAll(sel)).slice(0, max); return els.map(e=>e.outerHTML).join('\n\n<!-- ELEMENT SEPARATOR -->\n\n'); }", new Object[]{cssSelector, DEBUG_MAX_ELEMENTS});
                    if (res != null) combinedHtml = res.toString();
                } catch (Exception e) {
                    logger.debug("Selector-based extraction failed for {}: {}", baseName, e.getMessage());
                }
            }

            // If selector didn't yield anything, decide whether to capture full page based on markers or explicit flag
            if (combinedHtml == null || combinedHtml.trim().isEmpty()) {
                boolean markerFound = false;
                try {
                    String body = page.content();
                    if (body != null) {
                        String lower = body.toLowerCase();
                        String[] markers = new String[]{"tracklist__item", "song-row", "music-vertical-item", "data-test='playlist'", "playlist"};
                        for (String m : markers) {
                            if (lower.contains(m)) { markerFound = true; break; }
                        }
                    }
                } catch (Exception ignored) {}

                if (!markerFound && !ALLOW_FULL_PAGE_SAVE) {
                    logger.info("No debug markers found and full-page save disabled; skipping debug save for {}.", baseName);
                    return;
                }

                try {
                    combinedHtml = page.content();
                } catch (Exception e) {
                    combinedHtml = "";
                }
            }

            if (combinedHtml == null) combinedHtml = "";
            if (combinedHtml.length() > DEBUG_HTML_MAX_BYTES) combinedHtml = combinedHtml.substring(0, DEBUG_HTML_MAX_BYTES) + "\n<!-- TRUNCATED -->";

            if (combinedHtml.trim().isEmpty()) {
                logger.warn("No HTML content available to save for {}. Skipping.", baseName);
            } else {
                String htmlName = safeBase + "-" + timestamp + ".html";
                try {
                    Files.writeString(Paths.get(DEBUG_ARTIFACT_DIR).resolve(htmlName), combinedHtml);
                    logger.info("Saved debug HTML: {}/{} ({} bytes)", DEBUG_ARTIFACT_DIR, htmlName, combinedHtml.length());
                } catch (IOException e) {
                    logger.warn("Failed to write debug HTML {}/{}: {}", DEBUG_ARTIFACT_DIR, htmlName, e.getMessage());
                }

                // screenshot: prefer element-focused if selector present and matches
                String pngName = safeBase + "-" + timestamp + ".png";
                try {
                    if (cssSelector != null && !cssSelector.trim().isEmpty()) {
                        try {
                            Locator loc = page.locator(cssSelector);
                            if (loc != null && loc.count() > 0) {
                                loc.first().screenshot(new Locator.ScreenshotOptions().setPath(Paths.get(DEBUG_ARTIFACT_DIR).resolve(pngName)));
                                logger.info("Saved element-focused debug screenshot: {}/{}", DEBUG_ARTIFACT_DIR, pngName);
                            } else {
                                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get(DEBUG_ARTIFACT_DIR).resolve(pngName)));
                                logger.info("Saved full-page debug screenshot (fallback): {}/{}", DEBUG_ARTIFACT_DIR, pngName);
                            }
                        } catch (Exception e) {
                            logger.warn("Element screenshot failed for {}: {}. Falling back to full-page.", baseName, e.getMessage());
                            try {
                                page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get(DEBUG_ARTIFACT_DIR).resolve(pngName)));
                                logger.info("Saved full-page debug screenshot: {}/{}", DEBUG_ARTIFACT_DIR, pngName);
                            } catch (Exception e2) {
                                logger.warn("Full-page screenshot also failed for {}: {}", baseName, e2.getMessage());
                            }
                        }
                    } else {
                        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get(DEBUG_ARTIFACT_DIR).resolve(pngName)));
                        logger.info("Saved full-page debug screenshot: {}/{}", DEBUG_ARTIFACT_DIR, pngName);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to capture debug screenshot for {}: {}", baseName, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Unexpected error saving debug artifacts for {}: {}", baseName, e.getMessage());
        }
    }
}
