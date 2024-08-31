package com.redis.minipilot.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.IndexDefinition;
import redis.clients.jedis.search.IndexOptions;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.Schema;
import redis.clients.jedis.search.IndexDefinition.Type;
import static redis.clients.jedis.search.RediSearchUtil.toByteArray;

@Component
public class SemanticCache {
	
	
    @Value("${redis.host}")
    private String host;

    @Value("${redis.port}")
    private int port;
    
    @Value("${redis.password}")
    private String password;
	
    // Injected JedisPooled instance
    private final JedisPooled jedisPooled;
    
    OpenAiEmbeddingModel embeddingModel = null;
    
    @Autowired
    public SemanticCache(JedisPooled jedisPooled) {
        this.jedisPooled = jedisPooled;
        
        if (!jedisPooled.ftList().contains("minipilot_cache_idx")){
	        Map<String, Object> attr = new HashMap<>();
	        attr.put("TYPE", "FLOAT32");
	        attr.put("DIM", 1536);
	        attr.put("DISTANCE_METRIC", "COSINE");
	        attr.put("INITIAL_CAP", 5);
	        Schema schema = new Schema().addHNSWVectorField("$.vector", attr).as("vector");
	        IndexDefinition def = new IndexDefinition(Type.JSON).setPrefixes("minipilot:cache");
	        jedisPooled.ftCreate("minipilot_cache_idx", IndexOptions.defaultOptions().setDefinition(def), schema);
	        System.out.println("minipilot_cache_idx created");
        }
        
        try {
        	embeddingModel = OpenAiEmbeddingModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY")).modelName("text-embedding-ada-002")
            .build();
        } catch (Exception e) {
            System.out.println("Cannot instantiate the embedding model");
        }
    }
    
    
    public List<Document> isInCache(String q) {
    	int K = 1;
    	Query query = new Query("@vector:[VECTOR_RANGE $radius $query_vector]=>{$YIELD_DISTANCE_AS: dist_field}").
    	                    addParam("radius", 0.1).
    	                    addParam("query_vector", toByteArray(embeddingModel.embed(q).content().vector())).
    	                    setSortBy("dist_field", true).
    	                    returnFields("$.answer").
    	                    limit(0,K).
    	                    dialect(2);

    	List<Document> res = jedisPooled.ftSearch("minipilot_cache_idx", query).getDocuments();
    	return res;
    }
    
    
    public void addToCache(String q, String answer) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("question", q);
        fields.put("answer", answer);
        fields.put("vector", embeddingModel.embed(q).content().vector());
        
        AbstractTransaction tx = jedisPooled.multi();
        String cacheEntryKey = String.format("minipilot:cache:%s", UUID.randomUUID().toString());
        tx.jsonSetWithEscape(cacheEntryKey, Path2.of("$"), fields);
    	tx.expire(cacheEntryKey, 2628000);
    	tx.exec();
        return;
    }
    
    
    public void flushCache() {
    	jedisPooled.ftDropIndex("minipilot_cache_idx");
    	return;
    }

}
