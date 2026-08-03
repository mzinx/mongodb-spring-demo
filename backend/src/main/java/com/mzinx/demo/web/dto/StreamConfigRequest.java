package com.mzinx.demo.web.dto;

import java.util.List;
import java.util.Map;

import org.bson.Document;

import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.client.model.changestream.FullDocumentBeforeChange;
import com.mzinx.mongodb.changestream.model.ChangeStream.Mode;
import com.mzinx.mongodb.changestream.model.ChangeStream.ResumeStrategy;
import com.mzinx.mongodb.changestream.model.ChangeStreamConfig;

/**
 * JSON payload for creating/updating a change stream definition from the UI,
 * mapped onto the library's {@link ChangeStreamConfig}.
 */
public record StreamConfigRequest(
        String id,
        String collectionName,
        Mode mode,
        Integer batchSize,
        Long maxAwaitTime,
        ResumeStrategy resumeStrategy,
        Long checkpointInterval,
        FullDocument fullDocument,
        FullDocumentBeforeChange fullDocumentBeforeChange,
        List<Map<String, Object>> pipeline,
        String listener,
        Boolean enabled,
        /** Free-form, listener-defined settings (e.g. {@code outputPipeline}). */
        Map<String, Object> attributes) {

    public ChangeStreamConfig toConfig() {
        return ChangeStreamConfig.builder()
                .id(id)
                .collectionName(collectionName == null || collectionName.isBlank() ? null : collectionName)
                .mode(mode == null ? Mode.BROADCAST : mode)
                .batchSize(batchSize)
                .maxAwaitTime(maxAwaitTime)
                .resumeStrategy(resumeStrategy == null ? ResumeStrategy.NONE : resumeStrategy)
                .checkpointInterval(checkpointInterval)
                .fullDocument(fullDocument)
                .fullDocumentBeforeChange(fullDocumentBeforeChange)
                .pipeline(pipeline == null ? List.of() : pipeline.stream().map(Document::new).toList())
                .listener(listener)
                .enabled(enabled == null || enabled)
                .attributes(attributes)
                .build();
    }
}
