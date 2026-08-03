package com.mzinx.demo.config;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.mzinx.demo.listener.MaterializedViewListener;
import com.mzinx.mongodb.aggregation.dao.PipelineRepository;
import com.mzinx.mongodb.aggregation.model.PipelineTemplate;
import com.mzinx.mongodb.changestream.model.ChangeStream.Mode;
import com.mzinx.mongodb.changestream.model.ChangeStream.ResumeStrategy;
import com.mzinx.mongodb.changestream.model.ChangeStreamConfig;
import com.mzinx.mongodb.changestream.service.ChangeStreamConfigService;

/**
 * Seeds demo artifacts on first start:
 * <ul>
 * <li>the {@code order-summary} change stream config driving the generic
 * {@link MaterializedViewListener} (AUTO_RECOVER: one leader instance
 * precomputes, with automatic failover). Its {@code attributes.outputPipeline}
 * selects which pipeline the listener runs — seeded to the default below,
 * changeable at runtime from the Change streams page.</li>
 * <li>the default {@code orders-daily-summary} output pipeline template it
 * executes (the terminal {@code $merge} replaces documents by {@code _id})</li>
 * <li>two more pipeline templates for the Aggregations page</li>
 * </ul>
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    /** The change stream id (in this demo) that materializes the order summary. */
    public static final String ORDER_SUMMARY_STREAM_ID = "order-summary";
    public static final String ORDERS_DAILY_SUMMARY_PIPELINE = "orders-daily-summary";
    public static final String ORDERS_COLLECTION = "orders";
    public static final String ORDERS_SUMMARY_COLLECTION = "orderSummaries";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ChangeStreamConfigService changeStreamConfigService;
    private final PipelineRepository pipelineRepository;
    private final MaterializedViewListener materializedViewListener;
    

    DemoDataSeeder(ChangeStreamConfigService changeStreamConfigService, PipelineRepository pipelineRepository,
            MaterializedViewListener materializedViewListener) {
        this.changeStreamConfigService = changeStreamConfigService;
        this.pipelineRepository = pipelineRepository;
        this.materializedViewListener = materializedViewListener;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Clean up the config seeded by earlier demo versions; its listener
        // bean (eventRelay) no longer exists.
        if (changeStreamConfigService.findById("orders-demo") != null) {
            logger.info("Removing legacy demo change stream config 'orders-demo'");
            changeStreamConfigService.delete("orders-demo");
        }

        if (pipelineRepository.findById(ORDERS_DAILY_SUMMARY_PIPELINE).isEmpty()) {
            logger.info("Seeding pipeline template '{}'", ORDERS_DAILY_SUMMARY_PIPELINE);
            pipelineRepository.save(PipelineTemplate.builder()
                    .name(ORDERS_DAILY_SUMMARY_PIPELINE)
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
                                    // Recompute timestamp (informational; the dashboard shows it).
                                    "updatedAt", "$$NOW")),
                            Map.of("$merge", Map.of(
                                    "into", ORDERS_SUMMARY_COLLECTION,
                                    "on", "_id",
                                    "whenMatched", "replace",
                                    "whenNotMatched", "insert"))))
                    .build());
        }

        if (changeStreamConfigService.findById(ORDER_SUMMARY_STREAM_ID) == null) {
            logger.info("Seeding change stream config '{}'", ORDER_SUMMARY_STREAM_ID);
            changeStreamConfigService.save(ChangeStreamConfig.builder()
                    .id(ORDER_SUMMARY_STREAM_ID)
                    .collectionName(ORDERS_COLLECTION)
                    // only the elected leader recomputes; failover is automatic
                    .mode(Mode.AUTO_RECOVER)
                    // resume missed order changes after a restart
                    .resumeStrategy(ResumeStrategy.PER_BATCH)
                    .pipeline(List.of())
                    .listener(MaterializedViewListener.BEAN_NAME)
                    // The output pipeline is required and selectable at runtime (Change
                    // streams page); seed the default so the listener has a pipeline to run.
                    .attributes(new java.util.HashMap<>(Map.of(
                            MaterializedViewListener.ATTR_OUTPUT_PIPELINE,
                            ORDERS_DAILY_SUMMARY_PIPELINE)))
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
        ChangeStreamConfig summaryConfig = changeStreamConfigService.findById(ORDER_SUMMARY_STREAM_ID);
        materializedViewListener.recompute(ORDERS_COLLECTION, ORDER_SUMMARY_STREAM_ID,
                summaryConfig != null ? summaryConfig.getAttributes() : null);
    }
}
