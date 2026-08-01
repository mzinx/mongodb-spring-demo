package com.mzinx.demo.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Presence backed entirely by the Spring Session {@code sessions} collection in
 * MongoDB — there is no in-memory registry.
 * <p>
 * Liveness is derived from the session's own <em>keep-alive</em>: Spring Session
 * stores an {@code expireAt} timestamp on each document and MongoDB's TTL index
 * removes it once it lapses (see {@code maxInactiveIntervalInSeconds} on
 * {@link org.springframework.session.data.mongo.config.annotation.web.http.EnableMongoHttpSession}).
 * A session therefore counts as "online" for as long as it has not expired, and
 * every request the browser makes slides {@code expireAt} forward. This replaces
 * the earlier explicit {@code presenceConnected} flag, which could not be updated
 * reliably: Spring Session's request filter commits the <em>whole</em> session
 * document at the end of every HTTP request, and {@code MongoIndexedSessionRepository}
 * saves by full-document replace, so out-of-band writes to a single attribute
 * were clobbered by the concurrent handshake request's stale commit.
 * <p>
 * The chosen display name is still stored as a Spring Session attribute. Because
 * the demo uses {@link org.springframework.session.data.mongo.JacksonMongoSessionConverter},
 * it is persisted as queryable BSON under {@code attrs.presenceDisplayName}, so
 * the active-session roster is produced by querying the collection directly for
 * every non-expired document.
 */
@Service
public class PresenceService {
    /** Spring Session attribute name (persisted under {@code attrs.*} in Mongo). */
    public static final String ATTR_DISPLAY_NAME = "presenceDisplayName";

    private static final String COLLECTION = "sessions";
    // Field names as written by JacksonMongoSessionConverter (Jackson serializes
    // MongoSession's Java fields): epoch-millis longs, plus a Date expireAt.
    private static final String F_EXPIRE_AT = "expireAt";
    private static final String F_ACCESSED_MILLIS = "accessedMillis";

    /**
     * How long a session lingers in the roster after its WebSocket disconnects,
     * before MongoDB's TTL index reaps it. Long enough to ride out a brief blip
     * and reconnect (a reconnect's HTTP/handshake request slides {@code expireAt}
     * back out), short enough that a user who is truly gone drops off promptly.
     */
    private static final Duration DISCONNECT_GRACE = Duration.ofSeconds(30);

    private final MongoOperations mongoOperations;

    PresenceService(MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    /**
     * Shortens a session's keep-alive to a brief grace window
     * ({@link #DISCONNECT_GRACE}) after its WebSocket drops, so the browser leaves
     * the presence roster promptly rather than lingering until the full 30-minute
     * session TTL. Only ever <em>brings the expiry in</em>, never pushes it out,
     * and only for sessions still further out than the grace window — so it can't
     * fight a live session whose regular requests keep extending {@code expireAt}.
     * <p>
     * If the client reconnects during the grace window, its next request (the
     * WebSocket handshake calls {@code getSession(true)}) re-extends
     * {@code expireAt} through Spring Session's normal save path, so a transient
     * drop does not evict an otherwise-active user.
     * <p>
     * Written as a targeted {@code $set} on the single {@code expireAt} field
     * rather than by re-saving a whole {@code MongoSession}: that avoids the
     * full-document overwrite race that made the old {@code presenceConnected}
     * flag unreliable.
     *
     * @return {@code true} if a session's expiry was actually pulled in
     */
    public boolean expireSoon(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;

        Date graceCutoff = Date.from(Instant.now().plus(DISCONNECT_GRACE));
        // Only touch a non-expired session whose expiry is beyond the grace
        // window; if it's already due sooner, leave it alone.
        Query query = Query.query(Criteria.where("_id").is(sessionId)
                .and(F_EXPIRE_AT).gt(graceCutoff));
        Update update = new Update().set(F_EXPIRE_AT, graceCutoff);

        return mongoOperations.updateFirst(query, update, COLLECTION).getModifiedCount() > 0;
    }

    /**
     * The live roster, read straight from MongoDB: every session that has not yet
     * expired (i.e. still within its keep-alive window), newest access first.
     */
    public List<ActiveSession> activeSessions() {
        Query query = Query.query(Criteria.where(F_EXPIRE_AT).gt(new Date()));
        query.with(Sort.by(Sort.Direction.DESC, F_ACCESSED_MILLIS));

        return mongoOperations.find(query, Document.class, COLLECTION).stream()
                .map(this::toActiveSession)
                .toList();
    }

    private ActiveSession toActiveSession(Document doc) {
        String id = doc.getString("_id");
        Document attrs = doc.get("attrs", Document.class);
        String name = attrs != null ? attrs.getString(ATTR_DISPLAY_NAME) : null;
        // accessedMillis is an epoch-millis long written by the Jackson converter.
        Long accessedMillis = doc.get(F_ACCESSED_MILLIS, Number.class) != null
                ? doc.get(F_ACCESSED_MILLIS, Number.class).longValue() : null;
        Instant connectedAt = accessedMillis != null ? Instant.ofEpochMilli(accessedMillis) : Instant.now();
        return new ActiveSession(id, name != null ? name : "anonymous",
                ActiveSession.channelFor(id), connectedAt);
    }
}
