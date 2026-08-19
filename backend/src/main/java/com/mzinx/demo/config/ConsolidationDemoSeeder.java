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
 * Seeds the demo's change-stream driven data-movement scenario, idempotently.
 *
 * <h2>Schema — one polymorphic {@code orders} collection</h2>
 * Orders from every source (web / pos / marketplace) live in a <b>single</b>
 * {@code orders} collection, distinguished by a {@code source} discriminator and
 * sharing a common core schema ({@code customer, product, quantity, amount,
 * status, createdAt}) with source-specific fields under {@code sourceData} — the
 * MongoDB <a href="https://mongodb.com/docs/manual/data-modeling/design-patterns/polymorphic-data/polymorphic-schema-pattern/">polymorphic
 * pattern</a>. Because the data is unified <em>at write time</em> by the shared
 * schema, there is no separate {@code unifiedOrders} consolidation step (the demo
 * previously split the same base entity across three collections and mirrored
 * them together — a schema anti-pattern this redesign removes).
 *
 * <h2>Change streams seeded here</h2>
 * <ul>
 * <li><b>Slice enrichments</b> — {@code enrich-payment} / {@code enrich-shipping} /
 * {@code enrich-fulfillment}: each watches an enrichment ledger collection
 * ({@code paymentLog} / {@code shippingAck} / {@code fulfillmentLog}) and merges
 * that one slice into the matching order (O(1) per event, {@code whenMatched:
 * "merge"}).</li>
 * <li><b>Audit mirror</b> — {@code mirror-audit}: the {@link ChangeMirrorListener}
 * showcase. Mirrors every {@code orders} change 1:1 into {@code ordersAudit} (whole
 * document, O(1) per event, no aggregation), with {@code mirrorDelete=false} so
 * deletes are retained as history.</li>
 * <li><b>Fan-out rollups</b> — a single {@code rollup-orders} stream that watches
 * {@code orders} and uses the listener's multi-target {@code writeStages} to
 * {@code $merge} a per-customer rollup into {@code customerSummary} AND a
 * per-product rollup into {@code productInventory}. Each recompute is <em>scoped</em>
 * to the changed order's customer/product (not a full re-group), so it runs
 * immediately without coalescing.</li>
 * <li><b>Period distribution</b> — a single {@code orders-by-period} stream that
 * computes day + week + month in one {@code $unionWith} pass and {@code $merge}s
 * them all into one {@code ordersByPeriod} collection (composite {@code _id}
 * {@code "<period>|<bucketStart>"} + a {@code period} discriminator). This is a
 * full recompute, so it uses coalescing to fold bursts into one run.</li>
 * </ul>
 * The slice enrichments, rollups and period distribution use the
 * {@link MaterializedViewListener}, whose terminal {@code $merge} can do a partial
 * ({@code whenMatched: "merge"}) or full ({@code whenMatched: "replace"}) write, and
 * whose {@code writeStages} attribute lets one stream fan out to several targets;
 * the audit mirror uses the {@link ChangeMirrorListener} for a cheap whole-document
 * copy. The workflow tasks that feed the ledgers live in
 * {@code com.mzinx.demo.workflow.WorkflowService}.
 */
@Component
public class ConsolidationDemoSeeder implements ApplicationRunner {

    /** The single, polymorphic order collection every source writes into. */
    public static final String ORDERS = "orders";

    /**
     * Single period-summary collection holding <b>all</b> granularities. Each doc's
     * {@code _id} is {@code "<period>|<bucketStart ISO>"} and it carries a
     * {@code period} discriminator ({@code day}/{@code week}/{@code month}). One
     * change stream computes all three in a single {@code $unionWith} pass.
     */
    public static final String ORDERS_BY_PERIOD = "ordersByPeriod";

    /** Fan-out rollup collections, kept live by the rollup change streams. */
    public static final String CUSTOMER_SUMMARY = "customerSummary";
    public static final String PRODUCT_INVENTORY = "productInventory";

    /**
     * Enrichment ledger collections (one doc per order, {@code _id = orderId}). The
     * matching workflow task writes here instead of enriching {@code orders}
     * directly; a per-slice {@code enrich-*} change stream then merges that slice
     * into the order.
     */
    public static final String PAYMENT_LOG = "paymentLog";
    public static final String SHIPPING_ACK = "shippingAck";
    public static final String FULFILLMENT_LOG = "fulfillmentLog";

