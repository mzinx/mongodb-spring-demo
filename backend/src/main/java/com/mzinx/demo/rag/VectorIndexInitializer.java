package com.mzinx.demo.rag;

import java.util.List;

import org.bson.Document;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;

import lombok.extern.slf4j.Slf4j;

/**
 * Ensures the Atlas Vector Search <em>automatic-embedding</em> index the
 * chatbot relies on exists, creating it on startup when missing.
 * <p>
 * With automatic embedding the index declares a {@code text}-typed vector field
 * plus the embedding {@code model}; Atlas then embeds both the stored chunk
 * text and incoming {@code $vectorSearch} queries server-side (Voyage AI), so
 * neither ingest nor query ever calls an embedding API from the application.
 * <p>
 * Vector Search is an Atlas-only feature. On a plain local {@code mongod} the
 * {@code createSearchIndexes} command does not exist; this initializer logs a
 * warning and continues so the rest of the demo still runs — retrieval simply
 * returns nothing until the app is pointed at Atlas.
 *
 * @see <a href=
 *      "https://www.mongodb.com/docs/atlas/atlas-vector-search/vector-search-type/">Atlas
 *      Vector Search index types</a>
 */
@Component
// Run before the change-stream demo seeder: the two are independent, and this
// guarantees the vector-index bootstrap fires (and logs) even if a later runner
// throws. Lower @Order runs first.
@Order(0)
@Slf4j
public class VectorIndexInitializer implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;
    private final RagProperties props;

    VectorIndexInitializer(MongoTemplate mongoTemplate, RagProperties props) {
        this.mongoTemplate = mongoTemplate;
        this.props = props;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        log.info("VectorIndexInitializer starting (autoCreateIndex={}, collection='{}', index='{}').",
                props.isAutoCreateIndex(), props.getKnowledgeCollection(), props.getVectorIndex());
        if (!props.isAutoCreateIndex()) {
            log.info("rag.auto-create-index=false — skipping vector index bootstrap.");
            return;
        }
        String collection = props.getKnowledgeCollection();
        MongoCollection<Document> col = mongoTemplate.getCollection(collection);

        try {
            if (indexExists(col, props.getVectorIndex())) {
                log.info("Vector index '{}' already exists on '{}'.", props.getVectorIndex(), collection);
                return;
            }
            createAutoEmbeddingIndex(col);
            log.info("Requested creation of automatic-embedding vector index '{}' on '{}' (model={}). "
                    + "Atlas builds it asynchronously; it becomes queryable once READY.",
                    props.getVectorIndex(), collection, props.getEmbeddingModel());
        } catch (MongoCommandException e) {
            // CommandNotFound / unsupported => not an Atlas cluster.
            log.warn("Could not create vector index '{}' on '{}': {}. "
                    + "Atlas Vector Search (automatic embedding) is required for RAG retrieval; "
                    + "the app will still run but the chatbot won't find context until pointed at Atlas.",
                    props.getVectorIndex(), collection, e.getErrorMessage());
        } catch (RuntimeException e) {
            log.warn("Vector index bootstrap skipped: {}", e.getMessage());
        }
    }

    private boolean indexExists(MongoCollection<Document> col, String name) {
        try {
            for (Document idx : col.listSearchIndexes()) {
                if (name.equals(idx.getString("name")))
                    return true;
            }
            return false;
        } catch (RuntimeException e) {
            // listSearchIndexes unsupported (non-Atlas) — treat as "cannot create".
            throw new IllegalStateException("search indexes unsupported on this deployment", e);
        }
    }

    /**
     * Issues {@code createSearchIndexes} for a {@code vectorSearch} index whose
     * single field is an {@code autoEmbed} automatic-embedding field. The
     * {@code model} tells Atlas which Voyage AI model to embed with; Atlas fills
     * in {@code similarity}/{@code dimensions} from the model. {@code modality:
     * text} declares that the source field holds text to be embedded.
     */
    private void createAutoEmbeddingIndex(MongoCollection<Document> col) {
        Document field = new Document("type", "autoEmbed")
                .append("path", "text")
                .append("modality", "text")
                .append("model", props.getEmbeddingModel());

        Document definition = new Document("fields", List.of(field));

        Document createCmd = new Document("createSearchIndexes", col.getNamespace().getCollectionName())
                .append("indexes", List.of(new Document()
                        .append("name", props.getVectorIndex())
                        .append("type", "vectorSearch")
                        .append("definition", definition)));

        mongoTemplate.getDb().runCommand(createCmd);
    }
}
