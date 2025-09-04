package com.amazonmusic.scraper;

import java.io.IOException;
import java.util.List;

/**
 * Interface for CSV export operations for song data.
 * <p>
 * AGENTIC CHANGE LOG (2025-09-04):
 * - [IN PROGRESS] Auditing for null checks and error handling in Playwright-related methods per README agentic TODOs.
 * - [NEXT] Add explicit null checks and log progress after each method edit.
 * - [NEXT] Update Javadocs after each change to reflect progress and completion.
 *
 * AGENTIC TODO: When adding new fields, normalization, or enrichment to Song or metadata, update registry (MetadataFieldRegistry), normalization logic (MetadataCrossChecker), DB schema (PostgresService), CSV export logic, and all consumers to maintain consistency across extraction, validation, and export workflows.
 * AGENTIC TODO: If Song.sourceDetails type changes, update all consumers (DB, reporting, validation) and document interface changes here.
 */
public interface CsvServiceInterface {
    /**
     * Writes a list of songs to a CSV file with appropriate headers.
     * @param songs List of Song objects to export to CSV
     * @param filename Name of the output CSV file
     * @throws IOException if file writing fails
     */
    void writeSongsToCSV(List<Song> songs, String filename) throws IOException;
}
