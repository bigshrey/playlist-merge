package com.amazonmusic.scraper;

import java.util.List;

/**
 * Represents a metadata field for cross-checking and normalization.
 * Contains the field name and associated selectors.
 * Extend this class as new fields and normalization logic are added.
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

