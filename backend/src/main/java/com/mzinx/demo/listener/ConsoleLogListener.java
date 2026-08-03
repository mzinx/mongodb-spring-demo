package com.mzinx.demo.listener;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mzinx.mongodb.changestream.listener.ChangeStreamListener;

/**
 * Minimal demo {@link ChangeStreamListener} that only logs events to the
 * application console. Useful to demonstrate that multiple listener beans can
 * be targeted by change stream configs.
 */
@Component("consoleLog")
public class ConsoleLogListener implements ChangeStreamListener<Document> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void onEvent(String streamId, java.util.Map<String, Object> attributes,
            ChangeStreamDocument<Document> event) {
        logger.info("[consoleLog] stream={} {} on {} key={}",
                streamId,
                event.getOperationType() != null ? event.getOperationType().getValue() : "?",
                event.getNamespace(),
                event.getDocumentKey());
    }
}
