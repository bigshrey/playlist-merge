import com.amazonmusic.scraper.CsvService;
import com.amazonmusic.scraper.Song;
import com.amazonmusic.scraper.Utils;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

public class MainTest {
    @Test
    public void testSanitizeFilename() {
        String input = "my:invalid/file*name?.csv";
        String expected = "my_invalid_file_name_.csv";
        String actual = Utils.sanitizeFilename(input);
        Assertions.assertEquals(expected, actual);
    }
    @Test
    public void testWriteSongsToCSV() throws IOException, CsvException {
        List<Song> songs = Arrays.asList(
            new Song("Title1", "Artist1", "Album1", "URL1", null, null, null, null, null, null, null, null, false, 1.0, java.util.Map.of()),
            new Song("Title2", "Artist2", "Album2", "URL2", null, null, null, null, null, null, null, null, false, 1.0, java.util.Map.of())
        );
        String filename = "test_songs.csv";
        CsvService csvService = new CsvService();
        csvService.writeSongsToCSV(songs, filename);

        try (CSVReader reader = new CSVReader(new FileReader(filename))) {
            List<String[]> lines = reader.readAll();
            Assertions.assertArrayEquals(new String[]{"Title", "Artist", "Album", "URL"}, lines.get(0));
            Assertions.assertArrayEquals(new String[]{"Title1", "Artist1", "Album1", "URL1"}, lines.get(1));
            Assertions.assertArrayEquals(new String[]{"Title2", "Artist2", "Album2", "URL2"}, lines.get(2));
        }

        Files.deleteIfExists(Path.of(filename));
    }
    @Test
    public void testRetryPlaywrightActionSuccessAfterRetries() {
        final int[] attempts = {0};
        Callable<String> action = () -> {
            if (attempts[0] < 2) {
                attempts[0]++;
                throw new IOException("Simulated failure");
            }
            return "Success";
        };
        String result = Utils.retryPlaywrightAction(action, 3, "test action");
        Assertions.assertEquals("Success", result);
        Assertions.assertEquals(2, attempts[0]);
    }

    @Test
    public void testRetryPlaywrightActionFailure() {
        Callable<String> action = () -> {
            throw new IOException("Always fails");
        };
        String result = Utils.retryPlaywrightAction(action, 2, "test always fail");
        Assertions.assertNull(result);
    }
}
