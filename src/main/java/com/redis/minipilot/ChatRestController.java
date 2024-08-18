package com.redis.minipilot;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static dev.langchain4j.data.message.UserMessage.userMessage;
import static dev.langchain4j.data.message.AiMessage.aiMessage;

import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;


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
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.store.memory.chat.redis.RedisChatMemoryStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@RestController
public class ChatRestController {

	
    //@RequestMapping(value = "/ask", method = {RequestMethod.POST})
	@PostMapping(value = "/ask", produces = "text/plain")
    @ResponseBody
    public ResponseBodyEmitter ask(@RequestParam(value = "q") String q, HttpServletRequest request, HttpServletResponse response) {
		response.setContentType("text/plain");
        String decodedQ = java.net.URLDecoder.decode(q, java.nio.charset.StandardCharsets.UTF_8);        
        
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
                .port(6379)
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
        
        RedisChatMemoryStore store = RedisChatMemoryStore.builder().host("localhost").port(6379).build();
        
        //https://github.com/langchain4j/langchain4j-examples/blob/main/other-examples/src/main/java/ServiceWithPersistentMemoryForEachUserExample.java
        ChatMemoryProvider chatMemoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();
        
        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .retrievalAugmentor(retrievalAugmentor)
                .build();
        
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        
        
        try {
            TokenStream tokenStream = assistant.chat("minipilot:memory:" + request.getSession().getId(), q);
            StringBuilder chunks = new StringBuilder();
            tokenStream.onNext(responseData -> {
				try {
					emitter.send(responseData);
					chunks.append(responseData);
				} catch (IOException e) {
					e.printStackTrace();
				}
			})
            	.onComplete(responseData -> {
            		emitter.complete();
            		
            		// Limitation here https://docs.langchain4j.dev/tutorials/chat-memory/
            		// LangChain4j currently offers only "memory", not "history". If you need to keep an entire history, please do so manually.
            		// The memory comes with RAG context and without a separated field for the question, let's manage ourselves
            		String finalAnswer = chunks.toString();
            		chatMemoryProvider.get("minipilot:history:" + request.getSession().getId()).add(userMessage(q));
            		chatMemoryProvider.get("minipilot:history:" + request.getSession().getId()).add(aiMessage(finalAnswer));
            		
            		
            	})
            	.onError(emitter::completeWithError)  
            	.start();  // Start the streaming process

        } catch (Exception e) {
            emitter.completeWithError(e);  
        }
        
        return emitter;
	}
    
    
    interface Assistant {

    	TokenStream chat(@MemoryId String memoryId, @UserMessage String userMessage);
    }
    
    
    interface Assistant2 {

        TokenStream chat(String message);
    }
    
    
	@PostMapping("/reset")
	public ResponseEntity reset(Model model, HttpServletRequest request) {
		
        RedisChatMemoryStore store = RedisChatMemoryStore.builder().host("localhost").port(6379).build();
        System.out.println(store.getMessages("minipilot:memory:" + request.getSession().getId()));
		
        store.deleteMessages("minipilot:memory:" + request.getSession().getId());
        store.deleteMessages("minipilot:history:" + request.getSession().getId());
        
        // Return a JSON response
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");

        return ResponseEntity.ok(response);
	}
	
	
	@GetMapping("/ask")
	@ResponseBody
	public String askGet() {
	    return "This endpoint only supports POST requests.";
	}
    
	
	/*
	// https://github.com/langchain4j/langchain4j/blob/main/langchain4j-redis/src/main/java/dev/langchain4j/store/memory/chat/redis/RedisChatMemoryStore.java
    static class PersistentChatMemoryStore implements ChatMemoryStore {

        private final DB db = DBMaker.fileDB("multi-user-chat-memory.db").transactionEnable().make();
        private final Map<Integer, String> map = db.hashMap("messages", INTEGER, STRING).createOrOpen();

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            String json = map.get((int) memoryId);
            return messagesFromJson(json);
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            String json = messagesToJson(messages);
            map.put((int) memoryId, json);
            db.commit();
        }

        @Override
        public void deleteMessages(Object memoryId) {
            map.remove((int) memoryId);
            db.commit();
        }
    }
	*/
}
