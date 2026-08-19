package com.mzinx.demo.web;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mzinx.demo.config.ConsolidationDemoSeeder;
import com.mzinx.mongodb.messaging.command.CommandMessages;
import com.mzinx.mongodb.messaging.service.MessageService;

/**
 * Order intake — writes into a <b>single, polymorphic {@code orders}
 * collection</b>.
 * <p>
 * This deliberately follows MongoDB's
 * <a href="https://mongodb.com/docs/manual/data-modeling/design-patterns/polymorphic-data/polymorphic-schema-pattern/">polymorphic
 * pattern</a>: related-but-differently-shaped entities (orders originating from a
 * web store, a POS terminal, or a marketplace) live in <em>one</em> collection,
 * distinguished by a {@code source} discriminator, sharing a common core schema
 * and keeping each source's original fields under a nested {@code sourceData}
 * sub-document. Splitting the same base entity across three collections
 * ({@code webOrders} / {@code posOrders} / {@code marketplaceOrders}) — the demo's
 * previous shape — is a schema anti-pattern: cross-source reads need
 * {@code $unionWith} or multiple queries, indexes must be duplicated, and adding
 * a new source means a new collection.
 * <p>
 * <b>Core schema (shared by every {@code source}):</b>
 * <table border="1">
 * <caption>orders document</caption>
 * <tr><th>field</th><th>meaning</th></tr>
 * <tr><td>_id</td><td>{@code "<source>:<n>"} — stable, source-prefixed</td></tr>
 * <tr><td>source</td><td>{@code web} | {@code pos} | {@code marketplace} (discriminator)</td></tr>
 * <tr><td>customer</td><td>customer/account label</td></tr>
 * <tr><td>product</td><td>product sku</td></tr>
 * <tr><td>quantity</td><td>units ordered</td></tr>
 * <tr><td>amount</td><td>order value (USD, normalized)</td></tr>
 * <tr><td>status</td><td>lifecycle status (UPPER)</td></tr>
 * <tr><td>createdAt</td><td>order time (Date)</td></tr>
 * <tr><td>sourceData</td><td>source-specific fields (the polymorphic part)</td></tr>
 * </table>
 * The period-rollup dashboard (Scenario 2) reads this collection directly — there
 * is no longer a separate {@code unifiedOrders} consolidation step, because the
 * data is already unified at write time by the shared schema.
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    /** The single polymorphic collection every source writes into. */
    public static final String ORDERS = ConsolidationDemoSeeder.ORDERS;

    public static final String SOURCE_WEB = "web";
    public static final String SOURCE_POS = "pos";
    public static final String SOURCE_MARKETPLACE = "marketplace";

    private static final List<String> PRODUCTS = List.of("keyboard", "mouse", "monitor", "laptop", "webcam", "dock");
    private static final List<String> CUSTOMERS = List.of("acme", "globex", "initech", "umbrella", "wayne", "stark");

    private final MongoTemplate mongoTemplate;
    private final MessageService messageService;
    private final CommandMessages commandMessages;

    ChannelController(MongoTemplate mongoTemplate, MessageService messageService, CommandMessages commandMessages) {
        this.mongoTemplate = mongoTemplate;
        this.messageService = messageService;
        this.commandMessages = commandMessages;
    }

    /**
     * Broadcasts a {@code /cmd} REFRESH for the {@code orders} collection this
     * request just wrote, so every connected client re-fetches the orders view
     * immediately. The derived period summaries update slightly later, via their
     * own {@code /sync} live-data pushes as the change streams recompute them.
     */
    private void broadcastRefresh() {
        this.messageService.broadcast(commandMessages.refresh(ORDERS));
    }

    /** Inserts {@code count} random orders for the given source (default web). */
    @PostMapping("/insert")
    public Map<String, Object> insert(@RequestParam(defaultValue = "web") String channel,
            @RequestParam(defaultValue = "1") int count) {
        String source = sourceOf(channel);
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < Math.min(Math.max(count, 1), 100); i++)
            docs.add(randomFor(source));
        mongoTemplate.getCollection(ORDERS).insertMany(docs);
        broadcastRefresh();
        return Map.of("source", source, "collection", ORDERS, "inserted", docs.size());
    }

    /** Updates one random order's status for the given source (drives an update event). */
    @PostMapping("/update-random")
    public Map<String, Object> updateRandom(@RequestParam(defaultValue = "web") String channel) {
        String source = sourceOf(channel);
        MongoCollection<Document> c = mongoTemplate.getCollection(ORDERS);
        Document victim = c.aggregate(List.of(
                Aggregates.match(Filters.eq("source", source)),
                Aggregates.sample(1))).first();
        if (victim == null)
            return Map.of("updated", 0);
        c.updateOne(Filters.eq("_id", victim.get("_id")),
                Updates.set("status", randomStatus()));
        broadcastRefresh();
        return Map.of("updated", 1, "source", source, "id", String.valueOf(victim.get("_id")));
    }

    /** Deletes one random order for the given source (drives a delete event). */
    @PostMapping("/delete-random")
    public Map<String, Object> deleteRandom(@RequestParam(defaultValue = "web") String channel) {
        String source = sourceOf(channel);
        MongoCollection<Document> c = mongoTemplate.getCollection(ORDERS);
        Document victim = c.aggregate(List.of(
                Aggregates.match(Filters.eq("source", source)),
                Aggregates.sample(1))).first();
        if (victim == null)
            return Map.of("deleted", 0);
        c.deleteOne(Filters.eq("_id", victim.get("_id")));
        broadcastRefresh();
        return Map.of("deleted", 1, "source", source, "id", String.valueOf(victim.get("_id")));
    }

    /**
     * Recent orders for a source (or all sources when {@code channel=all}), read
     * from the single {@code orders} collection. Shows both the shared core fields
     * and the per-source {@code sourceData} sub-document.
     */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(defaultValue = "all") String channel,
            @RequestParam(defaultValue = "25") int limit) {
        var find = mongoTemplate.getCollection(ORDERS).find();
        String source = null;
        if (!"all".equalsIgnoreCase(channel == null ? "" : channel.trim())) {
            source = sourceOf(channel);
            find = find.filter(Filters.eq("source", source));
        }
        List<Document> docs = new ArrayList<>();
        find.sort(Sorts.descending("createdAt"))
                .limit(Math.min(Math.max(limit, 1), 100))
                .forEach(d -> {
                    Documents.stringifyId(d);
                    docs.add(d);
                });
        // Composition per source, so the UI can show the polymorphic breakdown.
        List<Document> bySource = new ArrayList<>();
        mongoTemplate.getCollection(ORDERS).aggregate(List.of(
                Aggregates.group("$source", Accumulators.sum("count", 1)),
                Aggregates.sort(Sorts.descending("count")))).forEach(bySource::add);
        long total = mongoTemplate.getCollection(ORDERS).estimatedDocumentCount();
        return Map.of("source", source == null ? "all" : source, "collection", ORDERS,
                "content", docs, "bySource", bySource, "total", total);
    }

    /** Normalizes a channel/source alias to the canonical discriminator value. */
    private static String sourceOf(String channel) {
        return switch (channel == null ? "" : channel.toLowerCase()) {
            case "web", "weborders" -> SOURCE_WEB;
            case "pos", "posorders" -> SOURCE_POS;
            case "marketplace", "marketplaceorders", "market" -> SOURCE_MARKETPLACE;
            default -> throw new IllegalArgumentException(
                    "Unknown source '" + channel + "' (use web|pos|marketplace)");
        };
    }

    private static Document randomFor(String source) {
        return switch (source) {
            case SOURCE_WEB -> randomWeb();
            case SOURCE_POS -> randomPos();
            default -> randomMarketplace();
        };
    }

    private static String randomStatus() {
        return random(List.of("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"));
    }

    /**
     * A source-prefixed id keeps the three sources collision-free in the shared
     * collection (e.g. {@code "web:531274"}), the same guarantee the old
     * per-collection split gave for free.
     */
    private static String id(String source) {
        return source + ":" + ThreadLocalRandom.current().nextInt(100000, 999999);
    }

    private static Document core(String source, String customer, String product, int qty,
            double amount, String status, Object sourceData) {
        return new Document("_id", id(source))
                .append("source", source)
                .append("customer", customer)
                .append("product", product)
                .append("quantity", qty)
                .append("amount", round(amount))
                .append("status", status)
                .append("createdAt", new Date())
                .append("sourceData", sourceData);
    }

    // --- web: browser checkout; sourceData keeps the tier + line items ---
    private static Document randomWeb() {
        String product = random(PRODUCTS);
        int qty = ThreadLocalRandom.current().nextInt(1, 6);
        double amount = qty * ThreadLocalRandom.current().nextDouble(20, 120);
        Document sourceData = new Document("tier", random(List.of("gold", "silver", "bronze")))
                .append("items", List.of(new Document("sku", product).append("qty", qty)))
                .append("currency", "USD");
        return core(SOURCE_WEB, random(CUSTOMERS), product, qty, amount, "PENDING", sourceData);
    }

    // --- pos: in-store terminal; sourceData keeps the cashier/store/ticket ---
    private static Document randomPos() {
        String product = random(PRODUCTS);
        int qty = ThreadLocalRandom.current().nextInt(1, 6);
        double amount = qty * ThreadLocalRandom.current().nextDouble(20, 120);
        Document sourceData = new Document("cashier", random(List.of("emma", "liam", "noah", "olivia")))
                .append("storeCode", "S" + ThreadLocalRandom.current().nextInt(1, 9))
                .append("ticketNo", "T-" + ThreadLocalRandom.current().nextInt(100000, 999999));
        return core(SOURCE_POS, random(List.of("emma", "liam", "noah", "olivia")),
                product, qty, amount, "PAID", sourceData);
    }

    // --- marketplace: 3rd-party listing; sourceData keeps the external ids ---
    private static Document randomMarketplace() {
        String product = random(PRODUCTS);
        int qty = ThreadLocalRandom.current().nextInt(1, 6);
        double amount = qty * ThreadLocalRandom.current().nextDouble(20, 120);
        Document sourceData = new Document("marketplace", random(List.of("amazon", "ebay", "etsy")))
                .append("externalId", "MP-" + ThreadLocalRandom.current().nextInt(100000, 999999))
                .append("buyerHandle", random(CUSTOMERS) + "_" + ThreadLocalRandom.current().nextInt(10, 99));
        return core(SOURCE_MARKETPLACE, random(CUSTOMERS), product, qty, amount, "PENDING", sourceData);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static <T> T random(List<T> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
