package com.amazonmusic.scraper;

import java.util.List;

/**
 * Interface for Postgres DB operations used by the scraper.
 */
public interface PostgresServiceInterface {
    void createTables();
    int insertPlaylist(String name, String url);
    void insertSongs(int playlistId, List<Song> songs);
}

