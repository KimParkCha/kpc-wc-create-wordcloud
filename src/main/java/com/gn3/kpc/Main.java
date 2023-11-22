package com.gn3.kpc;

import com.gn3.kpc.dto.DTO;
import com.gn3.kpc.dto.NewsDTO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kennycason.kumo.*;
import com.kennycason.kumo.bg.CircleBackground;
import com.kennycason.kumo.font.scale.SqrtFontScalar;
import com.kennycason.kumo.palette.ColorPalette;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.openkoreantext.processor.OpenKoreanTextProcessorJava;
import org.openkoreantext.processor.phrase_extractor.KoreanPhraseExtractor;
import org.openkoreantext.processor.tokenizer.KoreanTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import scala.Tuple2;
import scala.collection.Seq;

import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class Main {
    private static final CharSequence FIN_MESSAGE = "end\n";
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    public static void main(String[] args) throws IOException {
        AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext(AutoConfig.class);
        JavaSparkContext javaSparkContext = ac.getBean(JavaSparkContext.class);

        String bootstrapServers = "master:9092,sn01:9092,sn02:9092,sn03:9092";
        String groupId = args[0];
        String topic = "news";

        Properties properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);

        final Thread mainThread = Thread.currentThread();

        // adding the shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                log.info("Detected a shutdown, let's exit by calling consumer.wakeup()...");
                consumer.wakeup();

                // join the main thread to allow the execution of the code in the main thread
                try {
                    mainThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        List<DTO> newsDTOS = new ArrayList<>();
        try {
            consumer.subscribe(Arrays.asList(topic));
            String message = "";
            break_point: while (true){
                ConsumerRecords<String, String> records =
                        consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> record : records) {
                    message = record.value();
                    if(message.equals(FIN_MESSAGE))break break_point;
                    try{
                        Gson gson = new GsonBuilder().create();
                        NewsDTO newsDTO = gson.fromJson(message, NewsDTO.class);
                        newsDTOS.add(newsDTO);
                    }catch (Exception e){
                        e.printStackTrace();
                        System.out.println("message = " + message);
                    }

                }
            }
        } catch (WakeupException e) {
            log.info("Wake up exception!");
            // we ignore this as this is an expected exception when closing a consumer
        } catch (Exception e) {
            log.error("Unexpected exception", e);
        } finally {
            consumer.close(); // this will also commit the offsets if need be.
            log.info("The consumer is now gracefully closed.");
        }

        JavaRDD<DTO> rdd = javaSparkContext.parallelize(newsDTOS, 2);
        JavaRDD<String> wcRDD = rdd.mapPartitions(iter -> {
            List<DTO> dtos = new ArrayList<>();
            iter.forEachRemaining(dtos::add);
            List<String> result = new ArrayList<>();

            for (DTO dto : dtos) {
                String title = ((NewsDTO) dto).getTitle();
                CharSequence normalized = OpenKoreanTextProcessorJava.normalize(title);
                Seq<KoreanTokenizer.KoreanToken> tokens = OpenKoreanTextProcessorJava.tokenize(normalized);
                List<KoreanPhraseExtractor.KoreanPhrase> phrases = OpenKoreanTextProcessorJava.extractPhrases(tokens, true, true);
                for (KoreanPhraseExtractor.KoreanPhrase phrase : phrases) {
                    String text = phrase.text();
                    result.add(text);
                }
            }
            return result.iterator();
        });


        List<Tuple2<String, Integer>> result = wcRDD.mapToPair(s -> new Tuple2<>(s, 1)).reduceByKey((i1, i2) -> i1 + i2).collect();
        Jedis jedis = ac.getBean(JedisPool.class).getResource();
        for (Tuple2<String, Integer> tuple2 : result) {
            byte[] tupleBytes = tuple2._1().getBytes(StandardCharsets.UTF_8);
            String utf8Encode = new String(tupleBytes, StandardCharsets.UTF_8);
            System.out.println("tuple2._1() = " + tuple2._1());
            System.out.println("tuple2 = " + tuple2._2());
            jedis.hset("wordcloud", tuple2._1(), String.valueOf(tuple2._2()));
        }
        jedis.close();
    }
}
