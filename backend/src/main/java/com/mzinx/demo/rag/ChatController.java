package com.mzinx.demo.rag;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * The RAG chatbot API backing the overlay chat GUI.
 * <p>
 * Conversation identity is the browser's Spring Session id (the same identity
 * used elsewhere in the demo), so each browser gets its own durable
 * conversation memory in the {@code chatMemory} collection.
 *
 * <ul>
 * <li>{@code POST /api/chat/ask} — ask a question; retrieves context, answers,
 * and appends the turn to memory</li>
 * <li>{@code GET  /api/chat/history} — this session's full conversation</li>
 * <li>{@code DELETE /api/chat/history} — clear this session's memory</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/ask")
    public ChatService.Answer ask(@RequestBody Map<String, String> body, HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        String question = body.getOrDefault("question", "");
        String provider = body.get("provider"); // optional; falls back to default
        String model = body.get("model");       // optional; falls back to provider default
        return chatService.ask(session.getId(), question, provider, model);
    }

    /**
     * Lists the available chat providers, each with its id, label, selectable
     * models and availability, plus the default provider id. Providers that are
     * disabled, missing a required key, or unreachable are omitted — so the UI
     * only ever shows usable options. The chat UI renders a provider dropdown and
     * a dependent model dropdown from this.
     */
    @GetMapping("/providers")
    public Map<String, Object> providers() {
        return Map.of(
                "default", chatService.defaultProvider(),
                "providers", chatService.providers());
    }

    @GetMapping("/history")
    public List<Document> history(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        return chatService.history(session.getId());
    }

    @DeleteMapping("/history")
    public Map<String, Object> clear(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        long deleted = chatService.clearHistory(session.getId());
        return Map.of("deleted", deleted);
    }
}
