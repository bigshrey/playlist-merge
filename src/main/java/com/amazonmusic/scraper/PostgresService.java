package com.amazonmusic.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.*;
import java.util.List;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Paths;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service for interacting with the PostgreSQL database for playlists and songs.
 * Handles table creation, playlist insertion, and bulk song insertion.
 * 
 * @author Amazon Music Scraper Team
 * @since 1.0
 */
@SuppressWarnings({"SqlResolve", "unused"})
public class PostgresService implements PostgresServiceInterface {
    private static final Logger logger = LoggerFactory.getLogger(PostgresService.class);
    private final String url;
    private final String user;
    private final String password;

    /**
     * Constructs a PostgresService with the given connection parameters.
     * @param url JDBC URL
     * @param user Database user
     * @param password Database password
     */
    public PostgresService(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /**
     * Opens a new database connection.
     * @return Connection
     * @throws SQLException if connection fails
     */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Ensures the playlists and songs tables exist in the database.
     */
    public void createTables() {
        String playlistTable = "CREATE TABLE IF NOT EXISTS playlists (" +
                "id SERIAL PRIMARY KEY, " +
                "name TEXT, " +
                "url TEXT UNIQUE" +
                ")";
        String songTable = "CREATE TABLE IF NOT EXISTS songs (" +
                "id SERIAL PRIMARY KEY, " +
                "playlist_id INTEGER REFERENCES playlists(id), " +
                "title TEXT, artist TEXT, album TEXT, url TEXT, duration TEXT, " +
                "track_number INTEGER, playlist_position INTEGER, explicit BOOLEAN, " +
                "image_url TEXT, release_date TEXT, genre TEXT, " +
                "track_asin TEXT, validated BOOLEAN, confidence_score DOUBLE PRECISION, source_details JSONB" +
                ")";
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(playlistTable);
            stmt.execute(songTable);
            logger.info("Ensured playlists and songs tables exist.");
        } catch (SQLException e) {
            logger.error("Error creating tables: {}", e.getMessage());
        }
    }

    /**
     * Inserts or updates a playlist and returns its ID.
     * @param name Playlist name
     * @param url Playlist URL
     * @return Playlist ID, or -1 if failed
     */
    public int insertPlaylist(String name, String url) {
        if (name == null || name.trim().isEmpty() || url == null || url.trim().isEmpty()) {
            logger.warn("Invalid playlist name or URL for DB insert: name={}, url={}", name, url);
            return -1;
        }
        String sql = "INSERT INTO playlists (name, url) VALUES (?, ?) ON CONFLICT (url) DO UPDATE SET name = EXCLUDED.name RETURNING id";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, url);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int id = rs.getInt(1);
                logger.info("Inserted/updated playlist '{}' (id={})", name, id);
                return id;
            }
        } catch (SQLException e) {
            logger.error("Error inserting playlist: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Inserts a list of songs for a given playlist ID.
     * @param playlistId Playlist ID
     * @param songs List of Song records
     */
    public void insertSongs(int playlistId, List<Song> songs) {
        if (playlistId <= 0 || songs == null || songs.isEmpty()) {
            logger.warn("Invalid playlistId or empty song list for DB insert: playlistId={}, songs={}", playlistId, songs == null ? null : songs.size());
            return;
        }
        String sql = "INSERT INTO songs (playlist_id, title, artist, album, url, duration, track_number, playlist_position, explicit, image_url, release_date, genre, track_asin, validated, confidence_score, source_details) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Song song : songs) {
                ps.setInt(1, playlistId);
                ps.setString(2, song.title());
                ps.setString(3, song.artist());
                ps.setString(4, song.album());
                ps.setString(5, song.url());
                ps.setString(6, song.duration());
                if (song.trackNumber() != null) ps.setInt(7, song.trackNumber()); else ps.setNull(7, Types.INTEGER);
                if (song.playlistPosition() != null) ps.setInt(8, song.playlistPosition()); else ps.setNull(8, Types.INTEGER);
                if (song.explicit() != null) ps.setBoolean(9, song.explicit()); else ps.setNull(9, Types.BOOLEAN);
                ps.setString(10, song.imageUrl());
                ps.setString(11, song.releaseDate());
                ps.setString(12, song.genre());
                ps.setString(13, song.trackAsin());
                ps.setBoolean(14, song.validated());
                ps.setDouble(15, song.confidenceScore());
                ps.setObject(16, song.sourceDetails() == null ? null : new ObjectMapper().writeValueAsString(song.sourceDetails()), java.sql.Types.OTHER);
                ps.addBatch();
            }
            ps.executeBatch();
            logger.info("Inserted {} songs for playlist {}.", songs.size(), playlistId);
        } catch (SQLException e) {
            logger.error("Error inserting songs: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Error processing song data: {}", e.getMessage());
        }
    }

    /**
     * Starts an embedded PostgreSQL instance for local use and returns it.
     * @param dataDir directory under which to store DB data
     * @return EmbeddedPostgres instance
     */
    public static EmbeddedPostgres startEmbedded(String dataDir) {
        return startEmbedded(dataDir, 5432);
    }

    /**
     * Starts an embedded PostgreSQL instance on a specific port for local use and returns it.
     * @param dataDir directory under which to store DB data
     * @param port port number for the Postgres server
     * @return EmbeddedPostgres instance
     */
    public static EmbeddedPostgres startEmbedded(String dataDir, int port) {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setDataDirectory(Paths.get(dataDir))
                .setCleanDataDirectory(false)
                .setPort(port)
                .start();
            logger.info("Embedded PostgreSQL started at {} on port {}", dataDir, port);
            return postgres;
        } catch (Exception e) {
            logger.error("Failed to start embedded PostgreSQL on port {}: {}", port, e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
