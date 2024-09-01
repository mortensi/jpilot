package com.redis.minipilot.database;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

@Configuration
public class RedisConfig {

    @Value("${redis.host}")
    private String host;

    @Value("${redis.port}")
    private int port;
    
    @Value("${redis.password}")
    private String password;
    

    @Bean
    public JedisPooled jedisPooled() {
    	System.out.println("JedisPooled bean has been instantiated");
    	HostAndPort hostAndPort = new HostAndPort(host, port);
    	DefaultJedisClientConfig.Builder configBuilder = DefaultJedisClientConfig.builder();

    	// Conditionally set the password
    	if (password != null && !password.isEmpty()) {
    	    configBuilder.password(password);
    	}

    	// Build the config
    	DefaultJedisClientConfig config = configBuilder.build();
        return new JedisPooled(hostAndPort, config);
    }
}
