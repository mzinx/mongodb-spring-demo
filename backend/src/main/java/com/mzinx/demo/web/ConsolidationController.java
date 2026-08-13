package com.mzinx.demo.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import com.mzinx.demo.config.ConsolidationDemoSeeder;

/**
 * Read side for the consolidation demo scenarios.
 * <ul>
 * <li>{@code /api/unified} — the consolidated {@code unifiedOrders} view built
 * incrementally from the three channels (Scenario 1).</li>
 * <li>{@code /api/periods?period=day|week|month} — the period-bucketed summary,
 * distributed across the {@code ordersByDay} / {@code ordersByWeek} /
 * {@code ordersByMonth} collections (Scenario 2).</li>
 * </ul>
 * These are plain reads of precomputed collections — no aggregation on the read
 * path (except a small per-source count for the merge composition).
 */
@RestController
@RequestMapping("/api")
public class ConsolidationController {

    private final MongoTemplate mongoTemplate;

    ConsolidationController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // --- Scenario 1: consolidated view ---

    @GetMapping("/unified")
    public Map<String, Object> unified(@RequestParam(required = false) String source,
            @RequestParam(defaultValue = "50") int limit) {
        var find = mongoTemplate.getCollection(ConsolidationDemoSeeder.UNIFIED_ORDERS).find();
        if (source != null && !source.isBlank())
            find = find.filter(Filters.eq("source", source));
        List<Document> content = new ArrayList<>();
        find.sort(Sorts.descending("createdAt")).limit(clamp(limit)).forEach(content::add);
        // Count per source so the UI can show the merge composition.
        List<Document> bySource = new ArrayList<>();
        mongoTemplate.getCollection(ConsolidationDemoSeeder.UNIFIED_ORDERS).aggregate(List.of(
                Aggregates.group("$source", Accumulators.sum("count", 1)),
                Aggregates.sort(Sorts.descending("count")))).forEach(bySource::add);
        return Map.of("content", content, "bySource", bySource,
                "total", mongoTemplate.getCollection(ConsolidationDemoSeeder.UNIFIED_ORDERS).estimatedDocumentCount());
    }

    // --- Scenario 2: period-bucketed view (distributed across 3 collections) ---

    @GetMapping("/periods")
    public Map<String, Object> periods(@RequestParam(defaultValue = "day") String period,
            @RequestParam(defaultValue = "60") int limit) {
        String collection = collectionForPeriod(period);
        List<Document> content = new ArrayList<>();
        mongoTemplate.getCollection(collection).find()
                .sort(Sorts.descending("bucketStart"))
                .limit(clamp(limit))
                .forEach(content::add);
        return Map.of("period", period, "collection", collection, "content", content,
                "total", mongoTemplate.getCollection(collection).estimatedDocumentCount());
    }

    /** Maps a period name to its dedicated summary collection. */
    private static String collectionForPeriod(String period) {
        return switch (period == null ? "" : period.toLowerCase()) {
            case "week" -> ConsolidationDemoSeeder.ORDERS_BY_WEEK;
            case "month" -> ConsolidationDemoSeeder.ORDERS_BY_MONTH;
            default -> ConsolidationDemoSeeder.ORDERS_BY_DAY;
        };
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }
}
