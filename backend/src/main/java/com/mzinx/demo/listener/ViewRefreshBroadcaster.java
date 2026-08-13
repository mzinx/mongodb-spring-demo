package com.mzinx.demo.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.mzinx.demo.config.ConsolidationDemoSeeder;
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
 * materialized-view streams (e.g. the {@code orders-by-day/week/month} period
 * rollups; their configs are {@code runOn=BUSINESS}). The companion mongostream
 * console manages the configs but does not execute them, so this broadcaster is
 * only wired here. The recompute itself is authoritative; this broadcast is only
 * a hint to connected clients.
 * <p>
 * <b>Refresh the OUTPUT collection, not the source.</b> The event reports the
 * aggregation <em>source</em> ({@code getSourceCollection()}), which for all
 * three period streams is {@code unifiedOrders} — but their recompute writes
 * {@code ordersByDay}/{@code ordersByWeek}/{@code ordersByMonth}, not
 * {@code unifiedOrders}. Broadcasting a refresh for the source would therefore
 * fire three times for the wrong collection on a single {@code unifiedOrders}
 * change. We instead resolve the stream's output collection (via
 * {@link ConsolidationDemoSeeder#PERIOD_STREAM_OUTPUT}) so each recompute emits
 * exactly one refresh for the view it actually changed. (Changes to
 * {@code unifiedOrders} itself are already pushed on {@code /sync} by the
 * message-queuing live-data service, so no {@code /cmd} refresh is needed for it.)
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
        // The collection the recompute actually wrote — resolved from the stream
        // id, since the event only carries the (shared) source collection.
        String outputCollection = ConsolidationDemoSeeder.PERIOD_STREAM_OUTPUT.get(event.getStreamId());
        if (outputCollection == null) {
            // A materialized-view stream we don't have a mapping for: fall back to
            // the source collection (previous behavior) rather than dropping it.
            outputCollection = event.getSourceCollection();
        }
        logger.debug("View recomputed by stream '{}'; broadcasting refresh of '{}'",
                event.getStreamId(), outputCollection);
        this.messageService.broadcast(commandMessages.refresh(outputCollection));
    }
}
