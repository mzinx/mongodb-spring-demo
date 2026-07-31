package com.mzinx.demo.session;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;
import org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler;
import org.springframework.web.socket.sockjs.transport.TransportHandlingSockJsService;

/**
 * Attaches {@link SessionHandshakeInterceptor} to the {@code /ws} STOMP endpoint
 * that the {@code mongodb-spring-message-queuing} library registers.
 * <p>
 * The library owns the endpoint registration, so rather than re-registering
 * {@code /ws} (which would clash with the library's mapping) we look up the
 * already-built {@code stompWebSocketHandlerMapping} once all singletons are
 * initialized and add our handshake interceptor to the existing raw-WebSocket
 * and SockJS request handlers. This lets the browser's Spring Session id flow
 * into the WebSocket session attributes without modifying the library.
 * <p>
 * Using {@link SmartInitializingSingleton} (rather than a {@code BeanPostProcessor})
 * guarantees the handler mapping's URL map is fully populated before we mutate it.
 */
@Configuration
public class WebSocketHandshakeConfig {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHandshakeConfig.class);

    @Bean
    SmartInitializingSingleton stompHandshakeInterceptorRegistrar(
            ApplicationContext context, SessionHandshakeInterceptor interceptor) {
        return () -> {
            AbstractUrlHandlerMapping mapping;
            try {
                mapping = context.getBean("stompWebSocketHandlerMapping", AbstractUrlHandlerMapping.class);
            } catch (Exception ex) {
                log.warn("stompWebSocketHandlerMapping not found; presence handshake interceptor NOT attached", ex);
                return;
            }
            int attached = 0;
            for (Object handler : mapping.getHandlerMap().values()) {
                if (handler instanceof WebSocketHttpRequestHandler h) {
                    List<HandshakeInterceptor> list = new ArrayList<>(h.getHandshakeInterceptors());
                    list.add(interceptor);
                    h.setHandshakeInterceptors(list);
                    attached++;
                } else if (handler instanceof SockJsHttpRequestHandler s
                        && s.getSockJsService() instanceof TransportHandlingSockJsService service) {
                    List<HandshakeInterceptor> list = new ArrayList<>(service.getHandshakeInterceptors());
                    list.add(interceptor);
                    service.setHandshakeInterceptors(list);
                    attached++;
                }
            }
            log.info("Presence handshake interceptor attached to {} WebSocket handler(s)", attached);
        };
    }
}
