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

mvn exec:java -Dexec.mainClass=com.redis.jpilot.JpilotApplication
```

1. Now point your browser to [http://localhost:9090/data](http://localhost:9090/data).
2. Import a CSV with your data. As an example, download the [IMDB movies dataset](https://www.kaggle.com/datasets/ashpalsingh1525/imdb-movies-dataset)
3. Click on "create" to start reading the CSV file and create the index
4. When done (it will take up to thirty minutes to import 10,000+ movies in the dataset), click on "Make current" to select the new index
5. Start asking and review how the cache starts collecting questions.

For a feature complete demo, try [Minipilot](https://github.com/redis/minipilot).
