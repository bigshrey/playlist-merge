package com.amazonmusic.scraper;

import com.microsoft.playwright.Page;

import java.util.List;
import java.util.Map;

/**
 * Interface for scraping operations.
 * <p>
 * AGENTIC CHANGE LOG (2025-09-04):
 * - [IN PROGRESS] Auditing for null checks and error handling in Playwright-related methods per README agentic TODOs.
 * - [NEXT] Add explicit null checks and log progress after each method edit.
 * - [NEXT] Update Javadocs after each change to reflect progress and completion.
 *
 * AGENTIC TODO: When adding new fields, normalization, or enrichment to Song or metadata, update registry (MetadataFieldRegistry), normalization logic (MetadataCrossChecker), DB schema (PostgresService), CSV export logic, and all consumers to maintain consistency across extraction, validation, and export workflows.
 * AGENTIC TODO: If Song.sourceDetails type changes, update all consumers (DB, CSV, reporting, validation) and document interface changes here.
 */
public interface ScraperServiceInterface {
    /**
     * Scrapes all songs from a specific Amazon Music playlist.
     * @param page Playwright page instance to use for scraping
     * @param playlistUrl URL of the playlist to scrape
     * @return Playlist object containing playlist name and list of songs
     */
    Playlist scrapePlaylist(Page page, String playlistUrl);
    
    /**
     * Scrapes playlist links from the user's library playlists page.
     * @param page Playwright page instance positioned on the library playlists page
     * @return List of maps containing playlist names and URLs
     */
    List<Map<String, String>> scrapePlaylistLinks(Page page);
    
    /**
     * Navigates to the library playlists page in Amazon Music.
     * @param page Playwright page instance to navigate
     */
    void goToLibraryPlaylists(Page page);
    
    /**
     * Checks if the user is currently signed in to Amazon Music.
     * @param page Playwright page instance to check
     * @return true if user is signed in, false otherwise
     */
    boolean isSignedIn(Page page);
}
