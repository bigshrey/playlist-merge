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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        for (String sel : SONG_SELECTOR_CANDIDATE
