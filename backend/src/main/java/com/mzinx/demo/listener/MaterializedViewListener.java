package com.mzinx.demo.listener;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mzinx.mongodb.aggregation.dao.PipelineRepository;
import com.mzinx.mongodb.aggregation.model.AggregationSpec;
import com.mzinx.mongodb.aggregation.model.PipelineTemplate;
import com.mzinx.mongodb.aggregation.service.AggregationService;
import com.mzinx.mongodb.changestream.listener.ChangeStreamListener;
import com.mzinx.mongodb.messaging.command.CommandMessages;
import com.mzinx.mongodb.messaging.service.MessageService;

/**
 * Generic change-stream processor that maintains a <em>materialized view</em>:
 * whenever the watched source collection changes, it re-runs a configured
 * aggregation pipeline whose final stage {@code $merge}s the result into an
 * output collection (replacing documents by {@code _id}).
 * <p>
 * The listener is fully generic — nothing about a particular view is hardcoded.
 * The aggregation source is the collection that produced the event, and the
 * pipeline to run is taken from the {@code attributes.outputPipeline} of the
 * change stream that triggered it (the {@link PipelineTemplate} id in the
 * {@code _pipelines} collection). One bean can therefore back many views, each
 * configured by its own change stream.
 * <p>
 * The recompute is a full recompute keyed by {@code _id} via the pipeline's
 * terminal {@code $merge} ({@code whenMatched: replace}). Removing output
 * documents whose source data has disappeared is out of scope here — use a MongoDB
 * TTL index on the output collection for that housekeeping.
 */
@Component(MaterializedViewListener.BEAN_NAME)
public class MaterializedViewListener implements ChangeStreamListener<Document> {

    public static final String BEAN_NAME = "materializedViewListener";

    /** Attribute key naming the output pipeline to run (on the change stream config). */
    public static final String ATTR_OUTPUT_PIPELINE = "outputPipeline";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final PipelineRepository pipelineRepository;
    private final AggregationService aggregationService;
    private final MessageService messageService;
    private final CommandMessages commandMessages;

    MaterializedViewListener(PipelineRepository pipelineRepository, AggregationService aggregationService,
            MessageService messageService, CommandMessages commandMessages) {
        this.pipelineRepository = pipelineRepository;
        this.aggregationService = aggregationService;
        this.messageService = messageService;
        this.commandMessages = commandMessages;
    }

    @Override
    public void onEvent(String streamId, Map<String, Object> attributes, ChangeStreamDocument<Document> event) {
        logger.info("Source change detected on stream '{}' ({}), recomputing view",
                streamId, event.getOperationType() != null ? event.getOperationType().getValue() : "?");
        // Use the attributes delivered with the event — no per-event config lookup.
        recompute(event.getNamespace().getCollectionName(), streamId, attributes);

        this.messageService.broadcast(commandMessages.refresh(event.getNamespace().getCollectionName()));
    }

    /**
     * Recomputes the whole output collection for the given change stream using the
     * supplied attributes (delivered with the change stream event, or passed by a
     * direct caller such as the initial seed build).
     *
     * @throws IllegalStateException if the stream has no {@code outputPipeline}
     *                               attribute, or it names a pipeline that does not
     *                               exist — both are misconfiguration and must be
     *                               fixed before the listener can run.
     */
    public synchronized void recompute(String sourceCollection, String streamId, Map<String, Object> attributes) {
        String pipelineName = attr(attributes, ATTR_OUTPUT_PIPELINE);
        if (pipelineName.isEmpty())
            throw new IllegalStateException("Change stream '" + streamId + "' is missing the required '"
                    + ATTR_OUTPUT_PIPELINE + "' attribute");

        List<Document> stages = pipelineRepository.findById(pipelineName)
                .map(template -> template.getStages().stream().map(Document::new).toList())
                .orElseThrow(() -> new IllegalStateException("Output pipeline '" + pipelineName
                        + "' configured on change stream '" + streamId + "' does not exist"));

        aggregationService.execute(AggregationSpec.of(sourceCollection, stages));
    }

    private static String attr(Map<String, Object> attrs, String key) {
        Object v = attrs != null ? attrs.get(key) : null;
        return v instanceof String s && !s.isBlank() ? s : "";
    }
}
