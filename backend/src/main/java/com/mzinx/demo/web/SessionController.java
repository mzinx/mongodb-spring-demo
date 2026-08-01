package com.mzinx.demo.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mzinx.demo.session.ActiveSession;
import com.mzinx.demo.session.PresenceListener;
import com.mzinx.demo.session.PresenceService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Exposes the current browser's Spring Session identity and the live roster of
 * active sessions that drives the private-messaging presence list.
 * <p>
 * The Spring Session (persisted in MongoDB via {@code @EnableMongoHttpSession})
 * gives each browser a stable id; the display name the user picks on load is
 * stored as a session attribute so it survives reconnects and is available to
 * the WebSocket handshake.
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final PresenceService presenceService;
    private final PresenceListener presenceListener;

    SessionController(PresenceService presenceService, PresenceListener presenceListener) {
        this.presenceService = presenceService;
        this.presenceListener = presenceListener;
    }

    /**
     * Returns (creating if necessary) the caller's Spring Session id, its
     * private channel destination and any previously stored display name.
     */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object name = session.getAttribute(PresenceService.ATTR_DISPLAY_NAME);
        return Map.of(
                "sessionId", session.getId(),
                "channel", ActiveSession.channelFor(session.getId()),
                "displayName", name != null ? name.toString() : "");
    }

    /**
     * Stores the chosen display name on the Spring Session (Spring Session
     * persists it to the {@code sessions} collection) so subsequent WebSocket
     * handshakes and the DB-queried presence roster show it.
     */
    @PostMapping("/name")
    public Map<String, Object> setName(@RequestBody Map<String, String> body, HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        String name = body.getOrDefault("displayName", "").trim();
        if (name.isEmpty()) name = "anonymous";
        session.setAttribute(PresenceService.ATTR_DISPLAY_NAME, name);
        // Re-broadcast so the new name appears everywhere; the roster is
        // re-queried from MongoDB inside broadcastPresence().
        presenceListener.broadcastPresence();
        return Map.of(
                "sessionId", session.getId(),
                "channel", ActiveSession.channelFor(session.getId()),
                "displayName", name);
    }

    /** Current active sessions, queried directly from the MongoDB sessions collection. */
    @GetMapping("/active")
    public List<ActiveSession> active() {
        return presenceService.activeSessions();
    }
}
