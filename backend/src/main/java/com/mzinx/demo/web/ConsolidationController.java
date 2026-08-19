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
 * Read side for the demo.
 * <ul>
 * <li>{@code /api/unified} — the polymorphic {@code orders} collection (all
 * sources in one place), with a per-source composition count.</li>
 * <li>{@code /api/periods} — the daily period-bucketed summary from
 * one {@code ordersByPeriod} collection filtered by {@code period}
 * ({@code day}/{@code week}/{@code month}).</li>
 * </ul>
 * The period endpoint is a plain read of a precomputed collection; the orders
 * endpoint adds only a small per-source count for the composition display.
 */
@RestController
@RequestMapping("/api")
public class ConsolidationController {

    private final MongoTemplate mongoTemplate;

    ConsolidationController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // --- polymorphic orders view (all sources, one collection) ---

    @GetMapping("/unified")
    public Map<String, Object> unified(@RequestParam(required = false) String source,
            @RequestParam(defaultValue = "50") int limit) {
        var find = mongoTemplate.getCollection(ConsolidationDemoSeeder.ORDERS).find();
        if (source != null && !source.isBlank())
            find = find.filter(Filters.eq("source", source));
        List<Document> content = new ArrayList<>();
        find.sort(Sorts.descending("createdAt")).limit(clamp(limit))
                .forEach(d -> {
                    Documents.stringifyId(d);
                    content.add(d);
                });
        // Count per source so the UI can show the polymorphic composition.
        List<Document> bySource = new ArrayList<>();
        mongoTemplate.getCollection(ConsolidationDemoSeeder.ORDERS).aggregate(List.of(
                Aggregates.group("$source", Accumulators.sum("count", 1)),
                Aggregates.sort(Sorts.descending("count")))).forEach(bySource::add);
        return Map.of("content", content, "bySource", bySource,
                "total", mongoTemplate.getCollection(ConsolidationDemoSeeder.ORDERS).estimatedDocumentCount());
    }

    // --- period-bucketed view: one `ordersByPeriod` collection, filter by period ---

    @GetMapping("/periods")
    public Map<String, Object> periods(@RequestParam(defaultValue = "day") String period,
            @RequestParam(defaultValue = "60") int limit) {
        String p = switch (period == null ? "" : period.toLowerCase()) {
            case "week" -> "week";
            case "month" -> "month";
            default -> "day";
        };
        String collection = ConsolidationDemoSeeder.ORDERS_BY_PERIOD;
        List<Document> content = new ArrayList<>();
        mongoTemplate.getCollection(collection).find(Filters.eq("period", p))
                .sort(Sorts.descending("bucketStart"))
                .limit(clamp(limit))
                .forEach(content::add);
        return Map.of("period", p, "collection", collection, "content", content,
                "total", mongoTemplate.getCollection(collection).countDocuments(Filters.eq("period", p)));
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }
}
