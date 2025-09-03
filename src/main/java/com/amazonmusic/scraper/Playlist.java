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
 *
 * @author Amazon Music Scraper Team
 * @since 1.0
 */
public record Playlist(String name, String url, List<Song> songs) {}
