package com.amazonmusic.scraper;

import java.io.IOException;
import java.util.List;

public interface CsvServiceInterface {
    void writeSongsToCSV(List<Song> songs, String filename) throws IOException;
}

