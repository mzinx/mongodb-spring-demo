package com.mzinx.demo.config;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.mzinx.demo.listener.OrderSummaryListener;
import com.mzinx.mongodb.aggregation.dao.PipelineRepository;
import com.mzinx.mongodb.aggregation.model.PipelineTemplate;
import com.mzinx.mongodb.changestream.model.ChangeStream.Mode;
import com.mzinx.mongodb.changestream.model.ChangeStream.ResumeStrategy;
import com.mzinx.mongodb.changestream.model.ChangeStreamConfig;
import com.mzinx.mongodb.changestream.service.ChangeStreamConfigService;

/**
 * Seeds demo artifacts on first start:
 * <ul>
 * <li>the {@code order-summary} change stream config driving
 * {@link OrderSummaryListener} (AUTO_RECOVER: one leader instance
 * precomputes, with automatic failover)</li>
 * <li>the {@code orders-daily-summary} pipeline template it executes
 * (with a {@code {"_ph": "runId"}} variable placeholder)</li>
 * <li>two more pipeline templates for the Aggregations page</li>
 * </ul>
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ChangeStreamConfigService changeStreamConfigService;
    private final PipelineRepository pipelineRepository;
    private final OrderSummaryListener orderSummaryListener;

    DemoDataSeeder(ChangeStreamConfigService changeStreamConfigService, PipelineRepository pipelineRepository,
            OrderSummaryListener orderSummaryListener) {
        this.changeStreamConfigService = changeStreamConfigService;
        this.pipelineRepository = pipelineRepository;
        this.orderSummaryListener = orderSummaryListener;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Clean up the config seeded by earlier demo versions; its listener
        // bean (eventRelay) no longer exists.
        if (changeStreamConfigService.findById("orders-demo") != null) {
            logger.info("Removing legacy demo change stream config 'orders-demo'");
            changeStreamConfigService.delete("orders-demo");
        }

        if (pipelineRepository.findById(OrderSummaryListener.PIPELINE_NAME).isEmpty()) {
            logger.info("Seeding pipeline template '{}'", OrderSummaryListener.PIPELINE_NAME);
            pipelineRepository.save(PipelineTemplate.builder()
                    .name(OrderSummaryListener.PIPELINE_NAME)
                    .stages(List.of(
                            Map.of("$group", Map.of(
                                    "_id", Map.of(
                                            "day", Map.of("$dateToString",
                                                    Map.of("format", "%Y-%m-%d", "date", "$createdAt")),
                                            "status", "$status"),
                                    "count", Map.of("$sum", 1),
                                    "revenue", Map.of("$sum", "$amount"))),
                            Map.of("$group", Map.of(
                                    "_id", "$_id.day",
                                    "orders", Map.of("$sum", "$count"),
                                    "revenue", Map.of("$sum", "$revenue"),
                                    "byStatus", Map.of("$push", Map.of("k", "$_id.status", "v", "$count")))),
                            Map.of("$addFields", Map.of(
                                    "byStatus", Map.of("$arrayToObject", "$byStatus"),
                                    "avgOrderValue", Map.of("$round", List.of(
                                            Map.of("$divide",
                                                    List.of("$revenue", Map.of("$max", List.of("$orders", 1)))),
                                            2)),
                                    "updatedAt", "$$NOW",
                                    // replaced with the runId variable at execution time
                                    "runId", Map.of("_ph", "runId"))),
                            Map.of("$merge", Map.of(
                                    "into", OrderSummaryListener.SUMMARY_COLLECTION,
                                    "on", "_id",
                                    "whenMatched", "replace",
                                    "whenNotMatched", "insert"))))
                    .build());
        }

        if (changeStreamConfigService.findById("order-summary") == null) {
            logger.info("Seeding change stream config 'order-summary'");
            changeStreamConfigService.save(ChangeStreamConfig.builder()
                    .id("order-summary")
                    .collectionName(OrderSummaryListener.SOURCE_COLLECTION)
                    // only the elected leader recomputes; failover is automatic
                    .mode(Mode.AUTO_RECOVER)
                    // resume missed order changes after a restart
                    .resumeStrategy(ResumeStrategy.PER_BATCH)
                    .pipeline(List.of())
                    .listener(OrderSummaryListener.BEAN_NAME)
                    .enabled(true)
                    .build());
        }

        if (pipelineRepository.findById("orders-summary").isEmpty()) {
            logger.info("Seeding demo pipeline template 'orders-summary'");
            pipelineRepository.save(PipelineTemplate.builder()
                    .name("orders-summary")
                    .stages(List.of(
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
                    .stages(List.of(
                            // {"_ph": "status"} is replaced with the value of the
                            // "status" variable at execution time.
                            Map.of("$match", Map.of("status", Map.of("_ph", "status"))),
                            Map.of("$sort", Map.of("createdAt", -1)),
                            Map.of("$limit", 20)))
                    .build());
        }

        // Initial computation so the dashboard has data before the first change.
        orderSummaryListener.recompute();
    }
}
