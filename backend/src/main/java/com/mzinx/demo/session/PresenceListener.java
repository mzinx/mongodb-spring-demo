package com.mzinx.demo.session;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.mzinx.demo.messaging.DemoCommandMessages;
import com.mzinx.mongodb.messaging.model.Message;
import com.mzinx.mongodb.messaging.service.MessageService;

/**
 * Reacts to WebSocket connect/disconnect to keep the presence roster fresh.
 * <p>
 * Presence liveness is derived from Spring Session's keep-alive/expiry, so
 * {@link PresenceService#activeSessions()} reads the current roster straight from
 * the {@code sessions} collection (every non-expired document). This listener
 * does two things on those STOMP lifecycle events:
 * <ul>
 * <li><b>on disconnect</b> — shortens the session's expiry to a brief grace
 * window ({@link PresenceService#expireSoon}), so a browser that closes (or whose
 * connection is declared dead by the broker's heartbeats after a network drop)
 * leaves the roster promptly instead of lingering for the full 30-minute session
 * TTL. A quick reconnect re-extends the expiry, so a transient blip doesn't evict
 * an active user;</li>
 * <li><b>on connect and disconnect</b> — broadcasts a {@code {type: "PRESENCE"}}
 * command on the configured command destination (defaulted inside
 * {@link DemoCommandMessages}) via {@link MessageService#broadcast(Message)},
 * carrying the freshly queried roster, so every connected client refreshes its
 * list without waiting for the next poll.</li>
 * </ul>
 * Detecting a dead connection after an <em>intermittent</em> network drop relies
 * on STOMP heartbeats, which the {@code mongodb-spring-message-queuing} library
 * enables by default (configurable via {@code messaging.heartbeat.*}). Without
 * them, only a clean disconnect fires {@link SessionDisconnectEvent}.
 */
@Component
public class PresenceListener {
    private static final Logger logger = LoggerFactory.getLogger(PresenceListener.class);

    private final PresenceService presenceService;
    private final MessageService messageService;
    private final DemoCommandMessages commandMessages;

    PresenceListener(PresenceService presenceService, MessageService messageService,
            DemoCommandMessages commandMessages) {
        this.presenceService = presenceService;
        this.messageService = messageService;
        this.commandMessages = commandMessages;
    }

    // NOTE: we listen to SessionConnectEvent (the CONNECT frame) rather than
    // SessionConnectedEvent (the CONNECTED ack). Only the former reliably carries
    // the WebSocket session attributes populated by SessionHandshakeInterceptor;
    // on SessionConnectedEvent getSessionAttributes() comes back null.
    @EventListener
    public void onConnect(SessionConnectEvent event) {
        broadcastPresence();
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = presenceSessionId(event);
        if (sessionId != null && presenceService.expireSoon(sessionId)) {
            logger.debug("Session {} disconnected; presence expiry brought forward", sessionId);
        }
        broadcastPresence();
    }

    /**
     * Pulls the browser's Spring Session id out of the WebSocket session
     * attributes populated by {@link SessionHandshakeInterceptor} at handshake.
     * Returns {@code null} if it isn't available (e.g. attributes not carried).
     */
    private String presenceSessionId(SessionDisconnectEvent event) {
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> attrs = sha.getSessionAttributes();
        if (attrs == null) return null;
        Object id = attrs.get(SessionHandshakeInterceptor.SESSION_ID);
        return id != null ? id.toString() : null;
    }

    /**
     * Pushes the current roster (queried from MongoDB) to every client on the
     * command destination, reusing the message-queuing broadcast plumbing so the
     * presence notification travels the same path as any other command message.
     */
    public void broadcastPresence() {
        List<Document> sessions = presenceService.activeSessions().stream()
                .map(s -> new Document(Map.of(
                        "sessionId", s.sessionId(),
                        "displayName", s.displayName(),
                        "channel", s.channel())))
                .toList();
        messageService.broadcast(commandMessages.presence(sessions));
    }
}
