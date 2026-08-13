package com.mzinx.demo.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.mzinx.mongodb.sink.model.MaterializedViewRecomputedEvent;
import com.mzinx.mongodb.messaging.command.CommandMessages;
import com.mzinx.mongodb.messaging.service.MessageService;

/**
 * Bridges the materialized-view module to the messaging layer: whenever a view
 * is recomputed (the {@code materializedViewListener} publishes a
 * {@link MaterializedViewRecomputedEvent}), broadcast a {@code REFRESH} command
 * on the command channel ({@code /cmd}) so live browser clients know to reload.
 * <p>
 * This lives in the demo — not in a library — because only the demo owns the
 * WebSocket connections to browsers, and the demo is the app that runs the
 * {@code order-summary} stream (its config is {@code runOn=BUSINESS}). The
 * companion mongostream console manages the config but does not execute it, so
 * this broadcaster is only wired here. The recompute itself is authoritative;
 * this broadcast is only a hint to connected clients.
 */
@Component
public class ViewRefreshBroadcaster {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final MessageService messageService;
    private final CommandMessages commandMessages;

    ViewRefreshBroadcaster(MessageService messageService, CommandMessages commandMessages) {
        this.messageService = messageService;
        this.commandMessages = commandMessages;
    }

    @EventListener
    public void onViewRecomputed(MaterializedViewRecomputedEvent event) {
        logger.debug("View recomputed for stream '{}'; broadcasting refresh of '{}'",
                event.getStreamId(), event.getSourceCollection());
        this.messageService.broadcast(commandMessages.refresh(event.getSourceCollection()));
    }
}
