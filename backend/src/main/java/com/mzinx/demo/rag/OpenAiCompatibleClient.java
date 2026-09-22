package com.mzinx.demo.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.mzinx.demo.rag.RagProperties.Provider;

import lombok.extern.slf4j.Slf4j;

/**
 * A generic client for any <b>OpenAI-compatible</b> chat endpoint
 * ({@code POST /v1/chat/completions}). One client serves many backends —
 * LiteLLM (an OpenAI-compatible proxy), a local Ollama server (which also
 * exposes {@code /v1/...}), vLLM, and OpenAI itself — so new providers are added
 * by configuration alone.
 * <p>
 * It also discovers a provider's models from {@code GET /v1/models}, with a
 * fallback to Ollama's native {@code GET /api/tags} for older Ollama versions.
 */
@Component
@Slf4j
public class OpenAiCompatibleClient {

    private final RagProperties props;

    OpenAiCompatibleClient(RagProperties props) {
        this.props = props;
    }

    /** A single chat message in the {role, content} shape. */
    public record ChatMessage(String role, String content) {
    }

    private RestClient clientFor(Provider provider) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(provider.getTimeoutSeconds() * 1000);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(trimTrailingSlash(provider.getBaseUrl()))
                .requestFactory(factory);
        if (provider.hasKey())
            builder.defaultHeader("Authorization", "Bearer " + provider.getApiKey());
        return builder.build();
    }

    /**
     * Sends the conversation to the provider and returns the assistant's reply.
     *
     * @param provider the target provider config
     * @param model    the model id to use
     * @param system   the system prompt (knowledge-base context + instructions)
     * @param messages ordered user/assistant turns, ending with the new question
     */
    @SuppressWarnings("unchecked")
    public String chat(Provider provider, String model, String system, List<ChatMessage> messages) {
        List<Map<String, String>> payloadMessages = new ArrayList<>();
        if (system != null && !system.isBlank())
            payloadMessages.add(Map.of("role", "system", "content", system));
        for (ChatMessage m : messages)
            payloadMessages.add(Map.of("role", m.role(), "content", m.content()));

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", payloadMessages,
                "stream", false,
                "max_tokens", props.getMaxTokens());

        Map<String, Object> response = clientFor(provider).post()
                .uri("/v1/chat/completions")
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null)
            throw new IllegalStateException("empty response from " + provider.getId());
        Object choices = response.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> msg
                && msg.get("content") != null) {
            return msg.get("content").toString().strip();
        }
        throw new IllegalStateException("unexpected response shape from " + provider.getId() + ": " + response);
    }

    /** True if the provider's endpoint responds to a models probe. */
    public boolean isAvailable(Provider provider) {
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank())
            return false;
        try {
            clientFor(provider).get().uri("/v1/models").retrieve().body(Map.class);
            return true;
        } catch (RuntimeException e) {
            // Some Ollama builds only answer /api/tags; try that before giving up.
            try {
                clientFor(provider).get().uri("/api/tags").retrieve().body(Map.class);
                return true;
            } catch (RuntimeException ignored) {
                log.debug("Provider '{}' not reachable at {}: {}",
                        provider.getId(), provider.getBaseUrl(), e.getMessage());
                return false;
            }
        }
    }

    /**
     * Discovers the provider's model ids, merged with any statically configured
     * ones (config first, preserving order, de-duplicated). Never throws — an
     * unreachable provider just yields its static list.
     */
    @SuppressWarnings("unchecked")
    public List<String> discoverModels(Provider provider) {
        Set<String> models = new LinkedHashSet<>(provider.getModels());
        if (!provider.isDiscoverModels() || provider.getBaseUrl() == null || provider.getBaseUrl().isBlank())
            return new ArrayList<>(models);
        try {
            Map<String, Object> res = clientFor(provider).get().uri("/v1/models").retrieve().body(Map.class);
            if (res != null && res.get("data") instanceof List<?> data) {
                for (Object o : data)
                    if (o instanceof Map<?, ?> m && m.get("id") != null)
                        models.add(m.get("id").toString());
            }
        } catch (RuntimeException e) {
            // Fallback: Ollama's native tag listing.
            try {
                Map<String, Object> res = clientFor(provider).get().uri("/api/tags").retrieve().body(Map.class);
                if (res != null && res.get("models") instanceof List<?> data) {
                    for (Object o : data)
                        if (o instanceof Map<?, ?> m && m.get("name") != null)
                            models.add(m.get("name").toString());
                }
            } catch (RuntimeException ignored) {
                log.debug("Model discovery failed for '{}': {}", provider.getId(), e.getMessage());
            }
        }
        return new ArrayList<>(models);
    }

    private static String trimTrailingSlash(String url) {
        if (url == null)
            return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
