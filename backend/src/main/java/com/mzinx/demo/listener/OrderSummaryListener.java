package com.mzinx.demo.listener;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mzinx.mongodb.aggregation.dao.PipelineRepository;
import com.mzinx.mongodb.aggregation.model.AggregationSpec;
import com.mzinx.mongodb.aggregation.service.AggregationService;
import com.mzinx.mongodb.changestream.listener.ChangeStreamListener;
import com.mzinx.mongodb.messaging.command.CommandMessages;
import com.mzinx.mongodb.messaging.service.MessageService;

/**
 * {@link ChangeStreamListener} precomputing the daily order summary.
 * <p>
 * Every change on the {@code orders} collection (delivered through the
 * {@code order-summary} change stream, which runs in {@code AUTO_RECOVER}
 * mode so only the current leader instance recomputes) re-runs the
 * {@code orders-daily-summary} pipeline template via the aggregation library.
 * The pipeline groups orders per day and {@code $merge}s the result into the
 * {@link #SUMMARY_COLLECTION} collection, which backs the dashboard page and
 * is itself watched by the message-queuing live-data service, so dashboards
 * refresh in real time.
 * <p>
 * The recompute is idempotent (full recompute + {@code $merge} keyed by day);
 * a {@code runId} variable is substituted into the pipeline via the
 * {@code {"_ph": "runId"}} placeholder, and summaries of days that no longer
 * exist (all orders deleted) are removed after each run by comparing it.
 */
@Component(OrderSummaryListener.BEAN_NAME)
public class OrderSummaryListener implements ChangeStreamListener<Document> {

    public static final String BEAN_NAME = "orderSummaryListener";
    public static final String SUMMARY_COLLECTION = "orderSummaries";
    public static final String PIPELINE_NAME = "orders-daily-summary";
    public static final String SOURCE_COLLECTION = "orders";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final PipelineRepository pipelineRepository;
    private final AggregationService aggregationService;
    private final MessageService messageService;
    private final MongoTemplate mongoTemplate;
    private final CommandMessages commandMessages;

    OrderSummaryListener(PipelineRepository pipelineRepository, AggregationService aggregationService,
            MessageService messageService, CommandMessages commandMessages,
            MongoTemplate mongoTemplate) {
        this.pipelineRepository = pipelineRepository;
        this.aggregationService = aggregationService;
        this.messageService = messageService;
        this.commandMessages = commandMessages;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void onEvent(ChangeStreamDocument<Document> event) {
        logger.info("Order change detected ({}), recomputing daily summary",
                event.getOperationType() != null ? event.getOperationType().getValue() : "?");
        recompute();

        this.messageService.broadcast(commandMessages.refresh(event.getNamespace().getCollectionName()));
    }

    /** Recomputes the whole {@code orderSummaries} collection. */
    public synchronized void recompute() {
        List<Document> stages = pipelineRepository.findById(PIPELINE_NAME)
                .map(template -> template.getStages().stream().map(Document::new).toList())
                .orElse(null);
        if (stages == null) {
            logger.warn("Pipeline template '{}' not found, skipping summary recompute", PIPELINE_NAME);
            return;
        }
        long runId = System.currentTimeMillis();
        aggregationService.execute(AggregationSpec.of(SOURCE_COLLECTION, stages), Map.of("runId", runId));
        // Drop summaries not touched by this run (days whose orders were all deleted).
        mongoTemplate.getCollection(SUMMARY_COLLECTION).deleteMany(Filters.ne("runId", runId));
    }
}
