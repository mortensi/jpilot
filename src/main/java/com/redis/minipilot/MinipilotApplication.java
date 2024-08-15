package com.redis.minipilot;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.IndexDefinition;
import redis.clients.jedis.search.IndexOptions;
import redis.clients.jedis.search.Schema;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/*
@SpringBootApplication
@RestController
public class MinipilotApplication {

	public static void main(String[] args) {
		SpringApplication.run(MinipilotApplication.class, args);
	}
	
`
}
*/

// https://stackoverflow.com/questions/46617044/how-to-use-autowired-autowired-references-from-mainstring-args-method


@SpringBootApplication
@EnableAsync
public class MinipilotApplication implements ApplicationRunner {
    @Autowired
	FileStorage fs;
	
	
	public static void main(String[] args) {
		SpringApplication.run(MinipilotApplication.class, args);
	}


	@Override
	public void run(ApplicationArguments args) throws Exception {
		fs.init();
		
		UnifiedJedis unifiedjedis = new UnifiedJedis(new HostAndPort("localhost", 6379));
		
		if (!unifiedjedis.ftList().contains("minipilot_data_idx")){
			Schema schema = new Schema().addTextField("description", 1.0).addTagField("filename").addNumericField("uploaded");
			IndexDefinition def = new IndexDefinition().setPrefixes(new String[] {"minipilot:data:"});
			unifiedjedis.ftCreate("minipilot_data_idx", IndexOptions.defaultOptions().setDefinition(def), schema);
		}
	}

}