package com.amazonmusic.scraper;

import java.util.List;

/**
 * Represents a metadata field for cross-checking and normalization.
 * Contains the field name and associated selectors.
 * Extend this class as new fields and normalization logic are added.
 *
 * TODO [AGENTIC]: When adding new fields, normalization, or enrichment, update the registry (MetadataFieldRegistry), normalization logic (MetadataCrossChecker), DB schema (PostgresService), and all consumers to maintain consistency across extraction, validation, and export workflows.
 * TODO [AGENTIC]: Consider refactoring normalization/prioritization logic into this class or a registry for extensibility.
 */
public class MetadataField {
    public final String fieldName;
    public final List<String> selectors;

    public MetadataField(String fieldName, List<String> selectors) {
        this.fieldName = fieldName;
        this.selectors = selectors;
    }

    // TODO: Add normalization/prioritization logic per field as needed
}
