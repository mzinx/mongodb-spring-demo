package com.mzinx.demo.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.model.Sorts;
import com.mzinx.demo.listener.OrderSummaryListener;

/**
 * Daily order summary API backing the dashboard page. The summaries are
 * precomputed into the {@code orderSummaries} collection by
 * {@link OrderSummaryListener}, driven by the {@code order-summary} change
 * stream.
 */
@RestController
@RequestMapping("/api/summary")
public class SummaryController {

    private final MongoTemplate mongoTemplate;
    private final OrderSummaryListener orderSummaryListener;

    SummaryController(MongoTemplate mongoTemplate, OrderSummaryListener orderSummaryListener) {
        this.mongoTemplate = mongoTemplate;
        this.orderSummaryListener = orderSummaryListener;
    }

    /** Daily summaries, newest day first. */
    @GetMapping
    public List<Document> list() {
        return mongoTemplate.getCollection(OrderSummaryListener.SUMMARY_COLLECTION)
                .find().sort(Sorts.descending("_id")).limit(60).into(new ArrayList<>());
    }

    /** Forces a full recompute (normally triggered by the change stream). */
    @PostMapping("/recompute")
    public Map<String, Object> recompute() {
        orderSummaryListener.recompute();
        return Map.of("recomputed", true);
    }
}
