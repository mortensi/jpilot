package com.redis.minipilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

import com.redis.minipilot.core.SystemPromptDefault;
import com.redis.minipilot.core.UserPromptDefault;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.IndexDefinition;
import redis.clients.jedis.search.IndexOptions;
import redis.clients.jedis.search.Schema;


// https://stackoverflow.com/questions/46617044/how-to-use-autowired-autowired-references-from-mainstring-args-method


@SpringBootApplication
@EnableAsync
@ComponentScan(basePackages = {"com.redis.minipilot", "com.redis.minipilot.database", "com.redis.minipilot.core"})
public class MinipilotApplication implements ApplicationRunner {
    @Autowired
	FileStorage fs;
    
    private final JedisPooled jedisPooled;
    
    private static final Logger logger = LoggerFactory.getLogger(MinipilotApplication.class);
    
    @Autowired
    public MinipilotApplication(JedisPooled jedisPooled) {
        this.jedisPooled = jedisPooled;
    }
	
	public static void main(String[] args) {
		SpringApplication.run(MinipilotApplication.class, args);
	}


	@Override
	public void run(ApplicationArguments args) throws Exception {
		fs.init();
		
		logger.warn("Starting Minipilot");
		
		// Initialize prompts
		if (!jedisPooled.exists("minipilot:prompt:system")) {
			jedisPooled.set("minipilot:prompt:system", SystemPromptDefault.SYSTEM_TEMPLATE);
		}
		
		if (!jedisPooled.exists("minipilot:prompt:user")) {
			jedisPooled.set("minipilot:prompt:user", UserPromptDefault.USER_TEMPLATE);
		}
		
		// Initialize indexes
		if (!jedisPooled.ftList().contains("minipilot_data_idx")){
			Schema schema = new Schema().addTextField("description", 1.0).addTagField("filename").addNumericField("uploaded");
			IndexDefinition def = new IndexDefinition().setPrefixes(new String[] {"minipilot:data:"});
			jedisPooled.ftCreate("minipilot_data_idx", IndexOptions.defaultOptions().setDefinition(def), schema);
			//System.out.println("minipilot_data_idx created");
			logger.info("minipilot_data_idx created");
		}
	}

}