package com.redis.minipilot.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class FileProcessingUtils {
	
    @Autowired
    private CsvLoaderTask csvLoaderTask;

    @Async
    public CompletableFuture<Void> processFileAsync(String filename) {
        // Implement the file processing logic here
    	csvLoaderTask.load(filename);
        return CompletableFuture.completedFuture(null);
    }

}

