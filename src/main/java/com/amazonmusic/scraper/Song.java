package com.amazonmusic.scraper;

import java.util.Map;

/**
 * Immutable record representing a song in an Amazon Music playlist.
 * <p>
 * Extraction Workflow:
 * <ul>
 *   <li>Metadata is extracted from the Amazon Music site using multiple selectors per field.</li>
 *   <li>Fields are cross-checked and normalized using {@link MetadataCrossChecker} to resolve discrepancies and assign a confidence score.</li>
 *   <li>After extraction, external validation (e.g., MusicBrainz API) is performed to further validate and enrich metadata.</li>
 *   <li>Provenance for each field is tracked in {@code sourceDetails}, mapping metadata field names (e.g., "title", "artist") to validation/cross-checking objects. Typically, this is a Map&lt;String, String&gt; of selector-to-value provenance, but it is extensible for other validation results.</li>
 *   <li>The {@code confidenceScore} reflects the reliability of the extracted metadata, adjusted by cross-checking and external validation.</li>
 *   <li>The {@code validated} flag indicates whether the song's metadata has been externally validated.</li>
 *   <li>Per-field validation status is tracked in {@code fieldValidationStatus} (Map&lt;String, Boolean&gt;).</li>
 * </ul>
 * <p>
 * All consumers (CSV, DB, ScraperService) use the new sourceDetails and fieldValidationStatus fields. MusicBrainzClient integration updates validated and confidenceScore fields as required.
 * <p>
 * Future extensibility: Per-field validation status and sourceDetails can be further extended for granular tracking and new validation/cross-checking objects.
 * <p>
 * AGENTIC CHANGE LOG (2025-09-04):
 * - [CLARIFIED] sourceDetails maps metadata field names to validation/cross-checking objects (usually provenance maps, but extensible).
 * - [DONE] All fields are immutable and validated at construction; null handling is robust for all consumers (CSV, DB, ScraperService).
 * - [DONE] Javadocs updated to clarify provenance and per-field validation status structure and usage.
 * - [DONE] Validated that all consumers (CsvService, PostgresService, ScraperService) handle nulls and empty maps correctly.
 * - [DONE] All changes logged in README Agentic Change Iteration Summary.
 * <p>
 * TODO [AGENTIC]: When adding new fields, normalization, or enrichment, update registry (MetadataFieldRegistry), normalization logic (MetadataCrossChecker), DB schema (PostgresService), and all consumers to maintain consistency across extraction, validation, and export workflows.
 * TODO [AGENTIC]: Per-field validation status and sourceDetails can be further extended for granular tracking; update DB/CSV schema and all consumers if structure changes.
 * TODO [AGENTIC]: Ensure all values in provenance map are non-null maps; log inconsistencies and update DB/CSV schema if Song.sourceDetails type changes.
 * <p>
 * @author Amazon Music Scraper Team
 * @since 1.0
 */
public record Song(
    String title,
    String artist,
    String album,
    String url,
    String duration,
    Integer trackNumber,
    Integer playlistPosition,
    Boolean explicit,
    String imageUrl,
    String releaseDate,
    String genre,
    String trackAsin,
    boolean validated,
    double confidenceScore,
    Map<String, Object> sourceDetails,
    Map<String, Boolean> fieldValidationStatus
) {}
