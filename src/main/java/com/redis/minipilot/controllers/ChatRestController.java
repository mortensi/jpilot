package com.redis.minipilot.controllers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static dev.langchain4j.data.message.UserMessage.userMessage;
import static dev.langchain4j.data.message.AiMessage.aiMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import com.redis.minipilot.MinipilotApplication;
import com.redis.minipilot.core.SemanticCache;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
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
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.store.memory.chat.redis.RedisChatMemoryStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.aggr.AggregationBuilder;
import redis.clients.jedis.search.aggr.AggregationResult;
import redis.clients.jedis.search.aggr.Group;
import redis.clients.jedis.search.aggr.Reducers;


@RestController
public class ChatRestController {
    @Value("${redis.host}")
    private String host;

    @Value("${redis.port}")
    private int port;
    
    @Value("${redis.password}")
    private String password;
    
    @Value("${minipilot.conversation.length}")
    private long minipilotConversationLength;
    
    private final JedisPooled jedisPooled;
    private final SemanticCache cache;
    
    private static final Logger logger = LoggerFactory.getLogger(MinipilotApplication.class);
    
    @Autowired
    public ChatRestController(JedisPooled jedisPooled, SemanticCache semanticCache) {
		this.jedisPooled = jedisPooled;
		this.cache = semanticCache;

    }
	

