package com.redis.jpilot.controllers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import com.redis.jpilot.JpilotApplication;
import com.redis.jpilot.core.CsvLoaderTask;
import com.redis.jpilot.core.FileProcessingUtils;

import jakarta.servlet.http.HttpServletRequest;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;


@Controller
public class DataController {
    
    @Autowired
    private CsvLoaderTask csvLoaderTask;
    
    @Autowired 
    private FileProcessingUtils fileProcessingUtils;
    
    private final JedisPooled jedisPooled;
    
    private static final Logger logger = LoggerFactory.getLogger(JpilotApplication.class);
    
    @Autowired
    public DataController(JedisPooled jedisPooled) {
        this.jedisPooled = jedisPooled;
    }
	
	
	@GetMapping("/data")
	public String data(@RequestParam(name="name", required=false, defaultValue="data") String name, Model model) {			
		// Check the CSV files
		Query q = new Query("*");
		q.returnFields("filename");
		q.setSortBy("uploaded", false);
		q.limit(0, 50);
		
		List<Document> docs = jedisPooled.ftSearch("jpilot_data_idx", q).getDocuments();
		
        for (Document doc : docs) {
        	String[] parts = doc.getId().split(":");
        	doc.set("id", parts[parts.length - 1]);
        }
        
        
        // Check the indexes
        List<Map<String, Object>> idxOverview = new ArrayList<>();
        Map<String, Object> idxAliasInfo = null;
        
        try {
        	idxAliasInfo = jedisPooled.ftInfo("jpilot_rag_alias");
        }
		catch (JedisDataException e) {
			System.out.println("The jpilot_data_idx alias does not exist");
		}
        
        Set<String> indexes = jedisPooled.ftList(); // Adjust this according to how you retrieve this list

        // Filter for indexes starting with "jpilot_rag"
        List<String> ragIndexes = indexes.stream()
            .filter(idx -> idx.startsWith("jpilot_rag"))
            .collect(Collectors.toList());

        // Retrieve information for each filtered index
        for (String idx : ragIndexes) {
            // Retrieve index info
            Map<String, Object> idxInfo = jedisPooled.ftInfo(idx); // Adjust this according to how you retrieve index info
            Map<String, Object> tmp = new HashMap<>();
            tmp.put("name", idxInfo.get("index_name"));
            tmp.put("docs", String.valueOf(idxInfo.get("num_docs")));
            tmp.put("is_current", false);

            // Check to what index the alias is pointing
            if (idxAliasInfo != null) {
                if (idxInfo.get("index_name").equals(idxAliasInfo.get("index_name"))) {
                    tmp.put("is_current", true);
                }
            }

            idxOverview.add(tmp);
        }
        
        model.addAttribute("data", docs);
        model.addAttribute("idx_overview", idxOverview);
		return "data";
	}
	
	
    @PostMapping("/data/upload")
    public String handleFileUpload(@RequestParam("asset") MultipartFile file,
                                   RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            System.out.println("No selected file");
            logger.warn("No selected file");
            return "redirect:/data";
        }

        
        try {
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isEmpty()) {
                System.out.println("No selected file");
                logger.warn("No selected file");
                return "redirect:/data";
            }

            String path = Paths.get(System.getenv("JPILOT_ASSETS"), filename).toString();
            File destFile = new File(path);

            if (destFile.exists()) {
                System.out.println("The file already exists, overwriting");
            }

            // Only CSV accepted as of now
            if (file.getContentType().equals("text/csv")) {
                Files.copy(file.getInputStream(), Paths.get(System.getenv("JPILOT_ASSETS"), filename), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("File successfully uploaded");

                // Here you would add logic to save the file metadata to your database
                String key = "jpilot:data:" + UUID.randomUUID().toString().replace("-", "");
                
                jedisPooled.hset(key, "filename", filename);
                jedisPooled.hset(key, "uploaded", String.valueOf(Instant.now().getEpochSecond()));

                System.out.println("File metadata saved to database");
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "redirect:/data";
        }

        return "redirect:/data";
    }
    
    
    @GetMapping("/data/create/{id}")
    public RedirectView createIndex(@PathVariable("id") String id, HttpServletRequest request, RedirectAttributes redirectAttributes) {
        // Get filename from database
        String filename = jedisPooled.hget("jpilot:data:" + id, "filename");

        // Construct file path
        String uploadFolder = System.getenv("JPILOT_ASSETS"); // Adjust if needed
        String path = Paths.get(uploadFolder, filename).toString();
        
        // Use the utility method to process the file asynchronously
        fileProcessingUtils.processFileAsync(path);

        // Or use the utility method to process the file synchronously, for debugging
        //csvLoaderTask.load(path);
        
        // Wait for a second so the async job starts and on refresh you can see the index being created
        // Then refresh the page from time to time to see the progress. TODO update the GUI with the progress
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Redirect to the data page
        return new RedirectView("/data");
    }
    
    
    
    @GetMapping("/data/remove/{id}")
    public RedirectView removeFile(@PathVariable("id") String id, HttpServletRequest request, RedirectAttributes redirectAttributes) {
        String filename = jedisPooled.hget("jpilot:data:" + id, "filename");

        // Construct the file path
        String uploadFolder = System.getenv("JPILOT_ASSETS");
        File file = new File(uploadFolder + File.separator + filename);

        // Delete the file
        if (file.exists()) {
            file.delete();
        }

        // Remove the entry from the database
        jedisPooled.del("jpilot:data:" + id);

        // Redirect to the data page
        return new RedirectView("/data", true);
    }
    
    
    @GetMapping("/data/delete/{name}")
    public RedirectView deleteIndex(@PathVariable("name") String name, HttpServletRequest request, RedirectAttributes redirectAttributes) {
    	jedisPooled.ftDropIndexDD(name);


        // Redirect to the data page
        return new RedirectView("/data", true);
    }
    
    
    @GetMapping("/data/current/{name}")
    public RedirectView currentIndex(@PathVariable("name") String name, HttpServletRequest request, RedirectAttributes redirectAttributes) {
    	jedisPooled.ftAliasUpdate("jpilot_rag_alias", name);

        // Redirect to the data page
        return new RedirectView("/data", true);
    }

}