    /**
     * Whole-document audit/history mirror of {@code orders}, maintained by the
     * event-driven {@code changeMirrorListener} ({@code mirror-audit} stream).
     * Deletes are retained (not mirrored), so it doubles as a soft-delete trail.
     */
    public static final String ORDERS_AUDIT = "ordersAudit";

    /**
     * Order sub-document fields written by the enrichment workflow (via the payment
     * mirror + the direct inventory/shipping/risk merges). The period-rollup streams
     * must NOT recompute when only these change, so they filter them out of their
     * change-stream pipeline.
     */
    static final List<String> ENRICHMENT_FIELDS = List.of("payment", "fulfillment", "shipping", "risk");

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
        seedSliceEnrichments();
        seedAuditMirror();
        seedRollups();
        seedPeriodBucketing();
    }

    // ---------------------------------------------------------------------
    // Slice enrichments: <ledger collection> change -> $merge one slice into order
    // ---------------------------------------------------------------------

    /**
     * Seeds one {@code enrich-*} change stream per enrichment slice. Each workflow
     * task writes a ledger document keyed by the order {@code _id} into its own
     * collection; the matching stream watches that collection and, for the single
     * changed document, merges just that slice into the order — a scoped per-event
     * enrichment, NOT a full recompute.
     * <ul>
     * <li>{@code paymentLog}      → {@code payment.*}</li>
     * <li>{@code shippingAck}     → {@code shipping.*}</li>
     * <li>{@code fulfillmentLog}  → {@code fulfillment.*}</li>
     * </ul>
     * Each uses the {@link MaterializedViewListener} — whose terminal {@code $merge}
     * supports {@code whenMatched: "merge"} (a partial update that preserves the
     * order's other fields) — aggregating over the ledger collection but scoped to
     * just the event's {@code _id} via {@code {"_ph": "event.documentKey._id"}}, so
     * the work is O(1) per write. (The library's {@code changeMirrorListener} does a
     * full {@code replaceOne} and would wipe the order's other fields, so it is
     * deliberately not used here — for a genuine whole-document mirror see
     * {@link #seedAuditMirror()}.)
     */
    private void seedSliceEnrichments() {
        seedSliceEnrichment("enrich-payment", PAYMENT_LOG, "payment");
        seedSliceEnrichment("enrich-shipping", SHIPPING_ACK, "shipping");
        seedSliceEnrichment("enrich-fulfillment", FULFILLMENT_LOG, "fulfillment");
    }

    /**
     * Seeds a single slice enrichment: watch {@code logCollection}, and for the
     * changed record merge {@code {_id, <slice>: <the record, minus _id/orderId>}}
     * into {@code orders} with {@code whenMatched: "merge"}.
     */
    private void seedSliceEnrichment(String streamId, String logCollection, String slice) {
        if (pipelineRepository.findById(streamId).isEmpty()) {
            logger.info("Seeding pipeline template '{}'", streamId);
            pipelineRepository.save(PipelineTemplate.builder()
                    .name(streamId)
                    .stages(sliceEnrichmentStages(slice))
                    .build());
        }
        if (changeStreamConfigService.findById(streamId) != null)
            return;
        logger.info("Seeding slice-enrichment change stream '{}' ({} -> {}.{})", streamId, logCollection, ORDERS, slice);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(MaterializedViewListener.ATTR_OUTPUT_PIPELINE, streamId);
        // Aggregate over the ledger collection (not the whole orders collection). The
        // pipeline itself narrows to the single changed doc via event.documentKey._id.
        attributes.put(MaterializedViewListener.ATTR_AGGREGATION_COLLECTION, logCollection);
        // Merge ONLY this slice into the matching order, keeping every other field
        // intact. A single target is a one-element writeStages list.
        attributes.put(MaterializedViewListener.ATTR_WRITE_STAGES, List.of(Map.of(
                "writeStage", new Document("$merge", new Document()
                        .append("into", ORDERS)
                        .append("on", "_id")
                        .append("whenMatched", "merge")
                        .append("whenNotMatched", "discard")))));
        changeStreamConfigService.save(ChangeStreamConfig.builder()
                .id(streamId)
                .collectionName(logCollection)
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
     * Shapes the changed ledger doc into an order patch:
     * {@code {_id: <orderId>, <slice>: <ledger fields except _id/orderId>}}, then the
     * appended {@code $merge} writes it into {@code orders} with
     * {@code whenMatched: "merge"}. Scoped to the single event via
     * {@code {"_ph": "event.documentKey._id"}} so it never rescans the ledger. The
     * slice content is whatever the workflow task wrote (its bookkeeping {@code _id}
     * and {@code orderId} are stripped).
     */
    private static List<Map<String, Object>> sliceEnrichmentStages(String slice) {
        Document sliceValue = new Document("$unsetField", new Document()
                .append("field", "orderId")
                .append("input", new Document("$unsetField", new Document()
                        .append("field", "_id")
                        .append("input", "$$ROOT"))));
        return List.of(
                // only the ledger record that just changed
                new Document("$match", new Document("_id", new Document("_ph", "event.documentKey._id"))),
                // reshape into an order patch: keep _id (= orderId), nest the slice
                new Document("$replaceWith", new Document()
                        .append("_id", "$_id")
                        .append(slice, sliceValue)));
        // NOTE: terminal $merge into `orders` (whenMatched:merge) is appended by the
        // stream's single writeStages target.
    }

    // ---------------------------------------------------------------------
    // Audit mirror: orders change -> whole-document copy into ordersAudit
    // (the changeMirrorListener showcase — O(1) per event, no aggregation)
    // ---------------------------------------------------------------------

    /**
     * Seeds the {@code mirror-audit} change stream — the demo's use of the
     * event-driven {@link ChangeMirrorListener}. It mirrors <b>every</b>
     * {@code orders} change 1:1 into {@code ordersAudit}: on insert/update/replace it
     * upserts the whole current document by {@code _id} (O(1) per event, <em>no</em>
     * aggregation over the source), which is exactly what this listener is for.
     * <p>
     * {@code mirrorDelete=false} means deletes are <b>not</b> propagated, so a
     * deleted order is <b>retained</b> in {@code ordersAudit} — turning it into a
     * simple history/soft-delete trail. Contrast with the {@code enrich-*} streams,
     * which use the {@link MaterializedViewListener} to merge a partial slice; here
     * we want a full-document copy, so the mirror listener is the right tool.
     * <p>
     * Updates carry the current document via {@code fullDocument=UPDATE_LOOKUP} (the
     * mirror skips updates that arrive without a full document).
     */
    private void seedAuditMirror() {
        if (changeStreamConfigService.findById("mirror-audit") != null)
            return;
        logger.info("Seeding audit-mirror change stream 'mirror-audit' ({} -> {})", ORDERS, ORDERS_AUDIT);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ChangeMirrorListener.ATTR_DESTINATION, ORDERS_AUDIT);
        // Retain deleted orders in the audit trail (don't mirror the delete).
        attributes.put(ChangeMirrorListener.ATTR_MIRROR_DELETE, "false");
        changeStreamConfigService.save(ChangeStreamConfig.builder()
                .id("mirror-audit")
                .collectionName(ORDERS)
                .runOn(ChangeStreamConfig.RunOn.BUSINESS)
                .mode(Mode.AUTO_RECOVER)
                .resumeStrategy(ResumeStrategy.PER_BATCH)
                // Mirror updates too: the listener needs the current full document.
                .fullDocument(FullDocument.UPDATE_LOOKUP)
                // No watch pipeline: mirror every order change verbatim.
                .pipeline(List.of())
                .listener(ChangeMirrorListener.BEAN_NAME)
                .attributes(attributes)
                .enabled(true)
                .build());
    }

    // ---------------------------------------------------------------------
    // Fan-out rollups: ONE orders change stream -> customerSummary + productInventory
    // via the listener's multi-target writeStages (demonstrates that capability).
    // ---------------------------------------------------------------------

    /**
     * Seeds a <b>single</b> {@code rollup-orders} change stream that watches
     * {@code orders} and, on each qualifying change, fans the same rollup body out
     * to <b>two</b> targets via {@link MaterializedViewListener#ATTR_WRITE_STAGES}:
     * <ul>
     * <li>{@code customerSummary} — one doc per customer;</li>
     * <li>{@code productInventory} — one doc per product.</li>
     * </ul>
     * The recompute is <b>scoped</b> to the single key touched by the event (the
     * changed order's {@code customer} / {@code product}) rather than re-grouping
     * the whole collection — see {@link #rollupStages()}. So it runs immediately
     * (no coalescing needed); {@code whenMatched: "replace"} still writes an
     * authoritative total for that one key. One stream instead of two — the listener
     * runs one scoped pass per target. Uses {@link #rollupWatchFilter()} to skip
     * enrichment-only updates and deletes.
     */
    private void seedRollups() {
        if (pipelineRepository.findById("rollup-orders").isEmpty()) {
            logger.info("Seeding pipeline template 'rollup-orders'");
            pipelineRepository.save(PipelineTemplate.builder().name("rollup-orders")
                    .stages(rollupStages()).build());
        }
        if (changeStreamConfigService.findById("rollup-orders") != null)
            return;
        logger.info("Seeding rollup change stream 'rollup-orders' ({} -> {} + {})",
                ORDERS, CUSTOMER_SUMMARY, PRODUCT_INVENTORY);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(MaterializedViewListener.ATTR_OUTPUT_PIPELINE, "rollup-orders");
        // Multi-target: same SCOPED body, two variable sets (customer / product).
        attributes.put(MaterializedViewListener.ATTR_WRITE_STAGES, List.of(
                rollupTarget("customer", CUSTOMER_SUMMARY),
                rollupTarget("product", PRODUCT_INVENTORY)));
        // No coalescing: each recompute is now scoped to the single changed order's
        // customer/product (see rollupStages), so it's cheap and should run
        // immediately — coalescing would only add latency here.
        changeStreamConfigService.save(ChangeStreamConfig.builder()
                .id("rollup-orders")
                .collectionName(ORDERS)
                .runOn(ChangeStreamConfig.RunOn.BUSINESS)
                .mode(Mode.AUTO_RECOVER)
                .resumeStrategy(ResumeStrategy.PER_BATCH)
                // The scoped pipeline reads event.fullDocument.customer/product, so
                // updates must carry the current document.
                .fullDocument(FullDocument.UPDATE_LOOKUP)
                // Skip enrichment-only updates AND deletes: a delete carries no
                // fullDocument, so we can't tell which key to refresh (a deleted
                // order's rollup self-heals when that key is next written).
                .pipeline(rollupWatchFilter())
                .listener(MaterializedViewListener.BEAN_NAME)
                .attributes(attributes)
                .enabled(true)
                .build());
    }

    /**
     * One {@code writeStages} entry for the scoped rollup. Binds:
     * {@code matchField} (the core field name, e.g. {@code "customer"}),
     * {@code matchValue} (its value from the changed order, via
     * {@code event.fullDocument.<field>}), and {@code groupBy} (the group key,
     * {@code "$customer"}/{@code "$product"}); then {@code $merge}s the one
     * recomputed rollup row into the target.
     */
    private static Map<String, Object> rollupTarget(String field, String targetCollection) {
        return Map.of(
                "variables", Map.of(
                        "matchField", field,
                        "groupBy", "$" + field),
                "writeStage", new Document("$merge", new Document()
                        .append("into", targetCollection)
                        .append("on", "_id")
                        .append("whenMatched", "replace")
                        .append("whenNotMatched", "insert")));
    }

    /**
     * Shared, <b>scoped</b> rollup body. Instead of re-grouping the whole
     * collection on every change, it recomputes only the ONE key touched by the
     * event, parameterized by three per-target placeholders:
     * <ul>
     * <li>{@code {"_ph": "matchField"}} — the field to scope on ({@code "customer"}
     * or {@code "product"});</li>
     * <li>{@code {"_ph": "matchValue"}} — that field's value from the changed order
     * ({@code event.fullDocument.customer} / {@code .product});</li>
     * <li>{@code {"_ph": "groupBy"}} — the group key ({@code "$customer"} /
     * {@code "$product"}), whose value becomes the rollup doc {@code _id}.</li>
     * </ul>
     * The opening {@code $match} (via {@code $getField} so the field name itself can
     * be a placeholder) limits the scan to that key's orders — so re-running is
     * cheap and needs no coalescing. {@code whenMatched: "replace"} still writes an
     * authoritative total for that key (a full re-sum of its current orders), so
     * inserts and status updates stay correct.
     */
    private static List<Map<String, Object>> rollupStages() {
        // The changed order's value for the scoped field, read at runtime from the
        // event's fullDocument (bound as a literal) via $getField so the field NAME
        // can itself be the per-target {"_ph":"matchField"} placeholder.
        Document matchValue = new Document("$getField", new Document()
                .append("field", new Document("_ph", "matchField"))
                .append("input", new Document("_ph", "event.fullDocument")));
        return List.of(
                // scope to just the changed order's customer (or product)
                new Document("$match", new Document("$expr", new Document("$eq", List.of(
                        new Document("$getField", new Document()
                                .append("field", new Document("_ph", "matchField"))
                                .append("input", "$$ROOT")),
                        matchValue)))),
                new Document("$group", new Document("_id", new Document("_ph", "groupBy"))
                        .append("orders", new Document("$sum", 1))
                        .append("units", new Document("$sum", "$quantity"))
                        .append("revenue", new Document("$sum", "$amount"))),
                new Document("$set", new Document()
                        .append("revenue", new Document("$round", List.of("$revenue", 2)))
                        .append("updatedAt", "$$NOW")));
    }

    // ---------------------------------------------------------------------
    // Distribute orders by period — day/week/month in ONE $unionWith pass into a
    // SINGLE ordersByPeriod collection (one stream, one $merge)
    // ---------------------------------------------------------------------

    private void seedPeriodBucketing() {
        // ONE template computes all three granularities in a single pass: the day
        // rollup, then $unionWith the week and month rollups (each re-reads orders),
        // all tagged with their `period` and a composite _id, ending in a single
        // terminal $merge into ordersByPeriod. One stream, one write target — no
        // per-granularity streams.
        if (pipelineRepository.findById("orders-by-period").isEmpty()) {
            logger.info("Seeding pipeline template 'orders-by-period'");
            pipelineRepository.save(PipelineTemplate.builder()
                    .name("orders-by-period")
                    .stages(periodBucketStages())
                    .build());
        }
        if (changeStreamConfigService.findById("orders-by-period") != null)
            return;
        logger.info("Seeding period-bucketing change stream 'orders-by-period' ({} -> {})", ORDERS, ORDERS_BY_PERIOD);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(MaterializedViewListener.ATTR_OUTPUT_PIPELINE, "orders-by-period");
        // The template is self-contained (ends in its own $merge), so no writeStage.
        // Full-collection $unionWith rollup (day+week+month) — coalesce bursts into
        // one recompute per ~750ms quiet window (capped at 3s).
        attributes.put(MaterializedViewListener.ATTR_RECOMPUTE_DEBOUNCE_MS, 750);
        attributes.put(MaterializedViewListener.ATTR_RECOMPUTE_MAX_DELAY_MS, 3000);
        changeStreamConfigService.save(ChangeStreamConfig.builder()
                .id("orders-by-period")
                .collectionName(ORDERS)
                .runOn(ChangeStreamConfig.RunOn.BUSINESS)
                .mode(Mode.AUTO_RECOVER)
                .resumeStrategy(ResumeStrategy.PER_BATCH)
                // Ignore enrichment-only changes so the (expensive) full recompute
                // does not fire when a workflow task merges a payment/fulfillment/
                // shipping/risk slice into an order — those don't affect the
                // period rollups (which only use core fields).
                .pipeline(ignoreEnrichmentUpdates())
                .listener(MaterializedViewListener.BEAN_NAME)
                .attributes(attributes)
                .enabled(true)
                .build());
    }

    /**
     * Change-stream {@code $match} for the period streams: pass every event EXCEPT
     * an {@code update} whose changed fields are <em>all</em> enrichment fields
     * ({@code payment}/{@code fulfillment}/{@code shipping}/{@code risk}). Order
     * intake ({@code insert}), status edits and deletes still trigger a recompute;
     * a workflow enrichment merge does not (it doesn't change any field the period
     * rollups read).
     * <p>
     * An {@code update} event carries {@code updateDescription.updatedFields}, a
     * document whose keys are dotted paths (e.g. {@code "payment.status"}). We keep
     * the event when it is not an update, or when at least one updated field's
     * top-level key is NOT an enrichment field.
     */
    private static List<Document> ignoreEnrichmentUpdates() {
        // top-level key of a dotted updatedFields path, e.g. "payment.status" -> "payment"
        Document topLevelKey = new Document("$arrayElemAt", List.of(
                new Document("$split", List.of("$$field.k", ".")), 0));
        // does this changed field belong to an enrichment sub-document?
        Document isEnrichment = new Document("$in", List.of(topLevelKey, ENRICHMENT_FIELDS));
        // the array of changed top-level keys that are NOT enrichment fields
        Document nonEnrichmentChanges = new Document("$filter", new Document()
                .append("input", new Document("$objectToArray",
                        new Document("$ifNull", List.of("$updateDescription.updatedFields", new Document()))))
                .append("as", "field")
                .append("cond", new Document("$not", isEnrichment)));
        return List.of(new Document("$match", new Document("$expr",
                new Document("$or", List.of(
                        // keep anything that isn't an update (insert/replace/delete/...)
                        new Document("$ne", List.of("$operationType", "update")),
                        // keep updates that touch at least one core (non-enrichment) field
                        new Document("$gt", List.of(new Document("$size", nonEnrichmentChanges), 0)))))));
    }

    /**
     * Change-stream {@code $match} for the SCOPED rollup stream: the enrichment
     * filter above, PLUS drop {@code delete} events. The scoped pipeline keys off
     * {@code event.fullDocument.customer/product}, which a delete doesn't carry, so
     * a delete can't tell us which rollup to refresh (a deleted order's rollup
     * self-heals when that customer/product is next written).
     */
    private static List<Document> rollupWatchFilter() {
        List<Document> stages = new java.util.ArrayList<>();
        stages.add(new Document("$match", new Document("operationType",
                new Document("$ne", "delete"))));
        stages.addAll(ignoreEnrichmentUpdates());
        return stages;
    }

    /**
     * The whole period rollup as ONE pipeline over {@code orders}: compute the daily
     * buckets, then {@code $unionWith} the weekly and monthly buckets (each
     * sub-pipeline re-reads {@code orders}), and finish with a single terminal
     * {@code $merge} into {@code ordersByPeriod}. Every bucket doc is tagged with
     * its {@code period} and keyed by a composite {@code _id = "<period>|<ISO
     * bucketStart>"} so the three granularities coexist in one collection without
     * colliding.
     */
    private static List<Map<String, Object>> periodBucketStages() {
        List<Map<String, Object>> stages = new ArrayList<>(onePeriodStages("day"));
        stages.add(new Document("$unionWith", new Document()
                .append("coll", ORDERS)
                .append("pipeline", onePeriodStages("week"))));
        stages.add(new Document("$unionWith", new Document()
                .append("coll", ORDERS)
                .append("pipeline", onePeriodStages("month"))));
        // single terminal write: replace each (period, bucket) doc by its _id
        stages.add(new Document("$merge", new Document()
                .append("into", ORDERS_BY_PERIOD)
                .append("on", "_id")
                .append("whenMatched", "replace")
                .append("whenNotMatched", "insert")));
        return stages;
    }

    /**
     * Bucket {@code orders} for a single granularity ({@code unit} =
     * {@code day}/{@code week}/{@code month}), producing one doc per bucket with a
     * per-product array ({@code byProduct}) and a per-status map ({@code byStatus},
     * e.g. {@code {PAID: 3, SHIPPED: 1}}). The doc {@code _id} is
     * {@code "<unit>|<ISO bucketStart>"} and it carries a {@code period} field.
     * No write stage — the caller composes these via {@code $unionWith} and appends
     * one terminal {@code $merge}.
     */
    private static List<Map<String, Object>> onePeriodStages(String unit) {
        return List.of(
                // 1) truncate createdAt to this unit
                new Document("$addFields", new Document("bucketStart",
                        new Document("$dateTrunc", new Document()
                                .append("date", "$createdAt")
                                .append("unit", unit)))),
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
                        .append("statusPairs", new Document("$push", "$statuses"))),
                // 4) shape the output: composite _id "<unit>|<ISO bucketStart>",
                //    period tag, byStatus map, timestamp
                new Document("$addFields", new Document()
                        .append("period", unit)
                        .append("bucketStart", "$_id")
                        .append("_id", new Document("$concat", List.of(unit + "|",
                                new Document("$dateToString", new Document()
                                        .append("date", "$_id")
                                        .append("format", "%Y-%m-%dT%H:%M:%S.%LZ")))))
                        .append("revenue", new Document("$round", List.of("$revenue", 2)))
                        .append("avgOrderValue", new Document("$round", List.of(
                                new Document("$divide", List.of("$revenue",
                                        new Document("$max", List.of("$orders", 1)))),
                                2)))
                        .append("byStatus", buildByStatus())
                        .append("updatedAt", "$$NOW")),
                // drop the interim status-aggregation field from the output
                new Document("$project", new Document("statusPairs", 0)));
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
