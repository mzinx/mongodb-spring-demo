package com.mzinx.demo.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.changestream.FullDocument;
import com.mzinx.mongodb.aggregation.dao.PipelineRepository;
import com.mzinx.mongodb.aggregation.model.PipelineTemplate;
import com.mzinx.mongodb.changestream.model.ChangeStream.Mode;
import com.mzinx.mongodb.changestream.model.ChangeStream.ResumeStrategy;
import com.mzinx.mongodb.changestream.model.ChangeStreamConfig;
import com.mzinx.mongodb.changestream.service.ChangeStreamConfigService;
import com.mzinx.mongodb.sink.listener.ChangeMirrorListener;
import com.mzinx.mongodb.sink.listener.MaterializedViewListener;

/**
 * Seeds the demo scenarios that showcase change-stream driven data movement
 * patterns, all idempotently (created only if absent). This is the demo's sole
 * data seeder — every order flows through the three source channels into
 * {@code unifiedOrders} and onward.
 *
 * <h2>Scenario 1 — Merge / consolidate multiple sources (incremental)</h2>
 * Three source collections ({@code webOrders}, {@code posOrders},
 * {@code marketplaceOrders}) with <b>different field shapes</b> are consolidated
 * into a single {@code unifiedOrders} view. Rather than periodically rescanning
 * all three with a {@code $unionWith} recompute, each source has its own change
 * stream using the event-driven {@link ChangeMirrorListener}: on each source
 * write, the stream's <b>event pipeline</b> normalizes just that one changed
 * document (via {@code $set} into {@code fullDocument}) to the unified schema and
 * the listener upserts it — <b>O(1) work per event</b>. Deletes rewrite
 * {@code documentKey._id} to the prefixed unified id so the right unified doc is
 * removed. Each stream uses {@link ResumeStrategy#PER_BATCH} so a restart resumes
 * from its last checkpoint (trading a small window of eventual consistency for
 * performance and resilience — exactly the intended design).
 *
 * <h2>Scenario 2 — Distribute by period into separate collections</h2>
 * A {@code $dateTrunc}-based rollup groups {@code unifiedOrders} into period
 * buckets, distributed across <b>three distinct collections</b> —
 * {@code ordersByDay}, {@code ordersByWeek}, {@code ordersByMonth}. This is a
 * genuine group aggregation, so it uses the {@link MaterializedViewListener}
 * (full recompute + {@code $merge}) — the deliberate contrast to the incremental
 * mirrors above. One period-agnostic pipeline template is shared by all three
 * streams: each binds the {@code $dateTrunc} unit from its own {@code period}
 * attribute and appends its own terminal {@code $merge} (via {@code writeStage})
 * into its dedicated collection.
 */
@Component
public class ConsolidationDemoSeeder implements ApplicationRunner {

    public static final String WEB_ORDERS = "webOrders";
    public static final String POS_ORDERS = "posOrders";
    public static final String MARKETPLACE_ORDERS = "marketplaceOrders";
    public static final String UNIFIED_ORDERS = "unifiedOrders";
    /** Per-granularity summary collections (Scenario 2 — distribute by period). */
    public static final String ORDERS_BY_DAY = "ordersByDay";
    public static final String ORDERS_BY_WEEK = "ordersByWeek";
    public static final String ORDERS_BY_MONTH = "ordersByMonth";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ChangeStreamConfigService changeStreamConfigService;
    private final PipelineRepository pipelineRepository;

    ConsolidationDemoSeeder(ChangeStreamConfigService changeStreamConfigService,
            PipelineRepository pipelineRepository) {
        this.changeStreamConfigService = changeStreamConfigService;
        this.pipelineRepository = pipelineRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedConsolidationStreams();
        seedPeriodBucketing();
    }

    // ---------------------------------------------------------------------
    // Scenario 1: incremental consolidation of three differently-shaped sources
    // ---------------------------------------------------------------------

    private void seedConsolidationStreams() {
        seedMirrorStream("unify-web", WEB_ORDERS, webNormalizePipeline());
        seedMirrorStream("unify-pos", POS_ORDERS, posNormalizePipeline());
        seedMirrorStream("unify-marketplace", MARKETPLACE_ORDERS, marketplaceNormalizePipeline());
    }

