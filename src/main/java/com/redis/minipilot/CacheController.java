package com.redis.minipilot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

import com.redis.minipilot.core.SemanticCache;

import jakarta.servlet.http.HttpServletRequest;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;


@Controller
public class CacheController {

	
    private final JedisPooled jedisPooled;
    
    private final SemanticCache cache;
    
    @Autowired
    public CacheController(JedisPooled jedisPooled, SemanticCache semanticCache) {
		this.jedisPooled = jedisPooled;
		this.cache = semanticCache;

    }
    
    
    @GetMapping("/cache") 
    public String cache(
        @RequestParam(name = "q",  defaultValue = "", required = false) String query,  
        @RequestParam(name = "s", defaultValue = "fulltext", required = false) String searchType,  
        Model model) { 
    	
    	List<HashMap<String, String>> entries = new ArrayList<>();
    	Query q = null;
    	List<Document> res = null;
    	
    	if (searchType.contentEquals("fulltext")) {
        	if (query.contentEquals("")) {
        		q = new Query();
        	}
        	else {
        		q = new Query(String.format("@question:(%s)", query));
        	}
        	
    		String[] fields = {"answer", "question"};
    		q.returnFields(fields);
    		q.limit(0, 100);
    		res = jedisPooled.ftSearch("minipilot_cache_idx", q).getDocuments();
    	}
    	else {
        	if (query.contentEquals("")) {
        		return "redirect:/cache"; 
        	}
    		res = cache.semanticSearch(query, 100);
    	}
    	
		for (Document document : res) {
	        HashMap<String, String> entry = new HashMap<>();
        	String[] parts = document.getId().split(":");
        	entry.put("id", parts[parts.length - 1]);
	        entry.put("question", document.get("question").toString());
	        entry.put("answer", document.get("answer").toString());
	        entries.add(entry);
		}

    	
    	model.addAttribute("q", query);
    	model.addAttribute("s", searchType);
    	model.addAttribute("entries", entries);
		return "cache";
	}
    
    
    @GetMapping("/cache/delete/{id}")
    public String deleteCache(@PathVariable("id") String id) {
    	jedisPooled.del(String.format("minipilot:cache:%s", id));
    	return "redirect:/cache"; 
	}
    
    
    @PostMapping("/cache/save")
    public RedirectView saveCache(	@RequestParam(name = "id", required = false) String id, 
    								@RequestParam(name = "content", required = false) String content, 
    								HttpServletRequest request) {
    	if (id != null) {    	
    		jedisPooled.jsonSetWithEscape(String.format("minipilot:cache:%s", id), Path2.of("$.answer"), content);
    	
    	}
    	return new RedirectView(request.getHeader("Referer"));
    }
}
