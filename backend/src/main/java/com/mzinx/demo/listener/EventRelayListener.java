package com.mzinx.demo.listener;

import java.util.Date;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mzinx.mongodb.changestream.listener.ChangeStreamListener;

/**
 * Demo {@link ChangeStreamListener} that relays every change stream event to
 * WebSocket subscribers of the {@code /events} STOMP destination.
 * <p>
 * Select {@code eventRelay} as the listener when creating a change stream in
 * the UI to see its events appear live in the browser.
 */
@Component("eventRelay")
public class EventRelayListener implements ChangeStreamListener<Document> {

    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED).build();

    public static final String DESTINATION = "/events";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final SimpMessagingTemplate simpMessagingTemplate;

    EventRelayListener(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @Override
    public void execute(ChangeStreamDocument<Document> event) {
        try {
            Document payload = new Document();
            if (event.getOperationType() != null)
                payload.put("operationType", event.getOperationType().getValue());
            if (event.getNamespace() != null) {
                payload.put("database", event.getNamespace().getDatabaseName());
                payload.put("collection", event.getNamespace().getCollectionName());
            }
            if (event.getDocumentKey() != null)
                payload.put("documentKey", event.getDocumentKey());
            if (event.getFullDocument() != null)
                payload.put("fullDocument", event.getFullDocument());
            if (event.getFullDocumentBeforeChange() != null)
                payload.put("fullDocumentBeforeChange", event.getFullDocumentBeforeChange());
            UpdateDescription update = event.getUpdateDescription();
            if (update != null) {
                Document updateDoc = new Document();
                if (update.getUpdatedFields() != null)
                    updateDoc.put("updatedFields", update.getUpdatedFields());
                if (update.getRemovedFields() != null)
                    updateDoc.put("removedFields", update.getRemovedFields());
                payload.put("updateDescription", updateDoc);
            }
            if (event.getWallTime() != null)
                payload.put("wallTime", new Date(event.getWallTime().getValue()));
            payload.put("receivedAt", new Date());

            simpMessagingTemplate.convertAndSend(DESTINATION, payload.toJson(RELAXED));
        } catch (Exception e) {
            logger.error("Unable to relay change stream event", e);
        }
    }
}
