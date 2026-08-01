package com.mzinx.demo.session;

import java.time.Instant;

/**
 * A single connected browser, identified by its Spring Session (HTTP session)
 * id. This is what the messaging UI renders as an entry in the "who is online"
 * list and what a private channel is opened against.
 *
 * @param sessionId   the Spring Session id (stable per browser across reconnects)
 * @param displayName the nickname the user chose on load
 * @param channel     the private STOMP destination messages for this session are
 *                    delivered on ({@code /private/<sessionId>})
 * @param connectedAt when the browser's WebSocket first connected
 */
public record ActiveSession(String sessionId, String displayName, String channel, Instant connectedAt) {

    public static String channelFor(String sessionId) {
        return "/private/" + sessionId;
    }
}
