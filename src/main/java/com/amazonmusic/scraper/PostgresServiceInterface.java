package com.amazonmusic.scraper;

import java.util.List;

/**
 * Interface for Postgres DB operations used by the scraper.
 */
public interface PostgresServiceInterface {
    /**
     * Creates the necessary database tables (playlists and songs) if they don't already exist.
     */
    void createTables();
    
    /**
     * Inserts or updates a playlist in the database.
     * @param name The name of the playlist
     * @param url The URL of the playlist
     * @return The playlist ID if successful, -1 if failed
     */
    int insertPlaylist(String name, String url);
    
    /**
     * Inserts a list of songs associated with a specific playlist.
     * @param playlistId The ID of the playlist to associate songs with
     * @param songs List of Song objects to insert into the database
     */
    void insertSongs(int playlistId, List<Song> songs);
}

