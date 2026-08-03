package com.mzinx.demo.web;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.model.Sorts;
import com.mzinx.demo.listener.MaterializedViewListener;

/**
 * Daily order summary API backing the dashboard page. The summaries are
 * precomputed into the output collection by the generic
 * {@link MaterializedViewListener}, driven by the {@code order-summary} change
 * stream. Which aggregation pipeline produces them is configured on that change
 * stream's {@code attributes.outputPipeline} (edited from the Change streams
 * page), not here.
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
