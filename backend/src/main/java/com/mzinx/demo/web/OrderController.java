package com.mzinx.demo.web;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mzinx.mongodb.aggregation.model.AggregationSpec;
import com.mzinx.mongodb.aggregation.service.AggregationService;

/**
 * Orders API: paginated listing (through the mongodb-spring-aggregation
 * pagination support) plus a test data generator so change stream events can
 * be produced straight from the UI. The collection is watched by the
 * message-queuing "live-data" stream, which broadcasts REFRESH commands to
 * WebSocket subscribers so the orders page updates in real time.
 */
@RestController
@RequestMapping("/api/data/orders")
public class OrderController {

    static final String COLLECTION = "orders";

    private static final List<String> CUSTOMERS = List.of("acme", "globex", "initech", "umbrella", "wayne", "stark");
    private static final List<String> STATUSES = List.of("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED");
    private static final List<String> PRODUCTS = List.of("keyboard", "mouse", "monitor", "laptop", "webcam", "dock");

    private final MongoTemplate mongoTemplate;
    private final AggregationService aggregationService;

    OrderController(MongoTemplate mongoTemplate, AggregationService aggregationService) {
        this.mongoTemplate = mongoTemplate;
        this.aggregationService = aggregationService;
    }

    /**
     * Paginated orders, newest first. Executed via the aggregation library,
     * which appends a {@code $facet} stage computing results and total count
     * in a single round trip and returns a Spring Data {@link Page}.
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<Document> result = aggregationService.execute(
                AggregationSpec.of(COLLECTION, List.of(new Document("$sort", new Document("createdAt", -1)))),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
        result.getContent().forEach(Documents::stringifyId);
        return Map.of(
                "content", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages());
    }

    /** Inserts {@code count} random orders. */
    @PostMapping("/insert")
    public Map<String, Object> insert(@RequestParam(defaultValue = "1") int count) {
        List<Document> orders = new ArrayList<>();
        for (int i = 0; i < Math.min(count, 100); i++)
            orders.add(randomOrder());
        collection().insertMany(orders);
        return Map.of("inserted", orders.size());
    }

    /** Updates the status/amount of one random order. */
    @PostMapping("/update-random")
    public Map<String, Object> updateRandom() {
        Document victim = collection().aggregate(List.of(Aggregates.sample(1))).first();
        if (victim == null)
            return Map.of("updated", 0);
        collection().updateOne(Filters.eq("_id", victim.get("_id")),
                Updates.combine(
                        Updates.set("status", random(STATUSES)),
                        Updates.set("amount", randomAmount()),
                        Updates.set("updatedAt", new Date())));
        return Map.of("updated", 1, "id", victim.get("_id").toString());
    }

    /** Deletes one random order. */
    @PostMapping("/delete-random")
    public Map<String, Object> deleteRandom() {
        Document victim = collection().aggregate(List.of(Aggregates.sample(1))).first();
        if (victim == null)
            return Map.of("deleted", 0);
        collection().deleteOne(Filters.eq("_id", victim.get("_id")));
        return Map.of("deleted", 1, "id", victim.get("_id").toString());
    }

    private MongoCollection<Document> collection() {
        return mongoTemplate.getCollection(COLLECTION);
    }

    static Document randomOrder() {
        return new Document()
                .append("customer", random(CUSTOMERS))
                .append("product", random(PRODUCTS))
                .append("status", random(STATUSES))
                .append("quantity", ThreadLocalRandom.current().nextInt(1, 6))
                .append("amount", randomAmount())
                .append("createdAt", new Date());
    }

    private static double randomAmount() {
        return Math.round(ThreadLocalRandom.current().nextDouble(10, 500) * 100.0) / 100.0;
    }

    private static <T> T random(List<T> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
