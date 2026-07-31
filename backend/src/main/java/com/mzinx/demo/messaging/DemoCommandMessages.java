package com.mzinx.demo.messaging;

import java.util.List;

import org.bson.Document;
import org.springframework.stereotype.Component;

import com.mzinx.mongodb.messaging.command.CommandMessages;
import com.mzinx.mongodb.messaging.model.Message;

/**
 * Demo-specific command {@link Message}s, layered on the library's generic
 * {@link CommandMessages} builder.
 * <p>
 * The reusable {@code mongodb-spring-message-queuing} library only knows about
 * the {@link CommandMessages.Type#REFRESH REFRESH} command; the concepts of
 * presence rosters and private channels belong to this demo, so their command
 * types live here. Every message still flows to the same command destination
 * (defaulted to {@link CommandMessages#commandPath()}, i.e.
 * {@code messaging.command-path} / {@code /cmd}) with the same
 * {@code {type, ...}} shape, keeping the wire format consistent.
 */
@Component
public class DemoCommandMessages {

    /** Command types specific to the demo application. */
    public static final class Type {
        /** Carries the live roster of active sessions ({@code {type, sessions}}). */
        public static final String PRESENCE = "PRESENCE";
        /** Announces a private channel every connected client should subscribe to. */
        public static final String LISTEN = "LISTEN";

        private Type() {
        }
    }

    private final CommandMessages commandMessages;

    public DemoCommandMessages(CommandMessages commandMessages) {
        this.commandMessages = commandMessages;
    }

    /**
     * Builds a {@code PRESENCE} command carrying the active-session roster,
     * targeted at the configured command destination.
     *
     * @param sessions the active sessions, each already shaped as a
     *                 {@link Document} ({@code {sessionId, displayName, channel}})
     */
    public Message presence(List<Document> sessions) {
        return commandMessages.command(Type.PRESENCE, new Document("sessions", sessions));
    }

    /**
     * Builds a {@code LISTEN} command announcing a private-channel destination
     * that every connected client should subscribe to, targeted at the
     * configured command destination.
     *
     * @param target the private channel destination (e.g. {@code /private/<id>})
     */
    public Message listen(String target) {
        return commandMessages.command(Type.LISTEN, new Document("target", target));
    }
}
