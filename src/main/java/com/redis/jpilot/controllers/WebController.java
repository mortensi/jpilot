package com.redis.jpilot.controllers;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.redis.RedisChatMemoryStore;
import jakarta.servlet.http.HttpServletRequest;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.resps.StreamEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Controller
public class WebController {
	
    @Value("${redis.host}")
    private String host;

    @Value("${redis.port}")
    private int port;
    
    @Value("${redis.password}")
    private String password;
    
    private final JedisPooled jedisPooled;
    
    @Autowired
    public WebController(JedisPooled jedisPooled) {
		this.jedisPooled = jedisPooled;

    }
	
	@GetMapping("/")
	public String chat(@RequestParam(required=false, defaultValue="chat") String name, Model model, HttpServletRequest request) {
        RedisChatMemoryStore store = RedisChatMemoryStore.builder()
        		.host(host)
        		.port(port)
        		.password(password)
        		.build();

        
        List<ChatMessage> messages = store.getMessages("jpilot:history:" + request.getSession().getId());
        
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
	
	
	@GetMapping("/logger")
	public String logger(Model model) {
		
		List<StreamEntry> entries = jedisPooled.xrange("jpilot:logging", "-", "+");
		List<String> logs = new ArrayList<>();
		
		for (StreamEntry entry : entries) {
			logs.add(entry.getFields().toString());
		}
		
		model.addAttribute("logs", logs);
		return "logger";
	}
	
	
    //@RequestMapping("/error")
    //public String handleError() {
    //    // Log or customize the error page
    //    return "error";
    //}

}
