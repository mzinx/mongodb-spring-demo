package com.mzinx.demo.rag;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mzinx.demo.rag.RagProperties.Provider;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The retrieval-augmented generation core.
 * <p>
 * On each question it:
 * <ol>
 * <li><b>Retrieves</b> the most relevant knowledge-base chunks with a MongoDB
 * Atlas {@code $vectorSearch} that uses automatic embedding — the raw question
 * text is passed as {@code query} and Atlas embeds it (Voyage AI) server-side,
 * so no embedding API call is made here.</li>
 * <li><b>Remembers</b> recent turns of this session's conversation, read back
 * from the {@code chatMemory} collection, and replays them to the model.</li>
 * <li><b>Generates</b> an answer with the user-selected <em>provider</em> and
 * <em>model</em>, grounding it in the retrieved context. Providers are defined
 * in configuration ({@link RagProperties.Provider}); Anthropic uses its native
 * SDK, all other providers speak the OpenAI-compatible protocol.</li>
 * <li><b>Persists</b> the turn (question, answer, provider, model and the
 * sources used) back to {@code chatMemory}, keyed by the Spring Session id.</li>
 * </ol>
 */
@Service
@Slf4j
public class ChatService {

    private final MongoTemplate mongoTemplate;
    private final RagProperties props;
    private final OpenAiCompatibleClient openAiClient;
    private AnthropicClient anthropic;

    ChatService(MongoTemplate mongoTemplate, RagProperties props, OpenAiCompatibleClient openAiClient) {
        this.mongoTemplate = mongoTemplate;
        this.props = props;
        this.openAiClient = openAiClient;
    }

    /**
     * Initialises the Anthropic SDK client only when an enabled Anthropic
     * provider has a usable key (or one is present in the environment). With no
     * key the client stays null, so the Anthropic provider is reported
     * unavailable and hidden from the UI. Never fails startup.
     */
    @PostConstruct
    void init() {
        Provider anthropicProvider = props.getProviders().stream()
                .filter(p -> p.isEnabled() && p.getType() == RagProperties.Type.ANTHROPIC)
                .findFirst()
                .orElse(null);
        if (anthropicProvider == null)
            return;
        boolean envKey = System.getenv("ANTHROPIC_API_KEY") != null
                && !System.getenv("ANTHROPIC_API_KEY").isBlank();
        if (!anthropicProvider.hasKey() && !envKey) {
            log.info("No Anthropic API key configured — Anthropic provider will be hidden.");
            return;
        }
        try {
            this.anthropic = anthropicProvider.hasKey()
                    ? AnthropicOkHttpClient.builder().apiKey(anthropicProvider.getApiKey()).build()
                    : AnthropicOkHttpClient.fromEnv();
            log.info("Anthropic client initialised.");
        } catch (RuntimeException e) {
            log.warn("Anthropic client not initialised ({}); provider hidden.", e.getMessage());
            this.anthropic = null;
        }
    }

    /** One retrieved knowledge-base chunk with its relevance score. */
    public record Source(String fileName, String docId, int chunkIndex, double score, String text) {
    }

    /** The full answer returned to the UI. */
    public record Answer(String question, String answer, List<Source> sources, boolean grounded,
            String provider, String model) {
    }

    /** A provider plus its (discovered + configured) models, for the UI. */
    public record ProviderInfo(String id, String label, List<String> models, boolean available) {
    }

    // ------------------------------------------------------------ provider view

    /**
     * Lists providers that are enabled and usable, each with its models. A
     * provider that requires a key but has none, is disabled, or is unreachable
     * (for endpoint providers) is omitted entirely — so, e.g., Anthropic simply
     * doesn't appear when no API key is configured.
     */
    public List<ProviderInfo> providers() {
        List<ProviderInfo> out = new ArrayList<>();
        for (Provider p : props.getProviders()) {
            if (!p.isEnabled())
                continue;
            boolean available;
            List<String> models;
            if (p.getType() == RagProperties.Type.ANTHROPIC) {
                // Availability is decided by whether the SDK client initialised,
                // which already accounts for a config key OR an env-var key.
                available = anthropic != null;
                models = p.getModels();
            } else {
                if (p.isRequiresKey() && !p.hasKey())
                    continue;
                available = openAiClient.isAvailable(p);
                models = openAiClient.discoverModels(p);
            }
            if (!available)
                continue;
            if (models.isEmpty())
                continue; // nothing selectable
            out.add(new ProviderInfo(p.getId(), p.displayLabel(), models, true));
        }
        return out;
    }

