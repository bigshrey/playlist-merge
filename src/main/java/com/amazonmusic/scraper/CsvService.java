package com.amazonmusic.scraper;

import com.opencsv.CSVWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Service for exporting song data to CSV files using OpenCSV.
 * Handles safe string conversion and logging.
 */
public class CsvService implements CsvServiceInterface {
    private static final Logger logger = LoggerFactory.getLogger(CsvService.class);

    /**
     * Writes a list of songs to a CSV file.
     * @param songs List of Song records to export
     * @param filename Output CSV filename
     * @throws IOException if file writing fails
     */
    public void writeSongsToCSV(List<Song> songs, String filename) throws IOException {
        if (songs == null) {
            logger.warn("Attempted to write null song list to CSV: {}", filename);
            throw new IllegalArgumentException("Song list cannot be null");
        }
        if (filename == null || filename.trim().isEmpty()) {
            logger.warn("Attempted to write CSV with invalid filename: {}", filename);
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }
        try (CSVWriter writer = new CSVWriter(new FileWriter(filename))) {
            writer.writeNext(new String[]{
                "Title", "Artist", "Album", "URL", "Duration", "TrackNumber", "PlaylistPosition", "Explicit", "ImageURL", "ReleaseDate", "Genre"
            });
            for (Song song : songs) {
                writer.writeNext(new String[]{
                    safe(song.title()),
                    safe(song.artist()),
                    safe(song.album()),
                    safe(song.url()),
                    safe(song.duration()),
                    song.trackNumber() == null ? "" : song.trackNumber().toString(),
                    song.playlistPosition() == null ? "" : song.playlistPosition().toString(),
                    song.explicit() == null ? "" : song.explicit().toString(),
                    safe(song.imageUrl()),
                    safe(song.releaseDate()),
                    safe(song.genre())
                });
            }
        }
        logger.info("Wrote {} songs to CSV file: {}", songs.size(), filename);
    }

    /**
     * Safely converts a string for CSV output, removing commas and newlines.
     * @param s Input string
     * @return Sanitized string
     */
    private static String safe(String s) {
        // Replace commas with space and collapse any CR/LF characters into a single space, then trim
        return s == null ? "" : s.replace(",", " ").replaceAll("[\\r\\n]+", " ").trim();
    }
}