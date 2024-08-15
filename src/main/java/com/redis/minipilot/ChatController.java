package com.redis.minipilot;

import java.io.IOException;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

import java.util.function.Consumer;


@Controller
public class ChatController {
	
	@GetMapping("/")
	public String chat(@RequestParam(name="name", required=false, defaultValue="chat") String name, Model model) {
		model.addAttribute("name", name);
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

    @PostMapping("/ask")
    @ResponseBody
    public ResponseBodyEmitter ask(@RequestParam("q") String q, HttpServletRequest request) {
        String decodedQ = java.net.URLDecoder.decode(q, java.nio.charset.StandardCharsets.UTF_8);
        
        // Handle the data and perform necessary actions
        // For example, print the received data to the console
        System.out.println("Received data: " + decodedQ);
        
        
        // https://github.com/langchain4j/langchain4j-examples/blob/69bc2bfb1d7b6c539c3a176912bc106dba6d5a75/other-examples/src/main/java/ChatWithDocumentsExamples.java
        ChatLanguageModel chatLanguageModel = OpenAiChatModel.withApiKey(System.getenv("OPENAI_API_KEY"));
        
        StreamingChatLanguageModel streamingChatLanguageModel = OpenAiStreamingChatModel.withApiKey(System.getenv("OPENAI_API_KEY"));
        //String answer = chatLanguageModel.generate(decodedQ);
        
        // https://github.com/langchain4j/langchain4j-examples/blob/main/rag-examples/src/main/java/_3_advanced/_01_Advanced_RAG_with_Query_Compression_Example.java
        QueryTransformer queryTransformer = new CompressingQueryTransformer(chatLanguageModel);
        
        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("text-embedding-ada-002")
                .build();
        
        EmbeddingStore<TextSegment> embeddingStore = RedisEmbeddingStore.builder()
                .host("localhost")
                .user("default")
                .port(6399)
                .indexName("minipilot_rag_alias")
                .dimension(1536)
                //.metadataFieldsName(metadata)
                .build();
        
        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(2)
                .minScore(0.6)
                .build();
        
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .contentRetriever(contentRetriever)
                .build();
        
        RedisChatMemoryStore store = RedisChatMemoryStore.builder().host("localhost").port(6399).build();
        
        //https://github.com/langchain4j/langchain4j-examples/blob/main/other-examples/src/main/java/ServiceWithPersistentMemoryForEachUserExample.java
        ChatMemoryProvider chatMemoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(request.getSession().getId())
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();
        
        Assistant2 assistant = AiServices.builder(Assistant2.class)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .retrievalAugmentor(retrievalAugmentor)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();
        
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        
        try {
            TokenStream tokenStream = assistant.chat(q);

            tokenStream.onNext(response -> {
				try {
					emitter.send(response);
				} catch (IOException e) {
					e.printStackTrace();
				}
			})
            	.onComplete(response -> emitter.complete()) 
            	.onError(emitter::completeWithError)  
            	.start();  // Start the streaming process

        } catch (Exception e) {
            emitter.completeWithError(e);  
        }
        
        
        System.out.println(request.getSession().getId());
        
        return emitter;
	}
    
    
    public interface Assistant {

        String answer(String query);
    }
    
    
    interface Assistant2 {

        TokenStream chat(String message);
    }
    
	
	@GetMapping("/reset")
	public String reset(@RequestParam(name="name", required=false, defaultValue="prompt") String name, Model model) {
		model.addAttribute("name", name);
		return "reset";
	}
}
