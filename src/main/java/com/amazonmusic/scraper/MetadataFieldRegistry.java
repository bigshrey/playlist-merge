package com.amazonmusic.scraper;

import java.util.*;

/**
 * Central registry for all metadata fields and their selectors.
 * Provides static access to all fields for extraction, export, and validation.
 * Extensible: add new fields/selectors here and all consumers will use them.
 */
public final class MetadataFieldRegistry {
    private MetadataFieldRegistry() {}

    // Registry of all metadata fields and their selectors
    private static final List<MetadataField> FIELDS = List.of(
        new MetadataField("title", List.of(
            "a[data-test='track-title']", ".track-title", "[data-testid*='title']", ".title", "h2", "h3", "[aria-label]", "[alt]"
        )),
        new MetadataField("artist", List.of(
            "a[data-test='track-artist']", ".track-artist", "[data-testid*='artist']", ".artist", "h4", "h5", "[aria-label]", "[alt]"
        )),
        new MetadataField("album", List.of(
            "a[data-test='track-album']", ".track-album", "[data-testid*='album']", ".album", "[aria-label]"
        )),
        new MetadataField("url", List.of(
            "a[data-test='track-title']", "a", "[href]"
        )),
        new MetadataField("duration", List.of(
            "span[data-testid='duration']", ".duration", ".track-duration", "[aria-label]"
        )),
        new MetadataField("trackNumber", List.of(
            "span[data-testid='track-number']", ".track-number", ".index", ".position"
        )),
        new MetadataField("playlistPosition", List.of()),
        new MetadataField("explicit", List.of(
            ".explicit", "[aria-label*='explicit']", "[data-testid*='explicit']"
        )),
        new MetadataField("imageUrl", List.of(
            "img[data-testid='playlist-image']", "img", "[data-src]", "[src]"
        )),
        new MetadataField("releaseDate", List.of()),
        new MetadataField("genre", List.of()),
        new MetadataField("trackAsin", List.of()),
        new MetadataField("validated", List.of()),
        new MetadataField("confidenceScore", List.of()),
        new MetadataField("sourceDetails", List.of()),
        new MetadataField("fieldValidationStatus", List.of())
    );

    /**
     * Returns the list of all metadata fields.
     */
    public static List<MetadataField> getFields() {
        return FIELDS;
    }

    /**
     * Returns the list of all field names.
     */
    public static List<String> getFieldNames() {
        List<String> names = new ArrayList<>();
        for (MetadataField f : FIELDS) names.add(f.fieldName);
        return names;
    }

    /**
     * Returns the MetadataField for a given field name, or null if not found.
     */
    public static MetadataField getField(String name) {
        for (MetadataField f : FIELDS) if (f.fieldName.equals(name)) return f;
        return null;
    }
}

