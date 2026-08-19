package com.mzinx.demo.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.model.Sorts;

/**
 * UI entry points for the order-fulfillment workflow ({@link WorkflowService}).
 * <ul>
 * <li>{@code POST /api/workflow/run/{task}} — run one async task (per-task button):
 * {@code payment} | {@code inventory} | {@code shipping} | {@code risk}.</li>
 * <li>{@code POST /api/workflow/run-all} — fire every task at once ("Run all").</li>
 * <li>{@code GET /api/workflow/rollups} — read the fan-out rollup collections
 * ({@code customerSummary} / {@code productInventory}), which are kept current
 * automatically by the {@code rollup-*} change streams.</li>
 * </ul>
 * Tasks return immediately (they are {@code @Async}); clients observe the merged
 * result live via {@code /cmd} REFRESH broadcasts and {@code /sync} live-data.
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final MongoTemplate mongoTemplate;

    WorkflowController(WorkflowService workflowService, MongoTemplate mongoTemplate) {
        this.workflowService = workflowService;
        this.mongoTemplate = mongoTemplate;
    }

    /** Runs a single workflow task asynchronously. */
    @PostMapping("/run/{task}")
    public Map<String, Object> run(@PathVariable String task) {
        workflowService.run(task);
        return Map.of("task", task, "status", "started");
    }

    /** Runs the whole workflow at once — tasks merge concurrently. */
    @PostMapping("/run-all")
    public Map<String, Object> runAll() {
        workflowService.runAll();
        return Map.of("status", "started",
                "tasks", List.of("payment", "inventory", "shipping", "risk"));
    }

    /** Reads a fan-out rollup collection ({@code customerSummary} or {@code productInventory}). */
    @GetMapping("/rollups")
    public Map<String, Object> rollups(@RequestParam(defaultValue = "customer") String kind,
            @RequestParam(defaultValue = "50") int limit) {
        String collection = switch (kind == null ? "" : kind.toLowerCase()) {
            case "product", "productinventory", "inventory" -> WorkflowService.PRODUCT_INVENTORY;
            default -> WorkflowService.CUSTOMER_SUMMARY;
        };
        List<Document> content = new ArrayList<>();
        mongoTemplate.getCollection(collection).find()
                .sort(Sorts.descending("revenue"))
                .limit(Math.min(Math.max(limit, 1), 200))
                .forEach(content::add);
        return Map.of("kind", kind, "collection", collection, "content", content,
                "total", mongoTemplate.getCollection(collection).estimatedDocumentCount());
    }
}
