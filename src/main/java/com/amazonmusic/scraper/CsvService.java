package com.amazonmusic.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Service for exporting song data to CSV files using OpenCSV.
 * <p>
 * AGENTIC CHANGE LOG (2025-09-04):
 * - [IN PROGRESS] Auditing for null checks and error handling in Playwright-related methods per README agentic TODOs.
 * - [NEXT] Add explicit null checks and log progress after each method edit.
 * - [NEXT] Update Javadocs after each change to reflect progress and completion.
 *
 * Workflow:
 * <ul>
 *   <li>Exports all Song fields, including provenance ({@code sourceDetails}) and validation status.</li>
 *   <li>Ensures safe string conversion and logs export operations.</li>
 *   <li>Supports future extensibility for additional metadata fields.</li>
 * </ul>
 * <p>
 * Future extensibility: Can be extended to export per-field validation status and enriched metadata.
 * <p>
 * TODO [AGENTIC]: When adding new fields, normalization, or enrichment, update CSV_FIELDS, registry (MetadataFieldRegistry), normalization logic (MetadataCrossChecker), DB schema (PostgresService), and all consumers to maintain consistency across extraction, validation, and export workflows.
 * TODO [AGENTIC]: If Song.sourceDetails type changes, update CSV export logic and all consumers (DB, reporting, validation).
 *
 * @author Amazon Music Scraper Team
 * @since 1.0
 */
public class CsvService implements CsvServiceInterface {
    private static final Logger logger = LoggerFactory.getLogger(CsvService.class);

    // Central registry of CSV-exported fields for extensibility
    private static final List<MetadataField> CSV_FIELDS = List.of(
        new MetadataField("Title", List.of()),
        new MetadataField("Artist", List.of()),
        new MetadataField("Album", List.of()),
        new MetadataField("URL", List.of()),
        new MetadataField("Duration", List.of()),
        new MetadataField("TrackNumber", List.of()),
        new MetadataField("PlaylistPosition", List.of()),
        new MetadataField("Explicit", List.of()),
        new MetadataField("ImageURL", List.of()),
        new MetadataField("ReleaseDate", List.of()),
        new MetadataField("Genre", List.of()),
        new MetadataField("TrackASIN", List.of()),
        new MetadataField("Validated", List.of()),
        new MetadataField("ConfidenceScore", List.of()),
        new MetadataField("SourceDetails", List.of()),
        new MetadataField("FieldValidationStatus", List.of())
    );
    // TODO [PRIORITY: MEDIUM][2025-09-04]: Update CSV_FIELDS when adding new metadata fields. Ensure all consumers (CSV, DB, validation) use this registry for consistency.

    /**
     * Writes a list of songs to a CSV file.
     * @param songs List of Song records to export
     * @param filename Output CSV filename
     * @throws IOException if file writing fails
     * <p>
     * All major workflow TODOs are resolved:
     * - Exports all Song fields, including provenance (sourceDetails) and per-field validation status.
     * - CSV export logic matches Song record structure.
     * Remaining TODOs are for future extensibility only.
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
        // ensure scraped-data directory exists and write files into it
        try {
            Path outDir = Paths.get("scraped-data");
            if (!Files.exists(outDir)) Files.createDirectories(outDir);
            filename = outDir.resolve(filename).toString();
        } catch (IOException e) {
            logger.warn("Failed to ensure scraped-data directory exists: {}. Will attempt to write to current directory.", e.getMessage());
        }
        try (CSVWriter writer = new CSVWriter(new FileWriter(filename))) {
            // Dynamic header from CSV_FIELDS
            writer.writeNext(CSV_FIELDS.stream().map(f -> f.fieldName).toArray(String[]::new));
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
                    safe(song.genre()),
                    safe(song.trackAsin()),
                    Boolean.toString(song.validated()),
                    Double.toString(song.confidenceScore()),
                    song.sourceDetails() == null ? "" : new ObjectMapper().writeValueAsString(song.sourceDetails()),
                    song.fieldValidationStatus() == null ? "" : new ObjectMapper().writeValueAsString(song.fieldValidationStatus())
                });
            }
        }
        logger.info("Wrote {} songs to CSV file: {}", songs.size(), filename);
    }

    // TODO: Update CSV export logic if Song.sourceDetails type changes to Map<String, Object>. (future extensibility)
    // TODO [RESOLVED 2025-09-04]: Add support for exporting per-field validation status if added to Song. (already implemented)
    // Per-field validation status is exported as JSON in the FieldValidationStatus column. See writeSongsToCSV for details.

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