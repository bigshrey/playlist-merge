package com.amazonmusic.scraper;

import java.util.List;

/**
 * Immutable record representing a playlist and its songs in Amazon Music.
 * @param name The name of the playlist
 * @param songs List of Song objects contained in this playlist
 */
public record Playlist(String name, List<Song> songs) {}
