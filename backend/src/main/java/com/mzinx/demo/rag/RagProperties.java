package com.mzinx.demo.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration for the RAG chatbot, bound from the {@code rag.*} properties in
 * {@code application.properties}.
 * <p>
 * Chat backends are defined as a <b>list of providers</b> ({@link Provider}), so
 * new providers (a local Ollama server, a LiteLLM proxy, any OpenAI-compatible
 * endpoint, or hosted Anthropic) are added purely by editing configuration. Each
 * provider exposes one or more models; models can be listed statically and/or
 * auto-discovered from the provider at runtime.
 *
 * @see com.mzinx.demo.rag.KnowledgeService
 * @see com.mzinx.demo.rag.ChatService
 * @see com.mzinx.demo.rag.VectorIndexInitializer
 */
@Component
@ConfigurationProperties(prefix = "rag")
@Getter
@Setter
public class RagProperties {

    /** Collection holding the knowledge-base chunks (one document per chunk). */
    private String knowledgeCollection = "knowledgeBase";

    /** Collection holding per-session conversation memory (one doc per turn). */
    private String memoryCollection = "chatMemory";

    /** Name of the Atlas Vector Search automatic-embedding index. */
    private String vectorIndex = "knowledge_vector_index";

    /** Voyage AI model Atlas uses for automatic embedding of chunks and queries. */
    private String embeddingModel = "voyage-4";

    /** Create the vector index on startup when missing (Atlas only). */
    private boolean autoCreateIndex = true;

    /** Number of chunks to retrieve per query. */
    private int topK = 5;

    /** Candidate pool size for approximate nearest-neighbour search. */
    private int numCandidates = 100;

    /** Target chunk length (characters) when splitting extracted document text. */
    private int chunkSize = 1200;

    /** Overlap (characters) between consecutive chunks, preserving context. */
    private int chunkOverlap = 150;

    /** Maximum tokens for a chat completion. */
    private int maxTokens = 1024;

    /** How many prior turns to replay to the model as conversation memory. */
    private int memoryWindow = 10;

    /**
     * Default provider id to preselect in the UI. When blank, the first
     * <em>available</em> provider is used.
     */
    private String defaultProvider = "";

    /** The configured chat providers. */
    private List<Provider> providers = new ArrayList<>();

    /** Provider kinds understood by {@link ChatService}. */
    public enum Type {
        /** Hosted Anthropic, via the native Anthropic SDK. */
        ANTHROPIC,
        /** Any OpenAI-compatible {@code /v1/chat/completions} endpoint (LiteLLM, Ollama, vLLM, …). */
        OPENAI
    }

    /** A single chat backend. */
    @Getter
    @Setter
    public static class Provider {
        /** Stable id used in requests and memory records, e.g. {@code ollama}. */
        private String id;

        /** Human-friendly label shown in the UI. Defaults to the id. */
        private String label;

        /** How to talk to it: {@code ANTHROPIC} or {@code OPENAI} (OpenAI-compatible). */
        private Type type = Type.OPENAI;

        /**
         * Base URL of the endpoint. For OPENAI providers this is the root the
         * client appends {@code /v1/chat/completions} to (e.g.
         * {@code http://localhost:11434} for Ollama, {@code http://localhost:4000}
         * for LiteLLM). Ignored for ANTHROPIC (the SDK targets Anthropic's API).
         */
        private String baseUrl;

        /**
         * API key. May reference an env var via property placeholders. A provider
         * that <em>requires</em> a key (see {@link #requiresKey}) is hidden when
         * this is blank — this is how Anthropic disappears with no key set.
         */
        private String apiKey = "";

        /** When true, the provider is unavailable (hidden) unless a key is set. */
        private boolean requiresKey = false;

        /** Statically configured models. Auto-discovery is merged on top. */
        private List<String> models = new ArrayList<>();

        /** Attempt to auto-discover models from the provider at runtime. */
        private boolean discoverModels = true;

        /** Request timeout (seconds); local/proxy generation can be slow. */
        private int timeoutSeconds = 120;

        /** Explicit enable/disable switch (independent of key presence). */
        private boolean enabled = true;

        public String displayLabel() {
            return (label == null || label.isBlank()) ? id : label;
        }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
