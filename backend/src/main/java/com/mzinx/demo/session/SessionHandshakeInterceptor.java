package com.mzinx.demo.session;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Copies the browser's Spring Session (HTTP session) id and chosen display name
 * into the WebSocket session attributes at handshake time, so the STOMP
 * connect/disconnect listeners can associate each live socket with a persistent
 * session identity from Spring Session.
 */
@Component
public class SessionHandshakeInterceptor implements HandshakeInterceptor {

    public static final String SESSION_ID = "presenceSessionId";
    public static final String DISPLAY_NAME = "presenceDisplayName";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest http = servletRequest.getServletRequest();
            // getSession() forces creation so the browser always has a stable,
            // MongoDB-backed Spring Session id even before it calls the REST API.
            HttpSession session = http.getSession(true);
            attributes.put(SESSION_ID, session.getId());
            Object name = session.getAttribute(DISPLAY_NAME);
            attributes.put(DISPLAY_NAME, name != null ? name.toString() : "anonymous");
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }
}
