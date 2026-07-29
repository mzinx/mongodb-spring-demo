package com.mzinx.demo.config;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.mzinx.mongodb.aggregation.dao.PipelineRepository;
import com.mzinx.mongodb.aggregation.model.PipelineTemplate;
import com.mzinx.mongodb.changestream.model.ChangeStream.Mode;
import com.mzinx.mongodb.changestream.model.ChangeStream.ResumeStrategy;
import com.mzinx.mongodb.changestream.model.ChangeStreamConfig;
import com.mzinx.mongodb.changestream.service.ChangeStreamConfigService;

import com.mongodb.client.model.changestream.FullDocument;

/**
 * Seeds demo artifacts on first start so the UI has something to show:
 * <ul>
 * <li>an {@code orders-demo} change stream config targeting the
 * {@code eventRelay} listener</li>
 * <li>two aggregation pipeline templates, one of them using a
 * {@code {"_ph": ...}} variable placeholder</li>
 * </ul>
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ChangeStreamConfigService changeStreamConfigService;
    private final PipelineRepository pipelineRepository;

    DemoDataSeeder(ChangeStreamConfigService changeStreamConfigService, PipelineRepository pipelineRepository) {
        this.changeStreamConfigService = changeStreamConfigService;
        this.pipelineRepository = pipelineRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (changeStreamConfigService.findById("orders-demo") == null) {
            logger.info("Seeding demo change stream config 'orders-demo'");
            changeStreamConfigService.save(ChangeStreamConfig.builder()
                    .id("orders-demo")
                    .collectionName("orders")
                    .mode(Mode.BOARDCAST)
                    .resumeStrategy(ResumeStrategy.BATCH)
                    .fullDocument(FullDocument.UPDATE_LOOKUP)
                    .pipeline(List.of())
                    .listener("eventRelay")
                    .enabled(true)
                    .build());
        }

        if (pipelineRepository.findById("orders-summary").isEmpty()) {
            logger.info("Seeding demo pipeline template 'orders-summary'");
            pipelineRepository.save(PipelineTemplate.builder()
                    .name("orders-summary")
                    .aggs(List.of(
                            Map.of("$group", Map.of(
                                    "_id", "$status",
                                    "count", Map.of("$sum", 1),
                                    "totalAmount", Map.of("$sum", "$amount"))),
                            Map.of("$sort", Map.of("count", -1))))
                    .build());
        }

        if (pipelineRepository.findById("orders-by-status").isEmpty()) {
            logger.info("Seeding demo pipeline template 'orders-by-status'");
            pipelineRepository.save(PipelineTemplate.builder()
                    .name("orders-by-status")
                    .aggs(List.of(
                            // {"_ph": "status"} is replaced with the value of the
                            // "status" variable at execution time.
                            Map.of("$match", Map.of("status", Map.of("_ph", "status"))),
                            Map.of("$sort", Map.of("createdAt", -1)),
                            Map.of("$limit", 20)))
                    .build());
        }
    }
}
