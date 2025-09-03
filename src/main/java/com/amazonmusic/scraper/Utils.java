package com.amazonmusic.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

/**
 * Utility class for common helper methods used in scraping and file operations.
 * 
 * @author Amazon Music Scraper Team
 * @since 1.0
 */
public class Utils {
    private static final Logger logger = LoggerFactory.getLogger(Utils.class);

    /**
     * Sanitizes a filename by replacing each special character and whitespace with an underscore.
     * @param name Input filename
     * @return Sanitized filename
     */
    public static String sanitizeFilename(String name) {
        // Replace each invalid character or whitespace with a single underscore
        return name == null ? "" : name.replaceAll("[*?\"<>|/:\\s]", "_");
    }

    /**
     * Retries a Playwright action up to maxRetries times with exponential backoff.
     * @param action Callable action to execute
     * @param maxRetries Maximum number of retries
     * @param actionDesc Description for logging
     * @param <T> Return type
     * @return Result of action, or null if all retries fail
     */
    public static <T> T retryPlaywrightAction(Callable<T> action, int maxRetries, String actionDesc) {
        int attempts = 0;
        // Confirm retry logic is robust for Playwright actions
        while (attempts < maxRetries) {
            try {
                return action.call();
            } catch (Exception e) {
                logger.warn("Failed {} (attempt {}): {}", actionDesc, attempts + 1, e.getMessage());
                attempts++;
                try {
                    Thread.sleep((long) Math.pow(2, attempts) * 1000); // Exponential backoff
                } catch (InterruptedException ignored) {
                }
            }
        }
        logger.error("Giving up on {} after {} attempts.", actionDesc, maxRetries);
        return null;
    }
}