	@PostMapping(value = "/ask", produces = "text/plain")
    @ResponseBody
    public ResponseBodyEmitter ask(@RequestParam(value = "q") String q, HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setContentType("text/plain");  
		ResponseBodyEmitter emitter = new ResponseBodyEmitter();
		long startTime = System.currentTimeMillis();
        
        // https://github.com/langchain4j/langchain4j-examples/blob/69bc2bfb1d7b6c539c3a176912bc106dba6d5a75/other-examples/src/main/java/ChatWithDocumentsExamples.java
        ChatLanguageModel chatLanguageModel = OpenAiChatModel.withApiKey(System.getenv("OPENAI_API_KEY"));
        
        OpenAiStreamingChatModel streamingChatLanguageModel = OpenAiStreamingChatModel.builder()
	        .apiKey(System.getenv("OPENAI_API_KEY"))
	        .logRequests(false)
	        .logResponses(false)
	        .modelName("gpt-4o")
	        .build();
        
        // https://github.com/langchain4j/langchain4j-examples/blob/main/rag-examples/src/main/java/_3_advanced/_01_Advanced_RAG_with_Query_Compression_Example.java
        QueryTransformer queryTransformer = new CompressingQueryTransformer(chatLanguageModel);
        
        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("text-embedding-ada-002")
                .logRequests(false)
                .logResponses(false)
                .build();
        
        // If I use an alias here, an index will be created with the alias name.
        // That's a bug in isIndexExist https://github.com/langchain4j/langchain4j/blob/main/langchain4j-redis/src/main/java/dev/langchain4j/store/embedding/redis/RedisEmbeddingStore.java#L52
        // Should use ftInfo rather than ftList
        // For now, just dereference the alias to use the pointed index
        // Check to what index the alias is pointing to
        String idx;
        try {
        	Map<String, Object> idxAliasInfo = jedisPooled.ftInfo("minipilot_rag_alias");
        	idx = (String) idxAliasInfo.get("index_name");
        }
		catch (JedisDataException e) {
			System.out.println("The minipilot_data_idx alias does not exist");
			logger.warn("The minipilot_data_idx alias does not exist");
			try {
				emitter.send("You must associate the alias to an index");
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			return emitter;
		}

        // Dimension for RedisEmbeddingStore should not be required if the index exists 
        // https://github.com/langchain4j/langchain4j/issues/1618
        EmbeddingStore<TextSegment> embeddingStore = RedisEmbeddingStore.builder()
                .host(host)
                .user("default")
                .port(port)
                .indexName(idx)
                .dimension(1536)
                .build();
        
        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.8)
                .build();
        
        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .contentRetriever(contentRetriever)
                .build();
        
        RedisChatMemoryStore store = RedisChatMemoryStore.builder()
        								.host(host)
        								.port(port)
        								.build();
        
        //https://github.com/langchain4j/langchain4j-examples/blob/main/other-examples/src/main/java/ServiceWithPersistentMemoryForEachUserExample.java
        ChatMemoryProvider chatMemoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();
        
		List<Document> docs = cache.isInCache(q);
		if (!docs.isEmpty()) {
			String cachedAnswer = (String) docs.get(0).get("$.answer");
			emitter.send(cachedAnswer);
			
			// even if cached, add to user conversation
    		chatMemoryProvider.get("minipilot:history:" + request.getSession().getId()).add(userMessage(q));
    		chatMemoryProvider.get("minipilot:history:" + request.getSession().getId()).add(aiMessage(cachedAnswer));
			return emitter;
		}
        
        Assistant2 assistant = AiServices.builder(Assistant2.class)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .retrievalAugmentor(retrievalAugmentor)
                //.tools(new RedisSearchTools(idx))
                .build();
                
        OpenAiChatModel functionRequiredModel = OpenAiChatModel.builder()
    	        .apiKey(System.getenv("OPENAI_API_KEY"))
    	        .logRequests(true)
    	        .logResponses(true)
    	        .modelName("gpt-4o")
    	        .build();
        
        //FunctionRequired functionRequired = AiServices.builder(FunctionRequired.class)
        //						.chatLanguageModel(model)
        //						.build();
        
        FunctionRequired functionRequired = AiServices.create(FunctionRequired.class, functionRequiredModel);
        
        Assistant2 obj = assistant;
        
        
        if (functionRequired.isFunctionRequired(q).toLowerCase().equals("true")) {
        	System.out.println("Tools are required");
        	
            Assistant2 funcAssistant = AiServices.builder(Assistant2.class)
                    .streamingChatLanguageModel(streamingChatLanguageModel)
                    .chatMemoryProvider(chatMemoryProvider)
                    .tools(new RedisSearchTools(idx))
                    .build();
            
            obj = funcAssistant;
        } 
        
       
        /*
        Object schema = readIndexSchema(idx);
        System.out.println(schema);
        OpenAiChatModel model = OpenAiChatModel.withApiKey(System.getenv("OPENAI_API_KEY"));
        String answer = model.generate(String.format("based on this Redis index name %s, this schema %s, and based on this question %s, you must answer strictly a redis-cli command, without additional information, to retrieve the data using FT.SEARCH or FT.AGGREGATE. Do not add any HTML or markdown formatting", 
        												idx,
        												schema,
        												q));
        
        System.out.println(answer);
        emitter.send(answer);
        
        if (true) {
        	return emitter;
        }
        */
        
        
        String systemPrompt = jedisPooled.get("minipilot:prompt:system");
        String userPrompt = jedisPooled.get("minipilot:prompt:user");
        
        try {
            TokenStream tokenStream = obj.chat(	"minipilot:memory:" + request.getSession().getId(), 
            											systemPrompt,
            											userPrompt,
									            		q);
            
            StringBuilder chunks = new StringBuilder();
            boolean firstTokenReceived = false;
            AtomicLong ttft = new AtomicLong();
            AtomicLong etfl = new AtomicLong();
            tokenStream.onNext(responseData -> {
				try {
					if (!firstTokenReceived) {
						ttft.set(System.currentTimeMillis() - startTime);
					}
					emitter.send(responseData);
					chunks.append(responseData);
				} catch (IOException e) {
					e.printStackTrace();
				}
			})
            	.onComplete(responseData -> {
            		emitter.complete();
            		etfl.set(System.currentTimeMillis() - startTime);
            		
            		// Limitation here https://docs.langchain4j.dev/tutorials/chat-memory/
            		// LangChain4j currently offers only "memory", not "history". If you need to keep an entire history, please do so manually.
            		// The memory comes with RAG context and without a separated field for the question, let's manage ourselves
            		String finalAnswer = chunks.toString();
            		chatMemoryProvider.get("minipilot:history:" + request.getSession().getId()).add(userMessage(q));
            		chatMemoryProvider.get("minipilot:history:" + request.getSession().getId()).add(aiMessage(finalAnswer));
            		
            		// Log all the conversations
                    Map<String, String> data = new HashMap<>();
                    data.put("session", request.getSession().getId());
                    data.put("question", q);
                    data.put("answer", finalAnswer);
                    data.put("ttft", String.valueOf(ttft));
                    data.put("etfl", String.valueOf(etfl));
                    jedisPooled.xadd("minipilot:conversation", data, XAddParams.xAddParams().maxLen(minipilotConversationLength));
                    
                    // Add to semantic cache
                    cache.addToCache(q, finalAnswer);
                })
            	.onError(emitter::completeWithError)  
            	.start();  // Start the streaming process

        } catch (Exception e) {
            emitter.completeWithError(e);  
        	//emitter.send("Network issue, retry later");
        }
        
        return emitter;
	}
    
    
    interface Assistant {
    	TokenStream chat(	@MemoryId String memoryId, 
    						@UserMessage String userMessage);
    }
    
    
    interface FunctionRequired {
    	// String as a return types, does not append instructions to the end of UserMessage indicating the format in which the LLM should respond
    	// boolean appends "You must answer strictly in the following format: one of [true, false]" and may fail, as it return sometimes
    	// [true] rather than true, which is interpreted as false
        @UserMessage("Is this question asking to calculate an average of the score, or searching a movie by genre? The question is: \"{{it}}\" Reply strictly with true or false")
        String isFunctionRequired(String text);
    }
    
    
    interface Assistant2 { 	
    	@SystemMessage("{{systemPrompt}}")
    	//@UserMessage("Answer this question politely {{message}}")
        TokenStream chat(	@MemoryId String memoryId, 
        					@V("systemPrompt") String systemPrompt,
        					@UserMessage String userMessage, 
        					@V("question") String question);
    }
    
    
	@PostMapping("/reset")
	public ResponseEntity reset(Model model, HttpServletRequest request) {
		
        RedisChatMemoryStore store = RedisChatMemoryStore.builder()
        		.host(host)
        		.port(port)
        		.build();
		
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
	
	
    public List<List<String>> readIndexSchema(String indexName) {
    	ArrayList<String> indexSchema = (ArrayList<String>) jedisPooled.ftInfo(indexName).get("attributes");
    	List<List<String>> output = new ArrayList<>();
        try  {
        	// [[identifier, $.vector, attribute, vector, type, VECTOR], [identifier, $.text, attribute, text, type, TEXT, WEIGHT, 1], [identifier, $.score, attribute, score, type, NUMERIC]]
        	
        	for (Object item : indexSchema) {
        		ArrayList<String> innerList = (ArrayList<String>) item;
        		
        		// skipping vectors, not required for a natural language query
        		if (!innerList.get(5).equals("VECTOR")) {
	                ArrayList<String> reduced = new ArrayList<String>();
	                reduced.add(innerList.get(3));
	                reduced.add(innerList.get(5));
	                output.add(reduced);
        		}
            }

        } catch (JedisException e) {
            System.out.println("readIndexSchema error " + e.getMessage());
        }

        return output;
    }
}


class RedisSearchTools {
	
