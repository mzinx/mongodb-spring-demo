package com.mzinx.demo;

import java.util.concurrent.Executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;


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

}
