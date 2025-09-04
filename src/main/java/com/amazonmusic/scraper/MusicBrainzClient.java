package com.amazonmusic.scraper;

import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility for validating and enriching song metadata using the MusicBrainz API.
 * <p>
 * Workflow:
 * <ul>
 *   <li>Queries MusicBrainz using song title and artist via HTTP GET (JSON response).</li>
 *   <li>Parses the best matching recording for validation and enrichment.</li>
 *   <li>Enriches genre and release date if available from MusicBrainz tags/releases.</li>
 *   <li>Updates provenance and per-field validation status in Song.</li>
 *   <li>Handles API/network errors gracefully and logs all actions for agentic traceability.</li>
 * </ul>
 * <p>
 * Error Handling:
 * <ul>
 *   <li>All HTTP and JSON parsing errors are caught and logged.</li>
 *   <li>If API fails or no match is found, original Song fields are preserved.</li>
 *   <li>Provenance and validation status reflect API result or error.</li>
 * </ul>
 * <p>
 * Future extensibility: Can be extended to support additional enrichment fields and sources.
 * <p>
 * AGENTIC CHANGE LOG (2025-09-04):
 * - [DONE] Integrated real MusicBrainz API querying for song validation and enrichment.
 * - [DONE] Updated provenance and per-field validation status based on API results.
 * - [DONE] Javadocs and comments updated to reflect new workflow and error handling.
 * - [NEXT] Validate integration with Song, CsvService, PostgresService, and ScraperService for schema/logic changes.
 * - [NEXT] Log all changes in README Agentic Change Iteration Summary.
 *
 * TODO [AGENTIC]: When adding new fields, normalization, or enrichment, update registry (MetadataFieldRegistry), normalization logic (MetadataCrossChecker), DB schema (PostgresService), and all consumers to maintain consistency across extraction, validation, and export workflows.
 * TODO [AGENTIC]: If new enrichment fields are added, update Song, DB/CSV schema, and all consumers accordingly.
 *
 * @author Amazon Music Scraper Team
 * @since 1.0
 */
public class MusicBrainzClient {
    // Implementation to be added: methods for querying MusicBrainz and validating/enriching Song metadata.

    /**
     * Validates and enriches Song metadata using MusicBrainz API.
     * Adjusts confidenceScore, sets validated flag, and updates provenance.
     * Enriches genre and releaseDate if available from API.
     * Logs validation actions and results.
     *
     * @param song Song to validate and enrich
     * @return New Song with updated validation, confidence, provenance, genre, and releaseDate
     */
    public Song validateAndEnrich(Song song) {
        boolean isValid = false;
        double newConfidence = song.confidenceScore();
        String enrichedGenre = song.genre();
        String enrichedReleaseDate = song.releaseDate();
        // Query MusicBrainz API
        try {
            String query = String.format("https://musicbrainz.org/ws/2/recording/?query=recording:%s%%20AND%%20artist:%s&fmt=json",
                java.net.URLEncoder.encode(song.title(), java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLEncoder.encode(song.artist(), java.nio.charset.StandardCharsets.UTF_8));
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(query)).header("User-Agent", "AmazonMusicScraper/1.0 (contact@example.com)").build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.body());
                JsonNode recordings = root.path("recordings");
                if (recordings.isArray() && recordings.size() > 0) {
                    JsonNode best = recordings.get(0);
                    isValid = true;
                    newConfidence = Math.min(1.0, song.confidenceScore() + 0.2);
                    // Enrich genre if available
                    JsonNode tags = best.path("tags");
                    if (tags.isArray() && tags.size() > 0) {
                        enrichedGenre = tags.get(0).path("name").asText(enrichedGenre);
                    }
                    // Enrich release date if available
                    JsonNode releases = best.path("releases");
                    if (releases.isArray() && releases.size() > 0) {
                        String date = releases.get(0).path("date").asText("");
                        if (!date.isBlank()) enrichedReleaseDate = date;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("MusicBrainz API error: " + e.getMessage());
        }
        // Update provenance to include MusicBrainz validation and enrichment
        Map<String, Object> newSourceDetails = new java.util.HashMap<>(song.sourceDetails());
        newSourceDetails.put("MusicBrainz", Map.of(
            "source", "MusicBrainz",
            "status", isValid ? "Validated" : "Not validated"
        ));
        newSourceDetails.put("GenreEnrichment", Map.of(
            "source", "MusicBrainz",
            "value", enrichedGenre
        ));
        newSourceDetails.put("ReleaseDateEnrichment", Map.of(
            "source", "MusicBrainz",
            "value", enrichedReleaseDate
        ));
        // Update per-field validation status
        Map<String, Boolean> newFieldValidationStatus = new java.util.HashMap<>(song.fieldValidationStatus());
        newFieldValidationStatus.put("MusicBrainz", isValid);
        newFieldValidationStatus.put("GenreEnrichment", enrichedGenre != null && !enrichedGenre.isBlank());
        newFieldValidationStatus.put("ReleaseDateEnrichment", enrichedReleaseDate != null && !enrichedReleaseDate.isBlank());
        // Log validation and enrichment result
        System.out.println("MusicBrainz validation for '" + song.title() + "' by '" + song.artist() + "': " + (isValid ? "VALIDATED" : "NOT VALIDATED"));
        System.out.println("Genre enrichment: " + enrichedGenre);
        System.out.println("Release date enrichment: " + enrichedReleaseDate);
        // Return new Song with updated fields
        return new Song(
            song.title(),
            song.artist(),
            song.album(),
            song.url(),
            song.duration(),
            song.trackNumber(),
            song.playlistPosition(),
            song.explicit(),
            song.imageUrl(),
            enrichedReleaseDate,
            enrichedGenre,
            song.trackAsin(),
            isValid,
            newConfidence,
            newSourceDetails,
            newFieldValidationStatus
        );
    }
}
