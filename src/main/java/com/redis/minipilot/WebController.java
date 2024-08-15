package com.redis.minipilot;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;


@Controller
public class WebController {
	
	@GetMapping("/")
	public String chat(@RequestParam(required=false, defaultValue="chat") String name, Model model, HttpServletRequest request) {
		
        RedisChatMemoryStore store = RedisChatMemoryStore.builder().host("localhost").port(6379).build();
        System.out.println(store.getMessages("minipilot:history:" + request.getSession().getId()).getClass().getName());
        
        
        List<String> myList = new ArrayList<>();
        myList.add("Item 1");
        myList.add("Item 2");
        myList.add("Item 3");
        model.addAttribute("history", myList);
        
        //model.addAttribute("history", store.getMessages("minipilot:history:" + request.getSession().getId()));
		return "chat";
	}
	
	
	@GetMapping("/cache")
	public String cache(@RequestParam(name="name", required=false, defaultValue="cache") String name, Model model) {
		model.addAttribute("name", name);
		return "cache";
	}
	
	
	@GetMapping("/logger")
	public String logger(@RequestParam(name="name", required=false, defaultValue="logger") String name, Model model) {
		model.addAttribute("name", name);
		return "logger";
	}
	
	
	@GetMapping("/prompt")
	public String prompt(@RequestParam(name="name", required=false, defaultValue="prompt") String name, Model model) {
		model.addAttribute("name", name);
		return "prompt";
	}
	
	
    //@RequestMapping("/error")
    //public String handleError() {
    //    // Log or customize the error page
    //    return "error";
    //}

}
