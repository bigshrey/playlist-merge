package com.amazonmusic.scraper;

import com.microsoft.playwright.Page;

import java.util.List;
import java.util.Map;

/**
 * Interface for scraping operations.
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

