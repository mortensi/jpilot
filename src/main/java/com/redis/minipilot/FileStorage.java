package com.redis.minipilot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class FileStorage {

    @Value("${minipilot.assets}")
    private String uploadFolder;
    
    public void init() {
        File uploadDir = new File(uploadFolder);
        if (!uploadDir.exists()) {
            boolean created = uploadDir.mkdirs();
            if (created) {
                System.out.println("Upload directory created at: " + uploadFolder);
            } else {
                System.out.println("Failed to create upload directory!");
            }
        } else {
            System.out.println("Upload directory already exists at: " + uploadFolder);
        }
    }
}