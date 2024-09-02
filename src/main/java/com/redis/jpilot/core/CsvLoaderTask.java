package com.redis.jpilot.core;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.search.IndexDefinition;
import redis.clients.jedis.search.IndexDefinition.Type;
import redis.clients.jedis.search.IndexOptions;
import redis.clients.jedis.search.Schema;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;


@Component
public class CsvLoaderTask {
	
    @Value("${redis.host}")
    private String host;

    @Value("${redis.port}")
    private int port;
    
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
        	jedisPooled.ftInfo("jpilot_rag_alias");
        } catch (JedisDataException e) {
        	System.out.println("No alias exists for semantic search. Associate the alias to the desired index");
        }

        
        // Create a new index, named by CSV file and datetime
        String indexName = "jpilot_rag_" + getFilenameWithoutExtension(filename) + "_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + "_idx";


        // https://help.openai.com/en/articles/4936856-what-are-tokens-and-how-to-count-them
        // the default model is "text-embedding-ada-002".
        // max input is 8191 tokens
        // 1 token ~= 4 chars in English
        // 8191 x 4 = 32764 maximum characters that can be represented by a vector embedding
        // choosing 10000 as chunk size seems ok
        
        // https://github.com/langchain4j/langchain4j-examples/blob/main/redis-example/src/main/java/RedisEmbeddingStoreExample.java
        // Note that it is not possible to indicate a prefix for the index, which means that everything ingested will be indexed
        // Which also means that an application can be single-indexed and the Redis alias is meaningless
        // https://github.com/langchain4j/langchain4j/issues/1340
        // https://github.com/langchain4j/langchain4j/pull/1347
        
        // It seems like metadata is indexed as TEXT only, according to the implementation in toSchemaFields
        // https://github.com/langchain4j/langchain4j/blob/main/langchain4j-redis/src/main/java/dev/langchain4j/store/embedding/redis/RedisSchema.java#L51
        // This prevents from running NUMERIC or GEO or any other kind of advanced RAG query
        // https://github.com/langchain4j/langchain4j/discussions/1612
        // https://github.com/langchain4j/langchain4j/issues/1613
        // For the time being, let's comment metadataKeys
        
        OpenAiEmbeddingModel embeddingModel = null;
        try {
        	embeddingModel = OpenAiEmbeddingModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY")).modelName("text-embedding-ada-002")
            .build();
        } catch (Exception e) {
            System.out.println("Cannot instantiate the embedding model");
        }
        
        /*
        EmbeddingStore<TextSegment> embeddingStore = RedisEmbeddingStore.builder()
                .host(host)
                .user("default")
                .port(port)
                .indexName(indexName)
                .dimension(1536)
                .metadataKeys(List.of("score"))
                .build();        
        
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .documentSplitter(DocumentSplitters.recursive(10000, 50)) //, new OpenAiTokenizer()
                .build();
        */
        
        Map<String, Object> attr = new HashMap<>();
        attr.put("TYPE", "FLOAT32");
        attr.put("DIM", 1536);
        attr.put("DISTANCE_METRIC", "COSINE");
        attr.put("INITIAL_CAP", 5);
        Schema schema = new Schema().addHNSWVectorField("$.vector", attr).as("vector")
        							.addTextField("$.text", 1.0).as("text")
        							.addTextField("$.names", 1.0).as("title")
        							.addTagField("$.genre").as("genre")
        							.addNumericField("$.date_x").as("date_x")
        							.addNumericField("$.score").as("score");
        IndexDefinition def = new IndexDefinition(Type.JSON).setPrefixes(new String[] {String.format("jpilot:embedding:%s",indexName)});
        jedisPooled.ftCreate(indexName, IndexOptions.defaultOptions().setDefinition(def), schema);
        
        
        // First method to split and embed a document
        /*
        Document csv = new Document("l'ira funesta che infiniti adduse lutti agli Achei, molte anzi tempo all'orco generose travolse alme d'eroi, e di cani e d'augelli orrido pasto lor salme abbandonò (così di Giove l'alto consiglio s'adempìa), da quando primamente disgiunse aspra contesa il re de' prodi Atride e il divo Achille. E qual de numi inimmicoli? Il figlio Latona e di Giove.Irato al Sire destò quel Dio nel campo un ferro morbo,e la gente perìa:colpa d'Atride che fece a Crise sacerdote oltraggio. ");
        DocumentSplitter splitter = DocumentSplitters.recursive(10000, 50);
        List<TextSegment> chunks = splitter.split(csv);
        System.out.println(chunks.size());
        Response<List<Embedding>> embeddings = embeddingModel.embedAll(chunks);
        embeddingStore.addAll(embeddings.content(), chunks);
        */
        
        // Second method to split and embed a document, used below
        //ingestor.ingest(csv);
        
        // Load CSV and index the content
        try  {
        	System.out.println("Reading CSV file " + filename);
        	FileReader fileReader = new FileReader(filename);
            Scanner scanner = new Scanner(fileReader);

            //List<Map<String, String>> csvData = readCSV(scanner);
            
            List<Map<String, String>> csvData = readCSV2(filename);
            
            int cnt = 0;
            for (Map<String, String> row : csvData) {
                String rowStr = rowToString(row);
                
                System.out.println(row);
                
                // This is my low-level ingestion
                Map<String, Object> fields = new HashMap<>();
                fields.put("text", rowStr);
                fields.put("vector", embeddingModel.embed(rowStr).content().vector());
                fields.put("score", Float.parseFloat(row.get("score")));
                fields.put("names", row.get("names"));
                fields.put("genre", row.get("genre"));
                fields.put("date_x", DateToUnixTimestamp(row.get("date_x")));
                jedisPooled.jsonSetWithEscape(String.format("jpilot:embedding:%s:%s",indexName, UUID.randomUUID().toString()), Path2.of("$"), fields);
                
                // This is an example of ingestor with metadata, but unfortunately ingested metadata is only indexed as TEXT
                // Document movieWithMetadata = createFromCSVLine(row, List.of("score"));
                // ingestor.ingest(movieWithMetadata);
                // System.out.println(movieWithMetadata);
                
                // This is ingestion without metadata
                // Document movie = new Document(rowStr);
                // ingestor.ingest(movie);
                cnt++;
                if (cnt == 150) {
                	break;
                }
            }
            
        }
	    catch (IOException e) {
	    	System.out.println("Error reading CSV file");
	    	return;
	    } catch (CsvValidationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        System.out.println("Done loading CSV with LangChain4J");
    }
    
    
    private Document createFromCSVLine(	Map<String, String> row,
            							List<String> columnsToIncludeInMetadata) {
        
        Map<String, String> metadata = new HashMap<>();
        StringBuilder rowStr = new StringBuilder();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (columnsToIncludeInMetadata.contains(entry.getKey())) {
                metadata.put(entry.getKey(), entry.getValue());
            }
            rowStr.append(entry.getKey()).append(": ").append(entry.getValue()).append("; ");
        }
        
        // The \n is added to the end of the content
        return new Document(rowStr.append("\n").toString(), Metadata.from(metadata));
    }
    
    
    private static long DateToUnixTimestamp(String dateString) {
    	SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy");
    	Date date = null;
		try {
			date = dateFormat.parse(dateString);
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	long unixTimestamp = date.getTime() / 1000;
		return unixTimestamp;
    }
    

    private static List<Map<String, String>> readCSV(Scanner scanner) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        String[] headers = scanner.nextLine().split(",");
        while (scanner.hasNextLine()) {
            String[] values = scanner.nextLine().split(",");
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                row.put(headers[i], values[i]);
                System.out.println(headers[i]);
                System.out.println(values[i]);
            }
            rows.add(row);
        }
        return rows;
    }
    
    
    private static List<Map<String, String>> readCSV2(String filename) throws IOException, CsvValidationException {
    	List<Map<String, String>> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new FileReader(filename))) {
            String[] values;
            String[] header = reader.readNext();
            while ((values = reader.readNext()) != null) {
            	Map<String, String> row = new HashMap<>();
                for (int i = 0; i < header.length; i++) {
                    row.put(header[i], values[i]);
                    System.out.println(header[i]);
                    System.out.println(values[i]);
                }
                rows.add(row);
                System.out.println();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
		return rows;
    }

    
    private static String rowToString(Map<String, String> row) {
        StringBuilder rowStr = new StringBuilder();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            rowStr.append(entry.getKey()).append(": ").append(entry.getValue()).append("; ");
        }
        return rowStr.toString();
    }


    private static String getFilenameWithoutExtension(String filename) {
        return new File(filename).getName().replaceFirst("[.][^.]+$", "");
    }
}
