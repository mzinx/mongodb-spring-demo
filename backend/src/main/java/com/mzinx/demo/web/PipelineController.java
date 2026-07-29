package com.mzinx.demo.web;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mzinx.demo.web.dto.AggregationRequest;
import com.mzinx.mongodb.aggregation.dao.PipelineRepository;
import com.mzinx.mongodb.aggregation.model.Aggregation;
import com.mzinx.mongodb.aggregation.model.PipelineTemplate;
import com.mzinx.mongodb.aggregation.service.AggregationService;

/**
 * Aggregation demo API backed by mongodb-spring-aggregation: CRUD of pipeline
 * templates (persisted in the {@code _pipelines} collection) and ad-hoc
 * execution with {@code {"_ph": "variable"}} placeholder substitution.
 */
@RestController
@RequestMapping("/api")
public class PipelineController {

    private final PipelineRepository pipelineRepository;
    private final AggregationService aggregationService;

    PipelineController(PipelineRepository pipelineRepository, AggregationService aggregationService) {
        this.pipelineRepository = pipelineRepository;
        this.aggregationService = aggregationService;
    }

    @GetMapping("/pipelines")
    public List<PipelineTemplate> list() {
        return pipelineRepository.findAll();
    }

    @PutMapping("/pipelines/{name}")
    public PipelineTemplate save(@PathVariable String name, @RequestBody List<Map<String, Object>> stages) {
        return pipelineRepository.save(PipelineTemplate.builder().name(name).aggs(stages).build());
    }

    @DeleteMapping("/pipelines/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        pipelineRepository.deleteById(name);
        return ResponseEntity.noContent().build();
    }

    /** Runs a saved or inline pipeline through the AggregationService. */
    @PostMapping("/aggregations/run")
    public ResponseEntity<?> run(@RequestBody AggregationRequest request) {
        List<Map<String, Object>> stages = request.stages();
        if ((stages == null || stages.isEmpty()) && request.pipelineName() != null)
            stages = pipelineRepository.findById(request.pipelineName())
                    .map(PipelineTemplate::getAggs)
                    .orElse(null);
        if (stages == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Provide inline stages or the name of a saved pipeline"));
        if (request.collectionName() == null || request.collectionName().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "collectionName is required"));

        List<Document> pipeline = stages.stream().map(Document::new).toList();
        List<Document> results = aggregationService.execute(
                Aggregation.of(request.collectionName(), pipeline), request.variables());
        results.forEach(PipelineController::stringifyId);
        return ResponseEntity.ok(results);
    }

    private static void stringifyId(Document doc) {
        Object id = doc.get("_id");
        if (id instanceof ObjectId objectId)
            doc.put("_id", objectId.toHexString());
    }
}
