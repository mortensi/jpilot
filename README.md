# jPilot

jPilot is yet another Java GenAI chatbot. Based on [Minipilot](https://github.com/redis/minipilot) (developed in Python with Flask, OpenAI and LangChain), jPilot is a demo written in Java with OpenAI, LangChain4J, ThymeLeaf and JQuery. 

From the browser UI you will be able to:

1. Load CSV data, split, embed, store and index in Redis
2. Customize the system and user prompt based on the type of chatbot running (based on the ingested and indexed data)
3. Use semantic caching, with a UI panel to review, edit and remove entries
4. Load and ingest multiple CSV files and create the corresponding indexes. However, only one index at time is used using the Redis aliasing mechanism
5. Application logs are appended to a Redis stream, to review the latest logs directly from the UI


## Configuring jPilot

```
export OPENAI_API_KEY=...
export JPILOT_ASSETS=./assets
export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/"
```


## Running jPilot

```
git clone https://github.com/mortensi/jpilot
cd jpilot

mvn clean install
mvn package

mvn exec:java -Dexec.mainClass=com.redis.jpilot.JpilotApplication

```

