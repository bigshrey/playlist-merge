package com.amazonmusic.scraper;

import java.io.IOException;
import java.util.List;

/**
 * Interface for CSV export operations for song data.
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

