package com.amazonmusic.scraper;

import java.util.List;

/**
 * Immutable record representing a playlist and its songs in Amazon Music.
 * <p>
 * Workflow:
 * <ul>
 *   <li>Contains playlist metadata and a list of Song objects, each with provenance and validation status.</li>
 *   <li>Constructed after scraping and validating all songs in the playlist.</li>
 *   <li>Supports export to CSV and database, preserving all song metadata and provenance.</li>
 * </ul>
 * <p>
 * Future extensibility: Can be extended to include playlist-level validation and enrichment.
 * <p>
 * TODO [AGENTIC]: When adding new fields, normalization, or enrichment to Song, update registry (MetadataFieldRegistry), normalization logic (MetadataCrossChecker), DB schema (PostgresService), CSV export logic (CsvService), and all consumers to maintain consistency across extraction, validation, and export workflows.
 */
public record Playlist(String name, String url, List<Song> songs) {}
