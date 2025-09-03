package com.amazonmusic.scraper;

import com.microsoft.playwright.Page;

import java.util.List;
import java.util.Map;

/**
 * Interface for scraping operations.
 */
public interface ScraperServiceInterface {
    Playlist scrapePlaylist(Page page, String playlistUrl);
    List<Map<String, String>> scrapePlaylistLinks(Page page);
    void goToLibraryPlaylists(Page page);
    boolean isSignedIn(Page page);
}

