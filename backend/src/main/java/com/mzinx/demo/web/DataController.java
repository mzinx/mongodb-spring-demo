package com.mzinx.demo.web;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

/**
 * Test data generator so change stream events can be produced straight from
 * the UI: inserts/updates/deletes random documents in the {@code orders}
 * collection (which is watched by the message-queuing "live-data" stream and
 * by any stream the user creates on it).
 */
@RestController
@RequestMapping("/api/data/orders")
public class DataController {

    static final String COLLECTION = "orders";

    private static final List<String> CUSTOMERS = List.of("acme", "globex", "initech", "umbrella", "wayne", "stark");
    private static final List<String> STATUSES = List.of("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED");
    private static final List<String> PRODUCTS = List.of("keyboard", "mouse", "monitor", "laptop", "webcam", "dock");

    private final MongoTemplate mongoTemplate;

    DataController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Latest orders, newest first. */
    @GetMapping
    public List<Document> list(@RequestParam(defaultValue = "20") int limit) {
        List<Document> docs = collection().find().sort(Sorts.descending("createdAt"))
                .limit(Math.min(limit, 100)).into(new ArrayList<>());
        docs.forEach(DataController::stringifyId);
        return docs;
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

    private static void stringifyId(Document doc) {
        Object id = doc.get("_id");
        if (id instanceof ObjectId objectId)
            doc.put("_id", objectId.toHexString());
    }
}