    /**
     * Seeds one per-source change stream that normalizes each event's document to
     * the unified schema and mirrors it into {@code unifiedOrders}. The event
     * pipeline reshapes {@code fullDocument} (used by INSERT/REPLACE/UPDATE) and
     * {@code documentKey._id} (used by DELETE).
     */
    private void seedMirrorStream(String streamId, String sourceCollection, List<Document> eventPipeline) {
        if (changeStreamConfigService.findById(streamId) != null)
            return;
        logger.info("Seeding consolidation change stream '{}' ({} -> {})", streamId, sourceCollection, UNIFIED_ORDERS);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ChangeMirrorListener.ATTR_DESTINATION, UNIFIED_ORDERS);
        changeStreamConfigService.save(ChangeStreamConfig.builder()
                .id(streamId)
                .collectionName(sourceCollection)
                .runOn(ChangeStreamConfig.RunOn.BUSINESS)
                // one leader mirrors; automatic failover
                .mode(Mode.AUTO_RECOVER)
                // resilience: resume from last checkpoint after a restart
                .resumeStrategy(ResumeStrategy.PER_BATCH)
                // updates must carry the current document so it can be normalized
                .fullDocument(FullDocument.UPDATE_LOOKUP)
                .pipeline(eventPipeline)
                .listener(ChangeMirrorListener.BEAN_NAME)
                .attributes(attributes)
                .enabled(true)
                .build());
    }

    /**
     * Common tail applied to every normalize pipeline: stamp the unified source +
     * a stable, channel-prefixed {@code _id} onto both {@code fullDocument} (for
     * upserts) and {@code documentKey._id} (for deletes), so channels never
     * collide in {@code unifiedOrders} and deletes hit the right document.
     */
    private static List<Document> withUnifiedKey(String source, List<Document> setFields) {
        // Build the unified fields (evaluated against the ORIGINAL fullDocument).
        Document unified = new Document();
        for (Document s : setFields)
            unified.putAll(s);
        unified.put("source", source);
        // Prefixed unified id = "<source>:<original _id as string>" so the three
        // channels never collide in unifiedOrders.
        Object prefixedId = new Document("$concat",
                List.of(source + ":", new Document("$toString", "$documentKey._id")));
        unified.put("_id", prefixedId);

        List<Document> stages = new ArrayList<>();
        // Replace fullDocument wholesale with ONLY the unified fields, but guard on
        // its presence: delete events carry no fullDocument, and $mergeObjects/an
        // object literal referencing missing paths would inject nulls. When absent
        // we leave it null — the mirror listener uses documentKey._id for deletes.
        stages.add(new Document("$set", new Document("fullDocument",
                new Document("$cond", new Document()
                        .append("if", new Document("$ifNull", List.of("$fullDocument", false)))
                        .append("then", unified)
                        .append("else", "$fullDocument")))));
        // Rewrite documentKey._id to the same prefixed id (drives deletes).
        stages.add(new Document("$set", new Document("documentKey._id", prefixedId)));
        return stages;
    }

    /** webOrders: nested customer/items/totals, USD amount, Date placedAt. */
    private static List<Document> webNormalizePipeline() {
        return withUnifiedKey(WEB_ORDERS, List.of(
                new Document("customer", "$fullDocument.customer.name"),
                new Document("product", new Document("$arrayElemAt", List.of("$fullDocument.items.sku", 0))),
                new Document("quantity", new Document("$arrayElemAt", List.of("$fullDocument.items.qty", 0))),
                new Document("amount", "$fullDocument.totals.grand"),
                new Document("status", new Document("$toUpper", "$fullDocument.state")),
                new Document("createdAt", "$fullDocument.placedAt")));
    }

    /** posOrders: flat, amount in CENTS, epoch-millis tsMillis. */
    private static List<Document> posNormalizePipeline() {
        return withUnifiedKey(POS_ORDERS, List.of(
                new Document("customer", new Document("$concat", List.of("$fullDocument.cashier", "@", "$fullDocument.storeCode"))),
                new Document("product", new Document("$arrayElemAt", List.of("$fullDocument.lineItems.product", 0))),
                new Document("quantity", new Document("$arrayElemAt", List.of("$fullDocument.lineItems.qty", 0))),
                // cents -> dollars
                new Document("amount", new Document("$round", List.of(
                        new Document("$divide", List.of("$fullDocument.total", 100.0)), 2))),
                new Document("status", "$fullDocument.status"),
                // epoch millis -> Date
                new Document("createdAt", new Document("$toDate", "$fullDocument.tsMillis"))));
    }

    /** marketplaceOrders: externalId, nested listing, ISO-string created. */
    private static List<Document> marketplaceNormalizePipeline() {
        return withUnifiedKey(MARKETPLACE_ORDERS, List.of(
                new Document("customer", "$fullDocument.buyerHandle"),
                new Document("product", "$fullDocument.listing.sku"),
                new Document("quantity", "$fullDocument.listing.units"),
                new Document("amount", "$fullDocument.priceUsd"),
                new Document("status", new Document("$toUpper", "$fullDocument.fulfilment")),
                // ISO string -> Date
                new Document("createdAt", new Document("$toDate", "$fullDocument.created"))));
    }

    // ---------------------------------------------------------------------
    // Scenario 2: distribute unifiedOrders by period into SEPARATE collections
    // (daily/weekly/monthly) via $dateTrunc rollup
    // ---------------------------------------------------------------------

    private void seedPeriodBucketing() {
        // One period-agnostic pipeline template shared by every granularity. Its
        // body ends BEFORE the terminal write stage; each stream supplies its own
        // $merge target (writeStage) so the three granularities land in three
        // distinct collections. The $dateTrunc unit is bound from each stream's
        // own "period" attribute.
        if (pipelineRepository.findById("orders-by-period").isEmpty()) {
            logger.info("Seeding pipeline template 'orders-by-period'");
            pipelineRepository.save(PipelineTemplate.builder()
                    .name("orders-by-period")
                    .stages(periodBucketStages())
                    .build());
        }
        // Seed one stream per granularity — each writes to its own collection.
        seedPeriodStream("orders-by-day", "day", ORDERS_BY_DAY);
        seedPeriodStream("orders-by-week", "week", ORDERS_BY_WEEK);
        seedPeriodStream("orders-by-month", "month", ORDERS_BY_MONTH);
    }

    /**
     * Seeds one period-bucketing change stream for the given granularity. All
     * three ({@code day}/{@code week}/{@code month}) share the
     * {@code orders-by-period} template body; each carries its own {@code period}
     * attribute (bound into {@code $dateTrunc}) and its own {@code writeStage}
     * attribute — a terminal {@code $merge} into its dedicated collection
     * ({@code ordersByDay} / {@code ordersByWeek} / {@code ordersByMonth}).
     */
    private void seedPeriodStream(String streamId, String period, String targetCollection) {
        if (changeStreamConfigService.findById(streamId) != null)
            return;
        logger.info("Seeding period-bucketing change stream '{}' (period={}, {} -> {})",
                streamId, period, UNIFIED_ORDERS, targetCollection);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(MaterializedViewListener.ATTR_OUTPUT_PIPELINE, "orders-by-period");
        attributes.put("period", period);
        // Each stream's own terminal write stage: $merge into its collection,
        // replacing each bucket by _id. Appended to the shared template body by the
        // MaterializedViewListener at runtime.
        attributes.put(MaterializedViewListener.ATTR_WRITE_STAGE, new Document("$merge", new Document()
                .append("into", targetCollection)
                .append("on", "_id")
                .append("whenMatched", "replace")
                .append("whenNotMatched", "insert")));
        changeStreamConfigService.save(ChangeStreamConfig.builder()
                .id(streamId)
                .collectionName(UNIFIED_ORDERS)
                .runOn(ChangeStreamConfig.RunOn.BUSINESS)
                .mode(Mode.AUTO_RECOVER)
                .resumeStrategy(ResumeStrategy.PER_BATCH)
                .pipeline(List.of())
                .listener(MaterializedViewListener.BEAN_NAME)
                .attributes(attributes)
                .enabled(true)
                .build());
    }

    /**
     * Rollup of {@code unifiedOrders} into period buckets. The {@code $dateTrunc}
     * unit is bound from the stream's {@code period} attribute
     * ({@code {"_ph": "period"}}), so the same template body produces daily, weekly
     * or monthly buckets depending on which stream runs it. Groups at the
     * (bucketStart, product, status) grain, then rolls up per bucket to produce
     * both a per-product array ({@code byProduct}) and a per-status map
     * ({@code byStatus}, e.g. {@code {PAID: 3, SHIPPED: 1}}), keyed by the
     * truncated {@code bucketStart} date. The body ends WITHOUT a write stage —
     * each stream appends its own {@code $merge} into its dedicated collection
     * ({@code ordersByDay} / {@code ordersByWeek} / {@code ordersByMonth}).
     */
    private static List<Map<String, Object>> periodBucketStages() {
        return List.of(
                // 1) truncate createdAt to the configured unit (day/week/month)
                new Document("$addFields", new Document("bucketStart",
                        new Document("$dateTrunc", new Document()
                                .append("date", "$createdAt")
                                .append("unit", new Document("_ph", "period"))))),
                // 2) group at the finest grain: bucket + product + status
                new Document("$group", new Document()
                        .append("_id", new Document("bucketStart", "$bucketStart")
                                .append("product", "$product")
                                .append("status", "$status"))
                        .append("orders", new Document("$sum", 1))
                        .append("revenue", new Document("$sum", "$amount"))
                        .append("units", new Document("$sum", "$quantity"))),
                // 3a) roll up to bucket + product (collapsing status)
                new Document("$group", new Document()
                        .append("_id", new Document("bucketStart", "$_id.bucketStart").append("product", "$_id.product"))
                        .append("orders", new Document("$sum", "$orders"))
                        .append("revenue", new Document("$sum", "$revenue"))
                        .append("units", new Document("$sum", "$units"))
                        // carry the per-status counts for this (bucket, product) up
                        .append("statuses", new Document("$push", new Document()
                                .append("k", "$_id.status")
                                .append("v", "$orders")))),
                // 3b) roll up per bucket, keeping a per-product array AND flattening
                //     every (product) group's status entries into one bucket-level list
                new Document("$group", new Document()
                        .append("_id", "$_id.bucketStart")
                        .append("orders", new Document("$sum", "$orders"))
                        .append("revenue", new Document("$sum", "$revenue"))
                        .append("units", new Document("$sum", "$units"))
                        .append("byProduct", new Document("$push", new Document()
                                .append("product", "$_id.product")
                                .append("orders", "$orders")
                                .append("revenue", "$revenue")))
                        // concat every product's status pairs into one flat array
                        .append("statusPairs", new Document("$push", "$statuses"))),
                // 4) shape the output: the truncated date is the natural _id (one
                //    period per collection), keep bucketStart + a period tag, build
                //    byStatus by summing the flattened status pairs, stamp the time
                new Document("$addFields", new Document()
                        .append("period", new Document("_ph", "period"))
                        .append("bucketStart", "$_id")
                        .append("revenue", new Document("$round", List.of("$revenue", 2)))
                        .append("avgOrderValue", new Document("$round", List.of(
                                new Document("$divide", List.of("$revenue",
                                        new Document("$max", List.of("$orders", 1)))),
                                2)))
                        // flatten [[{k,v}...],[{k,v}...]] -> [{k,v}...], sum per status
                        // (a status can appear under several products), then to a map
                        .append("byStatus", buildByStatus())
                        .append("updatedAt", "$$NOW")),
                // drop the interim status-aggregation field from the output
                new Document("$project", new Document("statusPairs", 0)));
        // NOTE: no terminal $merge here — each stream appends its own writeStage
        // (into ordersByDay/ordersByWeek/ordersByMonth) via ATTR_WRITE_STAGE.
    }

    /**
     * Expression that turns {@code statusPairs} (a per-product array of
     * {@code [{k: status, v: count}, ...]} arrays) into a {@code {status: count}}
     * map, summing a status's counts across products. Flattens with {@code $reduce}
     * + {@code $concatArrays}, collapses duplicate keys by mapping over the set of
     * distinct statuses and summing their values, then {@code $arrayToObject}.
     */
    private static Document buildByStatus() {
        // 1) flat = concat all inner {k,v} arrays into one
        Document flat = new Document("$reduce", new Document()
                .append("input", "$statusPairs")
                .append("initialValue", List.of())
                .append("in", new Document("$concatArrays", List.of("$$value", "$$this"))));
        // 2) distinct status keys present in flat
        Document distinctKeys = new Document("$setUnion", List.of(
                new Document("$map", new Document()
                        .append("input", flat)
                        .append("as", "p")
                        .append("in", "$$p.k"))));
        // 3) for each distinct key, sum the v of matching pairs -> {k, v}
        Document matchingPairs = new Document("$filter", new Document()
                .append("input", flat)
                .append("as", "p")
                .append("cond", new Document("$eq", List.of("$$p.k", "$$key"))));
        Document matchingValues = new Document("$map", new Document()
                .append("input", matchingPairs)
                .append("as", "m")
                .append("in", "$$m.v"));
        Document summedPairs = new Document("$map", new Document()
                .append("input", distinctKeys)
                .append("as", "key")
                .append("in", new Document()
                        .append("k", "$$key")
                        .append("v", new Document("$sum", matchingValues))));
        // 4) pairs -> object
        return new Document("$arrayToObject", summedPairs);
    }
}
