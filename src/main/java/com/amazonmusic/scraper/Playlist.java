package com.amazonmusic.scraper;

import java.util.List;

/**
 * Immutable record representing a playlist and its songs in Amazon Music.
 */
public record Playlist(String name, List<Song> songs) {}
