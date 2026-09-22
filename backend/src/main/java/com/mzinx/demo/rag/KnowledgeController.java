package com.mzinx.demo.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Knowledge-base management API backing the document-upload GUI.
 * <p>
 * Uploaded PDF / Word files are extracted, chunked and stored in the
 * {@code knowledgeBase} collection where Atlas Vector Search automatic
 * embedding indexes them for retrieval.
 *
 * <ul>
 * <li>{@code POST /api/knowledge/upload} — multipart upload of one or more files</li>
 * <li>{@code GET  /api/knowledge/documents} — list uploaded documents</li>
 * <li>{@code GET  /api/knowledge/stats} — document / chunk counts</li>
 * <li>{@code DELETE /api/knowledge/documents/{docId}} — remove a document</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("files") List<MultipartFile> files) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty())
                continue;
            try {
                KnowledgeService.IngestResult r = knowledgeService.ingest(file);
                results.add(Map.of(
                        "docId", r.docId(),
                        "fileName", r.fileName(),
                        "chunks", r.chunks(),
                        "characters", r.characters()));
            } catch (Exception e) {
                failures.add(Map.of(
                        "fileName", file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename(),
                        "error", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        Map<String, Object> body = Map.of(
                "ingested", results,
                "failed", failures);
        return failures.isEmpty() ? ResponseEntity.ok(body) : ResponseEntity.status(207).body(body);
    }

    @GetMapping("/documents")
    public List<Document> documents() {
        return knowledgeService.listDocuments();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return knowledgeService.stats();
    }

    @DeleteMapping("/documents/{docId}")
    public Map<String, Object> delete(@PathVariable String docId) {
        long deleted = knowledgeService.deleteDocument(docId);
        return Map.of("docId", docId, "deletedChunks", deleted);
    }
}
