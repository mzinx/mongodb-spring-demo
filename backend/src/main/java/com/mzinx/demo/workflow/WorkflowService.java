package com.mzinx.demo.workflow;

import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.mzinx.demo.config.ConsolidationDemoSeeder;
import com.mzinx.mongodb.messaging.command.CommandMessages;
import com.mzinx.mongodb.messaging.service.MessageService;

/**
 * The <b>order-fulfillment workflow</b> — a set of independent, asynchronous
 * ({@code @Async}) tasks, each triggered from the UI so the effect can be shown
 * step by step. Each task owns a different <em>slice</em> of the order document and
 * they progressively enrich the <b>same</b> {@code orders} docs without
 * overwriting each other (the canonical "build up a document from multiple async
 * writers" merge). They come in two flavours:
 *
 * <h2>Ledger + enrichment-stream slices</h2>
 * {@link #runPayment}, {@link #runInventory} and {@link #runShipping} do NOT write
 * {@code orders} directly. Each writes a record (one per order, {@code _id =
 * orderId}) into its own ledger collection; an {@code enrich-*} change stream then
 * merges just that slice into the matching order (see
 * {@link com.mzinx.demo.config.ConsolidationDemoSeeder}):
 * <ul>
 * <li>{@link #runPayment}   → {@code paymentLog}     → {@code payment.*}</li>
 * <li>{@link #runInventory} → {@code fulfillmentLog} → {@code fulfillment.*}</li>
 * <li>{@link #runShipping}  → {@code shippingAck}    → {@code shipping.*}</li>
 * </ul>
 * This demonstrates change-stream driven merge <em>between</em> collections.
 *
 * <h2>Direct-merge slice</h2>
 * {@link #runRisk} is the contrast: it {@code $merge}s {@code risk.*} straight into
 * {@code orders} with {@code whenMatched: "merge"}, no ledger or mirror.
 *
 * <p>The fan-out rollups ({@code customerSummary} / {@code productInventory}) are no
 * longer produced here — they are maintained automatically by the {@code rollup-*}
 * change streams (see {@link com.mzinx.demo.config.ConsolidationDemoSeeder}); this
 * class only reads them via {@link WorkflowController}.
 *
 * <p>Every task broadcasts a {@code /cmd} REFRESH for the collection it wrote, so
 * connected clients see each slice land live (the order enrichment itself arrives
 * via the mirror stream's {@code /sync} push).
 */
