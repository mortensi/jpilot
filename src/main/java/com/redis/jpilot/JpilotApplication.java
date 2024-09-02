package com.redis.jpilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

import com.redis.jpilot.core.SystemPromptDefault;
import com.redis.jpilot.core.UserPromptDefault;
import com.redis.jpilot.utils.FileStorage;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.IndexDefinition;
import redis.clients.jedis.search.IndexOptions;
import redis.clients.jedis.search.Schema;



@SpringBootApplication
@EnableAsync
@ComponentScan(basePackages = {"com.redis.jpilot", "com.redis.jpilot.controllers", "com.redis.jpilot.database", "com.redis.jpilot.core"})
public class JpilotApplication implements ApplicationRunner {
    @Autowired
	FileStorage fs;
    
    private final JedisPooled jedisPooled;
    
    private static final Logger logger = LoggerFactory.getLogger(JpilotApplication.class);
    
    @Autowired
    public JpilotApplication(JedisPooled jedisPooled) {
        this.jedisPooled = jedisPooled;
    }
	
	public static void main(String[] args) {
		SpringApplication.run(JpilotApplication.class, args);
	}


	@Override
	public void run(ApplicationArguments args) throws Exception {
		fs.init();
		
		logger.warn("Starting jPilot");
		
		// Initialize prompts
		if (!jedisPooled.exists("jpilot:prompt:system")) {
			jedisPooled.set("jpilot:prompt:system", SystemPromptDefault.SYSTEM_TEMPLATE);
		}
		
		if (!jedisPooled.exists("jpilot:prompt:user")) {
			jedisPooled.set("jpilot:prompt:user", UserPromptDefault.USER_TEMPLATE);
		}
		
		// Initialize indexes
		if (!jedisPooled.ftList().contains("jpilot_data_idx")){
			Schema schema = new Schema().addTextField("description", 1.0).addTagField("filename").addNumericField("uploaded");
			IndexDefinition def = new IndexDefinition().setPrefixes(new String[] {"jpilot:data:"});
			jedisPooled.ftCreate("jpilot_data_idx", IndexOptions.defaultOptions().setDefinition(def), schema);
			logger.info("jpilot_data_idx created");
		}
	}

}