package com.mzinx.demo.web.dto;

import java.util.List;
import java.util.Map;

/**
 * JSON payload for running an aggregation via mongodb-spring-aggregation.
 * Either a saved {@code pipelineName} or inline {@code stages} must be given.
 * {@code variables} substitute {@code {"_ph": "name"}} placeholders in the
 * pipeline template.
 */
public record AggregationRequest(
        String collectionName,
        String pipelineName,
        List<Map<String, Object>> stages,
        Map<String, Object> variables) {
}