@Service
public class WorkflowService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowService.class);

    public static final String ORDERS = ConsolidationDemoSeeder.ORDERS;
    public static final String CUSTOMER_SUMMARY = ConsolidationDemoSeeder.CUSTOMER_SUMMARY;
    public static final String PRODUCT_INVENTORY = ConsolidationDemoSeeder.PRODUCT_INVENTORY;
    public static final String PAYMENT_LOG = ConsolidationDemoSeeder.PAYMENT_LOG;
    public static final String SHIPPING_ACK = ConsolidationDemoSeeder.SHIPPING_ACK;
    public static final String FULFILLMENT_LOG = ConsolidationDemoSeeder.FULFILLMENT_LOG;

    /** The enrichment task ids exposed to the UI (order is not significant). */
    public static final List<String> ENRICHMENT_TASKS = List.of("payment", "inventory", "shipping", "risk");

    private final MongoTemplate mongoTemplate;
    private final MessageService messageService;
    private final CommandMessages commandMessages;
    /**
     * Self-reference through the Spring proxy. {@code @Async} only takes effect
     * when a method is invoked via the proxy, not on a plain {@code this.} call —
     * so {@link #run(String)} / {@link #runAll()} dispatch through {@code self} to
     * make the enrichment tasks actually run concurrently on the task executor.
     */
    private final WorkflowService self;

    WorkflowService(MongoTemplate mongoTemplate, MessageService messageService, CommandMessages commandMessages,
            @Lazy WorkflowService self) {
        this.mongoTemplate = mongoTemplate;
        this.messageService = messageService;
        this.commandMessages = commandMessages;
        this.self = self;
    }

    /** Dispatches a single named task (used by the per-task UI buttons). */
    public void run(String task) {
        switch (task == null ? "" : task.toLowerCase()) {
            case "payment" -> self.runPayment();
            case "inventory" -> self.runInventory();
            case "shipping" -> self.runShipping();
            case "risk" -> self.runRisk();
            default -> throw new IllegalArgumentException(
                    "Unknown workflow task '" + task + "' (use payment|inventory|shipping|risk)");
        }
    }

    /** Fires every task at once (the "Run all" button) — they merge concurrently. */
    public void runAll() {
        self.runPayment();
        self.runInventory();
        self.runShipping();
        self.runRisk();
    }

    // ------------------------------------------------------------------
    // Same-document enrichment tasks: each $merges its own slice into orders
    // ------------------------------------------------------------------

    /** A small, stable 0..(n-1) bucket derived from the order _id, for demo variety. */
    private static Document idBucket(int n) {
        return new Document("$mod", List.of(
                new Document("$abs", new Document("$toHashedIndexKey", "$_id")), n));
    }

    /**
     * Records a payment per order in the {@code paymentLog} ledger. The
     * {@code enrich-payment} change stream then merges the {@code payment} slice
     * into the matching order.
     */
    @Async
    public void runPayment() {
        writeLedger("payment", PAYMENT_LOG, new Document()
                .append("status", new Document("$cond", List.of(
                        new Document("$in", List.of("$status", List.of("CANCELLED"))),
                        "VOIDED", "CAPTURED")))
                .append("method", new Document("$arrayElemAt", List.of(
                        List.of("card", "paypal", "applepay"), idBucket(3))))
                .append("capturedAmount", "$amount"));
    }

    /**
     * Records an inventory allocation per order in the {@code fulfillmentLog}
     * ledger. The {@code enrich-fulfillment} change stream then merges the
     * {@code fulfillment} slice into the matching order.
     */
    @Async
    public void runInventory() {
        writeLedger("inventory", FULFILLMENT_LOG, new Document()
                .append("allocated", "$quantity")
                .append("warehouse", new Document("$concat", List.of("WH-",
                        new Document("$toUpper", new Document("$substrCP", List.of("$source", 0, 1))))))
                .append("backordered", false));
    }

    /**
     * Records a shipping acknowledgement per order in the {@code shippingAck}
     * ledger. The {@code enrich-shipping} change stream then merges the
     * {@code shipping} slice into the matching order.
     */
    @Async
    public void runShipping() {
        writeLedger("shipping", SHIPPING_ACK, new Document()
                .append("carrier", new Document("$arrayElemAt", List.of(
                        List.of("ups", "fedex", "dhl"), idBucket(3))))
                .append("tracking", new Document("$concat", List.of("TRK-",
                        new Document("$toString", new Document("$abs",
                                new Document("$toHashedIndexKey", "$_id"))))))
                .append("etaDays", new Document("$add", List.of(2, idBucket(5)))));
    }

    /**
     * Enriches every order with a {@code risk} sub-document (fraud scoring) by
     * {@code $merge}-ing directly into {@code orders} — the one enrichment kept as a
     * direct merge (no ledger / mirror), to contrast with the change-stream-mirrored
     * payment/shipping/fulfillment slices.
     */
    @Async
    public void runRisk() {
        // Higher-value orders score higher; > 400 flagged for review.
        Document score = new Document("$min", List.of(99,
                new Document("$round", List.of(new Document("$divide", List.of("$amount", 6)), 0))));
        Document risk = new Document("score", score)
                .append("flagged", new Document("$gt", List.of("$amount", 400)))
                .append("at", "$$NOW");
        List<Document> pipeline = List.of(
                new Document("$set", new Document("risk", risk)),
                new Document("$merge", new Document()
                        .append("into", ORDERS)
                        .append("on", "_id")
                        .append("whenMatched", "merge")
                        .append("whenNotMatched", "discard")));
        mongoTemplate.getCollection(ORDERS).aggregate(pipeline).first();
        logger.info("Workflow task 'risk' enriched {} in place", ORDERS);
        broadcastRefresh(ORDERS);
    }

    /**
     * Shared ledger writer: for every order, derive a ledger record
     * ({@code sliceFields} evaluated against the order, plus bookkeeping
     * {@code _id = orderId}, {@code orderId} and {@code at}) and {@code $merge} it
     * into {@code logCollection} keyed on {@code _id} ({@code whenMatched: replace}
     * so re-running refreshes it). The matching {@code enrich-*} change stream then
     * merges the slice into the order — the enrichment is NOT written to
     * {@code orders} here.
     */
    private void writeLedger(String task, String logCollection, Document sliceFields) {
        Document record = new Document("_id", "$_id").append("orderId", "$_id");
        record.putAll(sliceFields);
        record.append("at", "$$NOW");
        List<Document> pipeline = List.of(
                new Document("$replaceWith", record),
                new Document("$merge", new Document()
                        .append("into", logCollection)
                        .append("on", "_id")
                        .append("whenMatched", "replace")
                        .append("whenNotMatched", "insert")));
        mongoTemplate.getCollection(ORDERS).aggregate(pipeline).first();
        logger.info("Workflow task '{}' wrote {}; mirror stream will merge the slice into {}",
                task, logCollection, ORDERS);
        // The order enrichment arrives via the mirror stream's /sync push; refresh
        // the ledger view immediately too.
        broadcastRefresh(logCollection);
    }

    private void broadcastRefresh(String collection) {
        this.messageService.broadcast(commandMessages.refresh(collection));
    }
}
