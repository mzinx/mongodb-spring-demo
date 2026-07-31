package com.mzinx.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.mongo.AbstractMongoSessionConverter;
import org.springframework.session.data.mongo.JacksonMongoSessionConverter;
import org.springframework.session.data.mongo.config.annotation.web.http.EnableMongoHttpSession;

/**
 * Enables Spring Session backed by MongoDB.
 * <p>
 * Every browser that hits the backend receives an {@code HttpSession} whose
 * state is persisted in the {@code sessions} collection instead of the servlet
 * container's in-memory store. Because the sessions live in MongoDB they are
 * shared across all backend instances and survive restarts, and they give the
 * demo a durable identity per browser that the messaging presence list keys off
 * of.
 * <p>
 * {@code maxInactiveIntervalInSeconds} controls how long an idle session lives
 * before MongoDB's TTL index expires it (30 minutes here). This interval doubles
 * as the presence keep-alive window: the messaging "active sessions" roster is
 * simply every session that has not yet expired, so a browser stays "online" for
 * up to this long after its last request. Each request the browser makes slides
 * the expiry forward.
 */
@Configuration
@EnableMongoHttpSession(maxInactiveIntervalInSeconds = 1800, collectionName = "sessions")
public class SessionConfig {

    /**
     * Store session attributes as native, queryable BSON (under {@code attrs.*})
     * instead of the default JDK-serialized binary blob.
     * <p>
     * This lets the messaging presence feature read the roster <em>directly from
     * the database</em>: it queries the {@code sessions} collection for every
     * non-expired document and reads each one's {@code attrs.presenceDisplayName}
     * — no in-memory registry required.
     */
    @Bean
    AbstractMongoSessionConverter mongoSessionConverter() {
        return new JacksonMongoSessionConverter();
    }
}
