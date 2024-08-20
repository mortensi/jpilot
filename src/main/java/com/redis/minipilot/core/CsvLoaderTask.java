package com.redis.minipilot.core;

import com.fasterxml.jackson.databind.ObjectMapper;


import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;


@Component
public class CsvLoaderTask {
	
    @Value("${redis.host}")
    private static String host;

    @Value("${redis.port}")
    private static int port;
    
    @Value("${redis.password}")
    private String password;
	
    // Injected JedisPooled instance
    private final JedisPooled jedisPooled;
    
    @Autowired
    public CsvLoaderTask(JedisPooled jedisPooled) {
        this.jedisPooled = jedisPooled;
    }


    public void load(String filename) {
    	System.out.println("Loading CSV with LangChain4J");
    	
        // Check if alias exists for semantic search
        try {
        	jedisPooled.ftInfo("minipilot_rag_alias");
        } catch (JedisDataException e) {
        	System.out.println("No alias exists for semantic search. Associate the alias to the desired index");
        }

        
        // Create a new index, named by CSV file and datetime
        String indexName = "minipilot_rag_" + getFilenameWithoutExtension(filename) + "_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + "_idx";


        // https://help.openai.com/en/articles/4936856-what-are-tokens-and-how-to-count-them
        // the default model is "text-embedding-ada-002".
        // max input is 8191 tokens
        // 1 token ~= 4 chars in English
        // 8191 x 4 = 32764 maximum characters that can be represented by a vector embedding
        // choosing 10000 as chunk size seems ok
        
        var metadata = List.of("title", "description");
        
        // https://github.com/langchain4j/langchain4j-examples/blob/main/redis-example/src/main/java/RedisEmbeddingStoreExample.java
        // https://github.com/langchain4j/langchain4j/pull/1347
        EmbeddingStore<TextSegment> embeddingStore = RedisEmbeddingStore.builder()
                .host(host)
                .user("default")
                .port(port)
                .indexName(indexName)
                .dimension(1536)
                //.metadataFieldsName(metadata)
                .build();
        
        OpenAiEmbeddingModel embeddingModel = null;
        try {
        	embeddingModel = OpenAiEmbeddingModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY")).modelName("text-embedding-ada-002")
            .build();
        } catch (Exception e) {
            System.out.println("Cannot instantiate the embedding model");
        }
        
        
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .documentSplitter(DocumentSplitters.recursive(10000, 50)) //, new OpenAiTokenizer()
                .build();
        
        
        // First method to split and embed a document
        // Document csv = new Document("l'ira funesta che infiniti adduse lutti agli Achei, molte anzi tempo all'orco generose travolse alme d'eroi, e di cani e d'augelli orrido pasto lor salme abbandonò (così di Giove l'alto consiglio s'adempìa), da quando primamente disgiunse aspra contesa il re de' prodi Atride e il divo Achille. E qual de numi inimmicoli? Il figlio Latona e di Giove.Irato al Sire destò quel Dio nel campo un ferro morbo,e la gente perìa:colpa d'Atride che fece a Crise sacerdote oltraggio. ");
        //DocumentSplitter splitter = DocumentSplitters.recursive(10000, 50);
        //List<TextSegment> chunks = splitter.split(csv);
        //System.out.println(chunks.size());
        //Response<List<Embedding>> embeddings = embeddingModel.embedAll(chunks);
        //embeddingStore.addAll(embeddings.content(), chunks);
        
        // Second method to split and embed a document
        //ingestor.ingest(csv);
        
        // Load CSV and index the content
        try  {
        	System.out.println("Reading CSV file " + filename);
        	FileReader fileReader = new FileReader(filename);
            Scanner scanner = new Scanner(fileReader);

            List<Map<String, String>> csvData = readCSV(scanner);

            for (Map<String, String> row : csvData) {
                String rowStr = rowToString(row);
                Document movie = new Document(rowStr);
                ingestor.ingest(movie);
            }
        }
	    catch (IOException e) {
	    	System.out.println("Error reading CSV file");
	    	return;
	    }
        
        System.out.println("Done loading CSV with LangChain4J");
        
        
        /*
        DocumentSplitter documentSplitter = DocumentSplitter.recursive(1000, 150);
        DocumentSplitters.recursive(1000, 200, new OpenAiTokenizer());
        //DocumentByCharacterSplitter splitter = new DocumentByCharacterSplitter(1024, 0, tokenizer);
        
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 0);
        List<TextSegment> segments = splitter.split(document);

        // Load CSV and index the content
        try (FileReader fileReader = new FileReader(filename);
             Scanner scanner = new Scanner(fileReader)) {

            List<Map<String, String>> csvData = readCSV(scanner);

            for (Map<String, String> row : csvData) {
                String rowStr = rowToString(row);
                List<String> splits = docSplitter.splitText(rowStr);

                if (!splits.isEmpty()) {
                    RedisVectorStore redisVectorStore = new RedisVectorStore(
                            RedisVectorStore.Builder.builder()
                                    .redisUrl(generateRedisConnectionString(REDIS_HOST, REDIS_PORT, REDIS_PASSWORD))
                                    .indexName(indexName)
                                    .embeddingModel(embeddingModel)
                                    .vectorSchema(vectorSchema)
                                    .build()
                    );

                    redisVectorStore.fromTexts(splits, null);
                }
            }

        } catch (IOException e) {
            logger.error("Error reading CSV file", e);
        }
        
        */
    }

    private static List<Map<String, String>> readCSV(Scanner scanner) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        String[] headers = scanner.nextLine().split(",");
        while (scanner.hasNextLine()) {
            String[] values = scanner.nextLine().split(",");
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                row.put(headers[i], values[i]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static String rowToString(Map<String, String> row) {
        StringBuilder rowStr = new StringBuilder();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            rowStr.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        return rowStr.toString();
    }

    private static String generateRedisConnectionString(String host, int port, String password) {
        return "redis://" + (password != null && !password.isEmpty() ? ":" + password + "@" : "") + host + ":" + port;
    }

    private static String getFilenameWithoutExtension(String filename) {
        return new File(filename).getName().replaceFirst("[.][^.]+$", "");
    }
}
