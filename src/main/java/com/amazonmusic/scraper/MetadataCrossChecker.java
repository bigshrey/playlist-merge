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
        // Compare results
        Set<String> uniqueVals = new HashSet<>(results.values());
        double confidence = uniqueVals.size() == 1 && !uniqueVals.isEmpty() ? 1.0 : 1.0 / Math.max(1, uniqueVals.size());
        if (uniqueVals.size() > 1) {
            logger.warn("Selector discrepancy: {}", results);
        }
        // Pick the first non-empty value as canonical
        String selected = results.values().stream().filter(v -> !v.isEmpty()).findFirst().orElse("");
        return new CrossCheckResult(selected, confidence, results);
    }
}

