package com.redis.minipilot;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.view.RedirectView;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.minipilot.core.SystemPromptDefault;
import com.redis.minipilot.core.UserPromptDefault;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.store.memory.chat.redis.RedisChatMemoryStore;
import jakarta.servlet.http.HttpServletRequest;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


@Controller
public class WebController {
	
    @Value("${redis.host}")
    private String host;

    @Value("${redis.port}")
    private int port;
    
    @Value("${redis.password}")
    private String password;
    
    @Autowired
    private final JedisPooled jedisPooled;
    
    @Autowired
    public WebController() {
		this.jedisPooled = new JedisPooled();

    }
	
	@GetMapping("/")
	public String chat(@RequestParam(required=false, defaultValue="chat") String name, Model model, HttpServletRequest request) {
        RedisChatMemoryStore store = RedisChatMemoryStore.builder()
        		.host(host)
        		.port(port)
        		.password(password)
        		.build();

        
        List<ChatMessage> messages = store.getMessages("minipilot:history:" + request.getSession().getId());
        
        ArrayList<Map<String, String>> messageList = new ArrayList<>();
        
        for (ChatMessage msg : messages) {
            Map<String, String> message = new HashMap<>();
            
            if (msg.getClass().getSimpleName().contentEquals("UserMessage")) {
                message.put("type", msg.getClass().getSimpleName());
                message.put("content", msg.text());
            }
            if (msg.getClass().getSimpleName().contentEquals("AiMessage")) {
                message.put("type", msg.getClass().getSimpleName());
                message.put("content", msg.text());
            }
            
            messageList.add(message);
        }
        
        model.addAttribute("conversation", messageList);
		return "chat";
	}
	
	
    @GetMapping("/cache") 
    public String cache(
        @RequestParam(name = "q", required = false) String query,  
        @RequestParam(name = "s", defaultValue = "semantic", required = false) String searchType,  
        Model model) { 
    	
    	Query q = null;
    	
    	if (query == null || query.contentEquals("")) {
    		q = new Query();
    	}
    	else {
    		q = new Query(String.format("@question:(%s)", query));
    	}
    	
		String[] fields = {"answer", "question"};
		q.returnFields(fields);
		q.limit(0, 100);
		List<Document> res = jedisPooled.ftSearch("minipilot_cache_idx", q).getDocuments();
		List<HashMap<String, String>> entries = new ArrayList<>();
		
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
	
	
	@GetMapping("/logger")
	public String logger(@RequestParam(name="name", required=false, defaultValue="logger") String name, Model model) {
		model.addAttribute("name", name);
		return "logger";
	}
	
	
	@GetMapping("/prompt")
	public String prompt(@RequestParam(name="name", required=false, defaultValue="prompt") String name, Model model) {
		model.addAttribute("system", jedisPooled.get("minipilot:prompt:system"));
		model.addAttribute("user", jedisPooled.get("minipilot:prompt:user"));
		return "prompt";
	}
	
    @PostMapping("/prompt/save")
    public String savePrompt(@RequestParam("prompt") String prompt, @RequestParam("type") String type) {
        if ("system".equals(type)) {
        	jedisPooled.set("minipilot:prompt:system", prompt);
        } else if ("user".equals(type)) {
        	jedisPooled.set("minipilot:prompt:user", prompt);
        }

        return "redirect:/prompt";  
    }
	
	
    //@RequestMapping("/error")
    //public String handleError() {
    //    // Log or customize the error page
    //    return "error";
    //}

}
