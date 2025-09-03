package com.amazonmusic.sitedatascrape;

import com.amazonmusic.scraper.AuthService;
import com.amazonmusic.scraper.AuthServiceInterface;
import com.amazonmusic.scraper.ScraperService;
import com.amazonmusic.scraper.ScraperServiceInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deprecated helper wrapper. Use Main / ScraperService directly instead.
 */
@Deprecated
public class AmazonMusicScraper {
    private static final Logger logger = LoggerFactory.getLogger(AmazonMusicScraper.class);

    private final AuthServiceInterface authService;
    private final ScraperServiceInterface scraperService;

    public AmazonMusicScraper() {
        this(new AuthService(), new ScraperService());
    }

    public AmazonMusicScraper(AuthServiceInterface authService, ScraperServiceInterface scraperService) {
        this.authService = authService == null ? new AuthService() : authService;
        this.scraperService = scraperService == null ? new ScraperService(this.authService) : scraperService;
    }

    /**
     * Simple wrapper entry that delegates to ScraperService when explicitly invoked.
     * Kept for backwards-compatibility only.
     */
    public void analyzeSiteStructure() {
        logger.warn("AmazonMusicScraper is deprecated. Use com.amazonmusic.scraper.ScraperService via Main instead.");
        try {
            authService.init();
            try (var playwright = com.microsoft.playwright.Playwright.create()) {
                var context = authService.setupBrowserContext(playwright);
                var page = context.newPage();
                authService.automateSignIn(page);
                // Delegate to ScraperService for analysis if caller expects more
                scraperService.goToLibraryPlaylists(page);
                var playlists = scraperService.scrapePlaylistLinks(page);
                logger.info("Wrapper found {} playlists (delegated to ScraperService).", playlists == null ? 0 : playlists.size());
                context.browser().close();
            } catch (Exception e) {
                logger.error("Wrapper failed to execute scraper: {}", e.getMessage());
            }
        } catch (Exception e) {
            logger.error("Deprecated wrapper initialization failed: {}", e.getMessage());
        }
    }
}
