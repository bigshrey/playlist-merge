package com.amazonmusic.scraper;

/**
 * Immutable record representing a song in an Amazon Music playlist.
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
    String genre
) {}