    /** The provider id to preselect: configured default, else first available. */
    public String defaultProvider() {
        List<ProviderInfo> available = providers();
        String configured = props.getDefaultProvider();
        if (configured != null && !configured.isBlank())
            for (ProviderInfo p : available)
                if (p.id().equals(configured))
                    return configured;
        return available.isEmpty() ? "" : available.get(0).id();
    }

    // -------------------------------------------------------------------- ask

    /** Answers a question for a given session, updating that session's memory. */
    public Answer ask(String sessionId, String question, String providerId, String model) {
        String q = question == null ? "" : question.strip();
        if (q.isEmpty())
            throw new IllegalArgumentException("Question must not be empty.");

        Provider provider = resolveProvider(providerId);
        String chosenModel = resolveModel(provider, model);

        List<Source> sources = retrieve(q);
        List<Document> history = recentHistory(sessionId);

        String answer = generate(provider, chosenModel, q, sources, history);

        boolean grounded = !sources.isEmpty();
        String providerLabel = provider == null ? "none" : provider.getId();
        saveTurn(sessionId, q, answer, sources, providerLabel, chosenModel);
        return new Answer(q, answer, sources, grounded, providerLabel, chosenModel);
    }

    /** Resolves a provider id to its config, falling back to the default. */
    private Provider resolveProvider(String requested) {
        String want = (requested == null || requested.isBlank()) ? defaultProvider() : requested.trim();
        return props.getProviders().stream()
                .filter(p -> p.isEnabled() && p.getId().equalsIgnoreCase(want))
                .findFirst()
                .orElse(null);
    }

    /** Picks the requested model if the provider offers it, else its first model. */
    private String resolveModel(Provider provider, String requested) {
        if (provider == null)
            return requested == null ? "" : requested;
        List<String> models = provider.getType() == RagProperties.Type.ANTHROPIC
                ? provider.getModels()
                : openAiClient.discoverModels(provider);
        if (requested != null && !requested.isBlank() && models.contains(requested))
            return requested;
        // Trust an explicit request even if not in the discovered list (a model
        // may be pullable on demand, e.g. Ollama), but prefer a known one.
        if (requested != null && !requested.isBlank())
            return requested;
        return models.isEmpty() ? "" : models.get(0);
    }

    // ---------------------------------------------------------------- retrieval

    /**
     * Runs the automatic-embedding vector search. Returns an empty list (rather
     * than throwing) when the index/feature is unavailable so the chat still
     * responds on non-Atlas deployments.
     */
    private List<Source> retrieve(String query) {
        Document vectorSearch = new Document("$vectorSearch", new Document()
                .append("index", props.getVectorIndex())
                .append("path", "text")
                // Automatic embedding: pass the raw query text, Atlas embeds it.
                .append("query", query)
                .append("numCandidates", props.getNumCandidates())
                .append("limit", props.getTopK()));

        Document project = new Document("$project", new Document()
                .append("text", 1)
                .append("fileName", 1)
                .append("docId", 1)
                .append("chunkIndex", 1)
                .append("score", new Document("$meta", "vectorSearchScore")));

        List<Source> out = new ArrayList<>();
        try {
            mongoTemplate.getCollection(props.getKnowledgeCollection())
                    .aggregate(List.of(vectorSearch, project))
                    .forEach(d -> out.add(new Source(
                            d.getString("fileName"),
                            d.getString("docId"),
                            d.get("chunkIndex") instanceof Number n ? n.intValue() : 0,
                            d.get("score") instanceof Number s ? s.doubleValue() : 0.0,
                            d.getString("text"))));
        } catch (RuntimeException e) {
            log.warn("Vector search unavailable ({}). Returning no context — ensure the app "
                    + "targets Atlas and index '{}' is READY.", e.getMessage(), props.getVectorIndex());
        }
        return out;
    }

    // ------------------------------------------------------------------- memory

    /** Reads the most recent turns for this session, oldest-first for replay. */
    private List<Document> recentHistory(String sessionId) {
        List<Document> turns = new ArrayList<>();
        mongoTemplate.getCollection(props.getMemoryCollection())
                .aggregate(List.of(
                        Aggregates.match(Filters.eq("sessionId", sessionId)),
                        Aggregates.sort(Sorts.descending("createdAt")),
                        Aggregates.limit(props.getMemoryWindow())))
                .forEach(turns::add);
        // Oldest first so the model reads the conversation in order.
        java.util.Collections.reverse(turns);
        return turns;
    }

