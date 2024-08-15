package com.redis.minipilot;

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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import com.redis.minipilot.core.CsvLoaderTask;
import com.redis.minipilot.core.FileProcessingUtils;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.servlet.http.HttpServletRequest;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;


@Controller
public class DataController {
    @Autowired
    private Environment env;
    
    @Autowired
    private FileProcessingUtils fileProcessingUtils;
	
	
	@GetMapping("/data")
	public String data(@RequestParam(name="name", required=false, defaultValue="data") String name, Model model) {			
		UnifiedJedis unifiedjedis = new UnifiedJedis(new HostAndPort("localhost", 6379));
		
		// Check the CSV files
		Query q = new Query("*");
		q.returnFields("filename");
		q.setSortBy("uploaded", false);
		q.limit(0, 50);
		
		List<Document> docs = unifiedjedis.ftSearch("minipilot_data_idx", q).getDocuments();
		
        for (Document doc : docs) {
        	String[] parts = doc.getId().split(":");
        	doc.set("id", parts[parts.length - 1]);
        }
        
        
        // Check the indexes
        List<Map<String, Object>> idxOverview = new ArrayList<>();
        Map<String, Object> idxAliasInfo = null;
        
        try {
        	idxAliasInfo = unifiedjedis.ftInfo("minipilot_rag_alias");
        }
		catch (JedisDataException e) {
			System.out.println("The minipilot_data_idx alias does not exist");
		}
        
        Set<String> indexes = unifiedjedis.ftList(); // Adjust this according to how you retrieve this list

        // Filter for indexes starting with "minipilot_rag"
        List<String> ragIndexes = indexes.stream()
            .filter(idx -> idx.startsWith("minipilot_rag"))
            .collect(Collectors.toList());

        // Retrieve information for each filtered index
        for (String idx : ragIndexes) {
            // Retrieve index info
            Map<String, Object> idxInfo = unifiedjedis.ftInfo(idx); // Adjust this according to how you retrieve index info
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
            return "redirect:/data";
        }

        
        try {
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isEmpty()) {
                System.out.println("No selected file");
                return "redirect:/data";
            }

            String path = Paths.get(System.getenv("MINIPILOT_ASSETS"), filename).toString();
            File destFile = new File(path);

            if (destFile.exists()) {
                System.out.println("The file already exists, overwriting");
            }

            // Only CSV accepted as of now
            if (file.getContentType().equals("text/csv")) {
                Files.copy(file.getInputStream(), Paths.get(System.getenv("MINIPILOT_ASSETS"), filename), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("File successfully uploaded");

                // Here you would add logic to save the file metadata to your database
                String key = "minipilot:data:" + UUID.randomUUID().toString().replace("-", "");
                
                UnifiedJedis unifiedjedis = new UnifiedJedis(new HostAndPort("localhost", 6379));
                unifiedjedis.hset(key, "filename", filename);
                unifiedjedis.hset(key, "uploaded", String.valueOf(Instant.now().getEpochSecond()));

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
    	UnifiedJedis unifiedjedis = new UnifiedJedis(new HostAndPort("localhost", 6379));
        String filename = unifiedjedis.hget("minipilot:data:" + id, "filename");
        
        System.out.println(filename);

        // Construct file path
        String uploadFolder = System.getenv("MINIPILOT_ASSETS"); // Adjust if needed
        String path = Paths.get(uploadFolder, filename).toString();

        // Start background task to handle file processing
        //processFileAsync(path);
        
        // Use the utility method to process the file asynchronously
        //fileProcessingUtils.processFileAsync(path);

        // Or use the utility method to process the file synchronously, for debugging
        CsvLoaderTask.csvLoaderTask(path);
        
        // Wait for a second to simulate the original behavior (not recommended in production)
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
    	UnifiedJedis unifiedjedis = new UnifiedJedis(new HostAndPort("localhost", 6379));
        String filename = unifiedjedis.hget("minipilot:data:" + id, "filename");

        // Construct the file path
        String uploadFolder = System.getenv("MINIPILOT_ASSETS");
        File file = new File(uploadFolder + File.separator + filename);

        // Delete the file
        if (file.exists()) {
            file.delete();
        }

        // Remove the entry from the database
        unifiedjedis.del("minipilot:data:" + id);

        // Redirect to the data page
        return new RedirectView("/data", true);
    }
    
    
    @GetMapping("/data/delete/{name}")
    public RedirectView deleteIndex(@PathVariable("name") String name, HttpServletRequest request, RedirectAttributes redirectAttributes) {
    	UnifiedJedis unifiedjedis = new UnifiedJedis(new HostAndPort("localhost", 6379));
        
    	unifiedjedis.ftDropIndexDD(name);


        // Redirect to the data page
        return new RedirectView("/data", true);
    }
    
    
    @GetMapping("/data/current/{name}")
    public RedirectView currentIndex(@PathVariable("name") String name, HttpServletRequest request, RedirectAttributes redirectAttributes) {
    	UnifiedJedis unifiedjedis = new UnifiedJedis(new HostAndPort("localhost", 6379));
    	
    	unifiedjedis.ftAliasUpdate("minipilot_rag_alias", name);

        // Redirect to the data page
        return new RedirectView("/data", true);
    }

}
