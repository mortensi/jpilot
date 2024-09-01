package com.redis.minipilot;

import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

import java.util.HashMap;
import java.util.Map;

public class RedisStreamAppender extends AppenderBase<ILoggingEvent> {

    private JedisPooled jedisPooled;
    private String streamName = "minipilot:logging";
    private String redisHost = "localhost";  // Default host, change if necessary
    private int redisPort = 6379;            // Default port, change if necessary
    private long maxStreamLength = 1000;

    public RedisStreamAppender() {
        // Default no-arg constructor, necessary for Logback
    }

    @Override
    public void start() {
        super.start();
        try {
            // Initialize JedisPooled here instead of using @Autowired
            this.jedisPooled = new JedisPooled(redisHost, redisPort);
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

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public void setRedisHost(String redisHost) {
        this.redisHost = redisHost;
    }

    public void setRedisPort(int redisPort) {
        this.redisPort = redisPort;
    }
}
