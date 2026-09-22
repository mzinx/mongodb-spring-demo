package com.mzinx.demo.rag;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import lombok.extern.slf4j.Slf4j;

/**
 * Ingests uploaded PDF / Word documents into the knowledge-base collection.
 * <p>
 * The pipeline is:
 * <ol>
 * <li>Extract plain text from the upload with Apache Tika (handles PDF via
 * PDFBox and .doc/.docx via POI).</li>
 * <li>Split the text into overlapping chunks so each stored unit is small
 * enough to embed well and to return as focused context.</li>
 * <li>Insert one document per chunk into {@code rag.knowledge-collection}. Each
 * chunk stores only its raw {@code text}; the Atlas Vector Search
 * <em>automatic embedding</em> index generates and persists the embedding
 * server-side (Voyage AI), so this service never calls an embedding API.</li>
 * </ol>
 *
 * <b>Chunk document shape:</b>
 *
 * <pre>
 * {
 *   _id:        &lt;uuid&gt;,
 *   docId:      &lt;uuid&gt;,          // groups all chunks of one uploaded file
 *   fileName:   "handbook.pdf",
 *   contentType:"application/pdf",
 *   chunkIndex: 3,
 *   text:       "…",               // embedded automatically by Atlas
 *   uploadedAt: ISODate(...)
 * }
 * </pre>
 */
@Service
@Slf4j
public class KnowledgeService {

    private final MongoTemplate mongoTemplate;
    private final RagProperties props;
    private final Tika tika = new Tika();

    KnowledgeService(MongoTemplate mongoTemplate, RagProperties props) {
        this.mongoTemplate = mongoTemplate;
        this.props = props;
    }

    /** Result of ingesting a single uploaded file. */
    public record IngestResult(String docId, String fileName, int chunks, int characters) {
    }

    /**
     * Extracts, chunks and stores one uploaded document. Returns a summary the
     * controller echoes back to the UI.
     */
    public IngestResult ingest(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();

        String text;
        try (InputStream in = file.getInputStream()) {
            // No write limit: extract the whole document regardless of size.
            Tika bounded = new Tika();
            bounded.setMaxStringLength(-1);
            text = bounded.parseToString(in, new Metadata());
        }
        text = text == null ? "" : text.strip();
        if (text.isEmpty())
            throw new IllegalArgumentException("No extractable text found in '" + fileName + "'.");

        List<String> chunks = chunk(text, props.getChunkSize(), props.getChunkOverlap());
        String docId = UUID.randomUUID().toString();
        Date now = new Date();

        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            docs.add(new Document("_id", UUID.randomUUID().toString())
                    .append("docId", docId)
                    .append("fileName", fileName)
                    .append("contentType", contentType)
                    .append("chunkIndex", i)
                    .append("text", chunks.get(i))
                    .append("uploadedAt", now));
        }
        mongoTemplate.getCollection(props.getKnowledgeCollection()).insertMany(docs);
        log.info("Ingested '{}' as {} chunk(s) into '{}' (docId={})",
                fileName, docs.size(), props.getKnowledgeCollection(), docId);

        return new IngestResult(docId, fileName, docs.size(), text.length());
    }

    /**
     * Lists the uploaded documents (grouped by {@code docId}) with chunk counts,
     * so the GUI can show what's in the knowledge base and offer per-document
     * deletion.
     */
    public List<Document> listDocuments() {
        List<Document> out = new ArrayList<>();
        mongoTemplate.getCollection(props.getKnowledgeCollection()).aggregate(List.of(
                Aggregates.group("$docId",
                        Accumulators.first("fileName", "$fileName"),
                        Accumulators.first("contentType", "$contentType"),
                        Accumulators.first("uploadedAt", "$uploadedAt"),
                        Accumulators.sum("chunks", 1)),
                Aggregates.sort(Sorts.descending("uploadedAt"))))
                .forEach(d -> {
                    // Surface the grouped _id (the docId) under a friendly name.
                    d.put("docId", d.get("_id"));
                    d.remove("_id");
                    out.add(d);
                });
        return out;
    }

    /** Deletes every chunk belonging to a document. */
    public long deleteDocument(String docId) {
        long deleted = mongoTemplate.getCollection(props.getKnowledgeCollection())
                .deleteMany(Filters.eq("docId", docId)).getDeletedCount();
        log.info("Deleted {} chunk(s) for docId={}", deleted, docId);
        return deleted;
    }

    /** Total chunk count in the knowledge base. */
    public Map<String, Object> stats() {
        long chunks = mongoTemplate.getCollection(props.getKnowledgeCollection()).estimatedDocumentCount();
        long documents = listDocuments().size();
        return Map.of("documents", documents, "chunks", chunks,
                "collection", props.getKnowledgeCollection());
    }

    /**
     * Splits text into overlapping, roughly {@code size}-character chunks, trying
     * to break on paragraph / sentence boundaries so chunks stay coherent.
     */
    static List<String> chunk(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        int n = text.length();
        if (n <= size) {
            chunks.add(text);
            return chunks;
        }
        int start = 0;
        while (start < n) {
            int end = Math.min(start + size, n);
            if (end < n) {
                // Prefer a paragraph break, then a sentence break, then whitespace,
                // searching backwards within the last ~30% of the window.
                int floor = start + (int) (size * 0.7);
                int br = lastBreak(text, floor, end);
                if (br > start)
                    end = br;
            }
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty())
                chunks.add(piece);
            if (end >= n)
                break;
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    /** Finds the best breakpoint (paragraph &gt; sentence &gt; space) in [from,to). */
    private static int lastBreak(String text, int from, int to) {
        int para = text.lastIndexOf("\n\n", to - 1);
        if (para >= from)
            return para + 2;
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n')
                return i + 1;
        }
        int space = text.lastIndexOf(' ', to - 1);
        return space >= from ? space + 1 : -1;
    }
}
