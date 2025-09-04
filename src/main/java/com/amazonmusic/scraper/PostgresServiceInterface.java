package com.amazonmusic.scraper;

import java.util.List;

/**
 * Interface for Postgres DB operations used by the scraper.
 * <p>
 * AGENTIC CHANGE LOG (2025-09-04):
 * - [IN PROGRESS] Auditing for null checks and error handling in Playwright-related methods per README agentic TODOs.
 * - [NEXT] Add explicit null checks and log progress after each method edit.
 * - [NEXT] Update Javadocs after each change to reflect progress and completion.
 *
 * AGENTIC TODO: When adding new fields, normalization, or enrichment to Song or metadata, update registry (MetadataFieldRegistry), normalization logic (MetadataCrossChecker), DB schema, CSV export logic, and all consumers to maintain consistency across extraction, validation, and export workflows.
 * AGENTIC TODO: If Song.sourceDetails type changes, update all consumers (CSV, reporting, validation) and document interface changes here.
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
