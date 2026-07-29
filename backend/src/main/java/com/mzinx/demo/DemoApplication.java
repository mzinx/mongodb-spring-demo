package com.mzinx.demo;

import java.util.concurrent.Executor;

import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import com.mongodb.MongoClientSettings;

/**
 * Demo application showcasing the mongodb-spring-* libraries.
 * <p>
 * The libraries configure themselves through Spring Boot auto-configuration;
 * this class only contributes the beans a host application is expected to
 * provide:
 * <ul>
 * <li>a {@code taskExecutor} used to run change stream cursors</li>
 * <li>a {@code CodecRegistry} required by the aggregation library</li>
 * </ul>
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    /** Executor running the blocking change stream cursor loops. */
    @Bean
    Executor taskExecutor() {
        return new SimpleAsyncTaskExecutor("demo-cs-");
    }

    /** POJO codec registry required by mongodb-spring-aggregation. */
    /*@Bean
    CodecRegistry pojoCodecRegistry() {
        return CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()));
    }*/

    /**
     * Registers the POJO codec registry on the MongoClient so raw driver
     * reads/writes of library POJOs (e.g. {@code PipelineTemplate}) can be
     * encoded/decoded.
     */
    @Bean
    MongoClientSettingsBuilderCustomizer pojoCodecClientCustomizer(CodecRegistry pojoCodecRegistry) {
        return builder -> builder.codecRegistry(pojoCodecRegistry);
    }
}
