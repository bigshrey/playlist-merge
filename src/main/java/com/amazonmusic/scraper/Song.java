package com.amazonmusic.scraper;

/**
 * Immutable record representing a song in an Amazon Music playlist.
 * @param title The title of the song
 * @param artist The artist name of the song
 * @param album The album name where the song appears
 * @param url The Amazon Music URL for the song
 * @param duration The duration of the song (e.g., "3:45")
 * @param trackNumber The track number on the album (may be null)
 * @param playlistPosition The position of the song in the playlist (may be null)
 * @param explicit Whether the song contains explicit content (may be null)
 * @param imageUrl The URL of the song's cover image
 * @param releaseDate The release date of the song
 * @param genre The genre classification of the song
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
