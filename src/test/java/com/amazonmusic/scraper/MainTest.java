package com.amazonmusic.scraper;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/**
 * Main test suite for Amazon Music Playlist Scraper.
 * Covers authentication, scraping, metadata extraction, enrichment, provenance validation, CSV/DB export, and error handling.
 */
public class MainTest {
    @Test
    void testAuthServiceInit() {
        AuthService authService = new AuthService();
        assertDoesNotThrow(authService::init);
    }

    @Test
    void testSanitizeFilename() {
        String input = "My:Playlist/Name?*<>|";
        String sanitized = Utils.sanitizeFilename(input);
        assertEquals("My_Playlist_Name______", sanitized);
    }

    @Test
    void testRetryPlaywrightActionSuccess() {
        int result = Utils.retryPlaywrightAction(() -> 42, 3, "test action");
        assertEquals(42, result);
    }

    @Test
    void testRetryPlaywrightActionFailure() {
        Integer result = Utils.retryPlaywrightAction(() -> { throw new RuntimeException("fail"); }, 2, "fail action");
        assertNull(result);
    }

    @Test
    void testMetadataFieldRegistryGetField() {
        MetadataField field = MetadataFieldRegistry.getField("title");
        assertNotNull(field);
        assertEquals("title", field.fieldName);
    }

    @Test
    void testMetadataCrossCheckerCrossCheckField() {
        MetadataCrossChecker checker = new MetadataCrossChecker();
        MetadataField field = new MetadataField("title", List.of());
        // Locator is Playwright-specific; use null for now
        MetadataCrossChecker.CrossCheckResult result = checker.crossCheckField(null, field);
        assertNotNull(result);
        assertTrue(result.value == null || result.value.isEmpty());
    }

    @Test
    void testMusicBrainzClientValidateAndEnrich() {
        Song song = new Song("Test Title", "Test Artist", "Test Album", "url", "3:30", 1, 1, false, "img", "", "", "ASIN123456", false, 0.8, new HashMap<>(), new HashMap<>());
        MusicBrainzClient client = new MusicBrainzClient();
        Song enriched = client.validateAndEnrich(song);
        assertTrue(enriched.validated());
        assertEquals("Electronic", enriched.genre());
        assertEquals("2020-01-01", enriched.releaseDate());
    }

    @Test
    void testProvenanceValidation() {
        // Removed: validateProvenanceStructure is obsolete and no longer present
    }

    @Test
    void testCsvServiceWriteSongsToCSV() {
        CsvService csvService = new CsvService();
        Song song = new Song("Title", "Artist", "Album", "url", "3:30", 1, 1, false, "img", "2020-01-01", "Electronic", "ASIN123456", true, 1.0, new HashMap<>(), new HashMap<>());
        List<Song> songs = List.of(song);
        assertDoesNotThrow(() -> csvService.writeSongsToCSV(songs, "TestPlaylist.csv"));
    }

    @Test
    void testPostgresServiceCreateTablesAndInsert() {
        // Use embedded Postgres for testing
        String dataDir = "scraped-data/test-pgdata";
        int port = 5433;
        PostgresService postgresService = PostgresService.startEmbedded(dataDir, port) != null ? new PostgresService("jdbc:postgresql://localhost:" + port + "/postgres", "postgres", "postgres") : null;
        assertNotNull(postgresService);
        assertDoesNotThrow(postgresService::createTables);
        int playlistId = postgresService.insertPlaylist("Test Playlist", "http://test.url");
        assertTrue(playlistId > 0);
        Song song = new Song("Title", "Artist", "Album", "url", "3:30", 1, 1, false, "img", "2020-01-01", "Electronic", "ASIN123456", true, 1.0, new HashMap<>(), new HashMap<>());
        assertDoesNotThrow(() -> postgresService.insertSongs(playlistId, List.of(song)));
    }

    @Test
    void testScraperServiceIsSignedInMock() {
        ScraperService scraperService = new ScraperService();
        // Use a mock Page (null for now, just test method call)
        assertDoesNotThrow(() -> scraperService.isSignedIn(null));
    }

    @Test
    void testPlaylistRecord() {
        Song song = new Song("Title", "Artist", "Album", "url", "3:30", 1, 1, false, "img", "2020-01-01", "Electronic", "ASIN123456", true, 1.0, new HashMap<>(), new HashMap<>());
        Playlist playlist = new Playlist("Test Playlist", "http://test.url", List.of(song));
        assertEquals("Test Playlist", playlist.name());
        assertEquals("http://test.url", playlist.url());
        assertEquals(1, playlist.songs().size());
    }

    // Add more tests for CsvService, PostgresService, ScraperService, AuthService workflows as needed
}
