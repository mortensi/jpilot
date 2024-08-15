package com.redis.minipilot.core;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class FileProcessingUtils {

    @Async
    public CompletableFuture<Void> processFileAsync(String filename) {
        // Implement the file processing logic here
    	CsvLoaderTask.csvLoaderTask(filename);
        return CompletableFuture.completedFuture(null);
    }

}