    /** Full conversation history for a session (for the chat UI to render). */
    public List<Document> history(String sessionId) {
        List<Document> turns = new ArrayList<>();
        mongoTemplate.getCollection(props.getMemoryCollection())
                .find(Filters.eq("sessionId", sessionId))
                .sort(Sorts.ascending("createdAt"))
                .forEach(turns::add);
        return turns;
    }

    /** Clears a session's conversation memory. */
    public long clearHistory(String sessionId) {
        return mongoTemplate.getCollection(props.getMemoryCollection())
                .deleteMany(Filters.eq("sessionId", sessionId)).getDeletedCount();
    }

    private void saveTurn(String sessionId, String question, String answer, List<Source> sources,
            String provider, String model) {
        List<Document> src = sources.stream()
                .map(s -> new Document("fileName", s.fileName())
                        .append("docId", s.docId())
                        .append("chunkIndex", s.chunkIndex())
                        .append("score", s.score()))
                .collect(Collectors.toList());
        Document turn = new Document("_id", UUID.randomUUID().toString())
                .append("sessionId", sessionId)
                .append("question", question)
                .append("answer", answer)
                .append("sources", src)
                .append("provider", provider)
                .append("model", model)
                .append("createdAt", new Date());
        mongoTemplate.getCollection(props.getMemoryCollection()).insertOne(turn);
    }

    // --------------------------------------------------------------- generation

    /** Routes generation to the selected provider, degrading gracefully. */
    private String generate(Provider provider, String model, String question,
            List<Source> sources, List<Document> history) {
        String system = buildSystemPrompt(sources);
        if (provider == null) {
            log.warn("No usable chat provider; returning retrieved context only.");
            return fallbackAnswer(sources);
        }
        try {
            if (provider.getType() == RagProperties.Type.ANTHROPIC)
                return generateAnthropic(model, system, question, history);
            return generateOpenAi(provider, model, system, question, history);
        } catch (RuntimeException e) {
            log.error("{} completion failed: {}", provider.getId(), e.getMessage());
            return "Sorry, I couldn't generate an answer with " + provider.getId()
                    + " (" + e.getMessage() + "). " + fallbackAnswer(sources);
        }
    }

    /** Shared RAG system prompt: instructions plus the retrieved context. */
    private String buildSystemPrompt(List<Source> sources) {
        String context = sources.isEmpty()
                ? "(no relevant documents were found in the knowledge base)"
                : sources.stream()
                        .map(s -> "[" + s.fileName() + " #" + s.chunkIndex() + "]\n" + s.text())
                        .collect(Collectors.joining("\n\n---\n\n"));
        return """
                You are a helpful assistant answering questions using the provided knowledge-base context.
                Ground your answer in the context below. If the context does not contain the answer, say you
                don't have that information in the knowledge base rather than guessing. Cite the source file
                names you used in brackets, e.g. [handbook.pdf].

                Knowledge-base context:
                %s
                """.formatted(context);
    }

    private String generateAnthropic(String model, String system, String question, List<Document> history) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(Model.of(model))
                .maxTokens(props.getMaxTokens())
                .system(system);

        // Replay prior turns as memory.
        for (Document turn : history) {
            String q = turn.getString("question");
            String a = turn.getString("answer");
            if (q != null)
                params.addUserMessage(q);
            if (a != null)
                params.addAssistantMessage(a);
        }
        params.addUserMessage(question);

        Message msg = anthropic.messages().create(params.build());
        return msg.content().stream()
                .map(ContentBlock::text)
                .filter(Optional::isPresent)
                .map(t -> t.get().text())
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private String generateOpenAi(Provider provider, String model, String system,
            String question, List<Document> history) {
        List<OpenAiCompatibleClient.ChatMessage> messages = new ArrayList<>();
        // Replay prior turns as memory.
        for (Document turn : history) {
            String q = turn.getString("question");
            String a = turn.getString("answer");
            if (q != null)
                messages.add(new OpenAiCompatibleClient.ChatMessage("user", q));
            if (a != null)
                messages.add(new OpenAiCompatibleClient.ChatMessage("assistant", a));
        }
        messages.add(new OpenAiCompatibleClient.ChatMessage("user", question));
        return openAiClient.chat(provider, model, system, messages);
    }

    /** Used when no LLM is configured/available: surface the retrieved context. */
    private String fallbackAnswer(List<Source> sources) {
        if (sources.isEmpty())
            return "No relevant information was found in the knowledge base for that question.";
        return "Here is the most relevant information I found:\n\n"
                + sources.stream()
                        .map(s -> "• [" + s.fileName() + "] " + snippet(s.text()))
                        .collect(Collectors.joining("\n"));
    }

    private static String snippet(String text) {
        if (text == null)
            return "";
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
