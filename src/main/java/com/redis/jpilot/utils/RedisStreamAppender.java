package com.redis.jpilot.utils;

import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

import java.util.HashMap;
import java.util.Map;


public class RedisStreamAppender extends AppenderBase<ILoggingEvent> {

    private JedisPooled jedisPooled;
	
    private String streamName = "jpilot:logging";
    private long maxStreamLength = 1000;
    

    public RedisStreamAppender() {
        // Default no-arg constructor, necessary for Logback
    }
    

    @Override
    public void start() {
        super.start();
        try {
            // Initialize JedisPooled here instead of using @Autowired
        	// RedisStreamAppender is not a Spring-managed bean by default
        	// it is used by Logback, which initializes its appenders independently of Spring's context
            String host = System.getenv("REDIS_HOST") != null ? System.getenv("REDIS_HOST") : "localhost"; 
            int port = System.getenv("REDIS_PORT") != null ? Integer.parseInt(System.getenv("REDIS_PORT")) : 6379; 
            String password = System.getenv("REDIS_PASSWORD") != null ? System.getenv("REDIS_PASSWORD") : ""; 
            
        	HostAndPort hostAndPort = new HostAndPort(host, port);
        	DefaultJedisClientConfig.Builder configBuilder = DefaultJedisClientConfig.builder();

        	// Conditionally set the password
        	if (password != null && !password.isEmpty()) {
        	    configBuilder.password(password);
        	}

        	DefaultJedisClientConfig config = configBuilder.build();
            
            this.jedisPooled = new JedisPooled(hostAndPort, config);
        } catch (Exception e) {
            addError("Failed to initialize Redis connection", e);
        }
    }

    @Override
    public void stop() {
        super.stop();
        if (this.jedisPooled != null) {
            try {
                this.jedisPooled.close(); // Ensure resource cleanup
            } catch (Exception e) {
                addError("Error while closing Redis connection", e);
            }
        }
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (jedisPooled == null) {
            addError("JedisPooled is not initialized.");
            return;
        }

        Map<String, String> logEntry = new HashMap<>();
        logEntry.put("timestamp", String.valueOf(eventObject.getTimeStamp()));
        logEntry.put("level", eventObject.getLevel().toString());
        logEntry.put("thread", eventObject.getThreadName());
        logEntry.put("logger", eventObject.getLoggerName());
        logEntry.put("message", eventObject.getFormattedMessage());

        try {
        	XAddParams params = XAddParams.xAddParams().maxLen(maxStreamLength);
            jedisPooled.xadd(streamName, params, logEntry);
            
        } catch (Exception e) {
            addError("Error sending log message to Redis", e);
        }
    }
}
