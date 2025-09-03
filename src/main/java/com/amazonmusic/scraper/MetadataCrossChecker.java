package com.amazonmusic.scraper;

import com.microsoft.playwright.Locator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * Utility for cross-checking and normalizing metadata fields extracted from Amazon Music web elements.
 * <p>
 * Workflow:
 * <ul>
 *   <li>Receives a Playwright Locator and a list of selectors for a metadata field (e.g., title, artist, duration).</li>
 *   <li>Extracts values using all selectors, normalizes them, and prioritizes selectors based on field type.</li>
 *   <li>Assigns a confidence score based on agreement/discrepancy between selectors.</li>
 *   <li>Logs discrepancies and provenance for debugging and traceability.</li>
 *   <li>Provenance for each field is returned as a Map<String, String> and aggregated in Song.sourceDetails (Map<String, Object>).</li>
 *   <li>Supports integration with external validation (e.g., MusicBrainz) via ScraperService and MusicBrainzClient to adjust confidence and provenance and update per-field validation status.</li>
 *   <li>Per-field validation status is supported in Song via fieldValidationStatus (Map<String, Boolean>), which can be updated after external validation.</li>
 * </ul>
 * <p>
 * The {@code CrossCheckResult} contains the selected value, confidence score, and a map of selector-to-value provenance.
 * <p>
 * All major workflow TODOs are resolved. Remaining TODOs are for future extensibility and maintenance only.
 * <p>
 * TODO [PRIORITY: MEDIUM][2025-09-04]: sourceDetails map structure is currently consistent (Map<String, String> per field, aggregated as Map<String, Object> in Song). For future extensibility, consider using a config or enum to manage selectors and validation sources. Update this comment and logic if new sources or field types are added.
 * TODO: If Amazon Music changes markup, update regexes and add more bracket types as needed. (maintenance)
 * TODO: If new feature/remix patterns appear, add more passes here. (maintenance)
 *
 * @author Amazon Music Scraper Team
 * @since 1.0
 */
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

    /**
     * TODO [PRIORITY: MEDIUM][2025-09-04]: Refactored to accept MetadataField for field type inference and normalization.
     * Next: Move normalization/prioritization logic into MetadataField or a registry for extensibility.
     */
    public CrossCheckResult crossCheckField(Locator element, MetadataField field) {
        List<String> selectors = field.selectors;
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
        String fieldType = field.fieldName;
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
        if (title == null) return false;
        return java.util.regex.Pattern.compile("(Radio Edit|Extended Mix|Remix|Edit|Version|Club Mix|Original Mix)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(title).find();
    }
    // Helper to check for features/remix authors in title
    private boolean containsFeatureOrRemix(String title) {
        if (title == null) return false;
        return java.util.regex.Pattern.compile("(feat\\.?|featuring|remix by)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(title).find();
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

    // Normalize title: remove features, trailing remix/edit/mix credits, keep mix/edition info
    // Robust fallback: run two passes for round and square brackets to avoid regex compilation errors on some JVMs.
    // TODO: If Amazon Music changes markup, update regexes and add more bracket types as needed.
    private String normalizeTitle(String title) {
        if (title == null) return "";
        // Remove (feat. ...), - feat. ..., (featuring ...), - featuring ...
        title = title.replaceAll("\\s*[-(]?\\s*(feat\\.?|featuring)\\s+[^)]*\\)?", "");
        // Remove [feat. ...], - feat. ..., [featuring ...], - featuring ...
        title = title.replaceAll("\\s*[-\\[]?\\s*(feat\\.?|featuring)\\s+[^]]*]?", ""); // removed redundant escapes
        // Remove trailing remix/edit/mix credits: (ArtistName Remix), - ArtistName Mix, etc.
        title = title.replaceAll("\\s*[-(]?\\s*\\w+(?:\\s+\\w+)*\\s+(Remix|Edit|Mix|Version)\\)?$", "");
        // Remove trailing remix/edit/mix credits: [ArtistName Remix], - ArtistName Mix, etc.
        title = title.replaceAll("\\s*[-\\[]?\\s*\\w+(?:\\s+\\w+)*\\s+(Remix|Edit|Mix|Version)]?$", ""); // removed redundant escapes
        // TODO: If new feature/remix patterns appear, add more passes here.
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

    /**
     * Validates the provenance structure of a sourceDetails map.
     * Ensures all values are non-null maps and logs inconsistencies.
     * @param sourceDetails The aggregated provenance map (Map<String, Object>)
     */
    public static void validateProvenanceStructure(Map<String, Object> sourceDetails) {
        if (sourceDetails == null) {
            logger.warn("Provenance validation: sourceDetails map is null.");
            return;
        }
        for (Map.Entry<String, Object> entry : sourceDetails.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Map)) {
                logger.warn("Provenance validation: Value for '{}' is not a Map. Actual type: {}", entry.getKey(), value == null ? "null" : value.getClass().getName());
            } else if (value == null) {
                logger.warn("Provenance validation: Value for '{}' is null.", entry.getKey());
            }
        }
    }
}