    @Autowired
    private final JedisPooled jedisPooled;
    
    private final String indexName;
    
    @Autowired
    public RedisSearchTools(String indexName) {
		this.jedisPooled = new JedisPooled();
		this.indexName = indexName;

    }
	
	
    // example FT.AGGREGATE minipilot_rag_imdb_movies_20240826_012558_idx * GROUPBY 0 REDUCE AVG 1 score AS avg_field
	@Tool("Calculate the average of the desired field")
	public float average(String field) {
		AggregationBuilder r = new AggregationBuilder("*");
		r.groupBy(new Group().reduce(Reducers.avg(field).as("avg_field")));
		AggregationResult res = jedisPooled.ftAggregate(indexName, r);	
	    return  Float.parseFloat(res.getRow(0).getString("avg_field"));
	}
	
	
	@Tool("Search movies by genre")
	public List<Document> genre(
			@P("The genre") String theGenre) {
		Query q = new Query(String.format("@genre:{%s}", theGenre));
		q.returnFields("$.names");
		q.limit(0, 100);
		List<Document> res = jedisPooled.ftSearch(indexName, q).getDocuments();
	    return res;
	}
	
	
	// example FT.SEARCH minipilot_rag_imdb_movies_20240826_012558_idx "@score:[80.0 +inf]" LIMIT 0 100 RETURN 1 $.names
	@Tool("Find entries having a field bigger than a certain value")
	public List<Document> popular(
			@P("The field name") String fieldName,
			@P("The field value") float fieldValue) {
		Query q = new Query(String.format("@%s:[%s +inf]", fieldName, fieldValue));
		q.returnFields("$.names");
		q.limit(0, 100);
		List<Document> res = jedisPooled.ftSearch(indexName, q).getDocuments();
	    return res;
	}
}
