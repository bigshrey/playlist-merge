package com.amazonmusic.scraper;

import com.microsoft.playwright.Locator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

public class MetadataCrossChecker {
    private static final Logger logger = LoggerFactory.getLogger(MetadataCrossChecker.class);

    public static class CrossCheckResult {
        public final String value;
        public final double confidenceScore;
        public final Map<String, String> sourceDetails;
        public CrossCheckResult(String value, double confidenceScore, Map<String, String> sourceDetails) {
            this.value = value;
            this.confidenceScore = confidenceScore;
            this.sourceDetails = sourceDetails;
        }
    }

    public CrossCheckResult crossCheckField(Locator element, List<String> selectors) {
        Map<String, String> results = new LinkedHashMap<>();
        for (String selector : selectors) {
            try {
                Locator loc = element.locator(selector);
                String val = (loc != null && loc.count() > 0) ? loc.first().innerText().trim() : "";
                if (!val.isEmpty()) results.put(selector, val);
            } catch (Exception e) {
                logger.debug("Selector error for '{}': {}", selector, e.getMessage());
            }
        }
        // Field-specific normalization and prioritization
        String fieldType = inferFieldType(selectors);
        Map<String, String> normalizedResults = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : results.entrySet()) {
            String normVal = switch (fieldType) {
                case "title" -> normalizeTitle(entry.getValue());
                case "artist" -> normalizeArtist(entry.getValue());
                case "duration" -> normalizeDuration(entry.getValue());
                default -> entry.getValue();
            };
            normalizedResults.put(entry.getKey(), normVal);
        }
        // Prioritize selectors for canonical value selection
        String selected = selectCanonicalValue(fieldType, normalizedResults, selectors);
        // Confidence score: weighted for title, artist, duration
        double confidence = calculateConfidence(fieldType, normalizedResults);
        if (new HashSet<>(normalizedResults.values()).size() > 1) {
            logger.warn("Selector discrepancy for {}: {}", fieldType, normalizedResults);
        }
        return new CrossCheckResult(selected, confidence, normalizedResults);
    }

    // Select canonical value based on field type and selector priority
    private String selectCanonicalValue(String fieldType, Map<String, String> normalizedResults, List<String> selectors) {
        // For title, prefer values with mix/edition info, without features/remix authors
        if (fieldType.equals("title")) {
            for (String selector : selectors) {
                String val = normalizedResults.get(selector);
                if (val != null && !val.isEmpty() && containsMixEdition(val) && !containsFeatureOrRemix(val)) {
                    return val;
                }
            }
        }
        // For artist, prefer values with remix/edit artists at start
        if (fieldType.equals("artist")) {
            for (String selector : selectors) {
                String val = normalizedResults.get(selector);
                if (val != null && !val.isEmpty() && startsWithRemixOrEdit(val)) {
                    return val;
                }
            }
        }
        // For duration, use the most common value
        if (fieldType.equals("duration")) {
            Map<String, Integer> freq = new HashMap<>();
            for (String val : normalizedResults.values()) {
                freq.put(val, freq.getOrDefault(val, 0) + 1);
            }
            return freq.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        }
        // Default: first non-empty value
        return normalizedResults.values().stream().filter(v -> !v.isEmpty()).findFirst().orElse("");
    }

    // Helper to check for mix/edition info in title
    private boolean containsMixEdition(String title) {
        return title != null && title.matches(".*(Radio Edit|Extended Mix|Remix|Edit|Version|Club Mix|Original Mix).*", java.util.regex.Pattern.CASE_INSENSITIVE);
    }
    // Helper to check for features/remix authors in title
    private boolean containsFeatureOrRemix(String title) {
        return title != null && title.matches(".*(feat\\.?|featuring|remix by).*", java.util.regex.Pattern.CASE_INSENSITIVE);
    }
    // Helper to check if artist starts with remix/edit artist
    private boolean startsWithRemixOrEdit(String artist) {
        if (artist == null) return false;
        String[] parts = artist.split(",");
        if (parts.length == 0) return false;
        String first = parts[0].trim().toLowerCase();
        return first.contains("remix") || first.contains("edit");
    }

    // Improved confidence calculation: weighted for key fields
    private double calculateConfidence(String fieldType, Map<String, String> normalizedResults) {
        Set<String> uniqueVals = new HashSet<>(normalizedResults.values());
        if (uniqueVals.isEmpty()) return 0.0;
        if (uniqueVals.size() == 1) return 1.0;
        // For title/artist/duration, penalize disagreement more
        double base = 1.0 / uniqueVals.size();
        if (fieldType.equals("title") || fieldType.equals("artist") || fieldType.equals("duration")) {
            return base * 0.7;
        }
        return base;
    }

    // Infer field type from selectors for normalization
    private String inferFieldType(List<String> selectors) {
        String joined = String.join(",", selectors).toLowerCase();
        if (joined.contains("title")) return "title";
        if (joined.contains("artist")) return "artist";
        if (joined.contains("duration")) return "duration";
        return "other";
    }

    // Normalize title: remove features/remix authors, keep mix/edition info
    private String normalizeTitle(String title) {
        if (title == null) return "";
        // Remove (feat. ...), [feat. ...], - feat. ...
        title = title.replaceAll("\\s*[\-\(\[]?feat\\.?[^\)\]]*[\)\]]?", "");
        // Remove (Remix by ...), [Remix by ...], - Remix by ...
        title = title.replaceAll("\\s*[\-\(\[]?remix by[^\)\]]*[\)\]]?", "");
        // Keep mix/edition info (e.g., Radio Edit, Extended Mix)
        // Already present in most titles, so just trim
        return title.trim();
    }

    // Normalize artist: prioritize remix/edit artists at start
    private String normalizeArtist(String artist) {
        if (artist == null) return "";
        // Split by common separators
        String[] parts = artist.split(",|;|&|feat\\.?|with|vs\\.?|/|");
        List<String> remixers = new ArrayList<>();
        List<String> mainArtists = new ArrayList<>();
        for (String part : parts) {
            String p = part.trim();
            if (p.toLowerCase().contains("remix") || p.toLowerCase().contains("edit")) {
                remixers.add(p);
            } else {
                mainArtists.add(p);
            }
        }
        // Remix/edit artists first, then main artists
        List<String> all = new ArrayList<>();
        all.addAll(remixers);
        all.addAll(mainArtists);
        // Remove duplicates
        LinkedHashSet<String> deduped = new LinkedHashSet<>(all);
        return String.join(", ", deduped);
    }

    // Normalize duration: standardize to mm:ss
    private String normalizeDuration(String duration) {
        if (duration == null) return "";
        // Remove any non-digit/non-colon chars
        duration = duration.replaceAll("[^0-9:]", "");
        // If duration is in seconds, convert to mm:ss
        try {
            if (duration.matches("\\d{1,3}")) {
                int sec = Integer.parseInt(duration);
                return String.format("%d:%02d", sec / 60, sec % 60);
            }
        } catch (Exception ignored) {}
        // If already mm:ss, return as is
        if (duration.matches("\\d{1,2}:\\d{2}")) return duration;
        return duration;
    }
}
