package com.mzinx.demo.web;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.model.Sorts;

/**
 * Read-only daily order summary API backing the dashboard page. The summaries
 * are precomputed into the {@code orderSummaries} collection by this app's
 * {@code order-summary} materialized-view change stream (seeded in
 * {@code DemoDataSeeder} and executed here by the {@code materializedViewListener}
 * from {@code mongodb-spring-materialized-view}). This endpoint only reads the
 * resulting view.
 */
@RestController
@RequestMapping("/api/summary")
public class OrderSummaryController {

    private final MongoTemplate mongoTemplate;

    OrderSummaryController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Daily summaries, newest day first. */
    @GetMapping
    public List<Document> list() {
        return mongoTemplate.getCollection("orderSummaries")
                .find().sort(Sorts.descending("_id")).limit(60).into(new ArrayList<>());
    }

}
