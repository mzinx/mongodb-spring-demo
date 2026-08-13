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
import com.mzinx.mongodb.messaging.command.CommandMessages;
import com.mzinx.mongodb.messaging.service.MessageService;

/**
 * Scenario 1 — <b>Merge / consolidate multiple data sources</b>.
 * <p>
 * Generates orders into three distinct <em>channel</em> collections, each with a
 * deliberately <b>different field shape</b> (as would come from separate upstream
 * systems). These are the inputs consolidated <em>incrementally</em> into a single
 * {@code unifiedOrders} view by three per-channel change streams (each using the
 * event-driven {@code changeMirrorListener} + a normalizing event pipeline — see
 * {@link com.mzinx.demo.config.ConsolidationDemoSeeder}). Nothing here rescans a
 * whole collection: each source write is mirrored as one upsert.
 * <table border="1">
 * <caption>Per-channel field shapes (all mapped to the unified schema)</caption>
 * <tr><th>unified</th><th>webOrders</th><th>posOrders</th><th>marketplaceOrders</th></tr>
 * <tr><td>orderId</td><td>_id (ObjectId)</td><td>ticketNo</td><td>externalId</td></tr>
 * <tr><td>customer</td><td>customer.name</td><td>cashier + "@store"</td><td>buyerHandle</td></tr>
 * <tr><td>product</td><td>items[0].sku</td><td>lineItems[0].product</td><td>listing.sku</td></tr>
 * <tr><td>quantity</td><td>items[0].qty</td><td>lineItems[0].qty</td><td>listing.units</td></tr>
 * <tr><td>amount</td><td>totals.grand (USD)</td><td>total (cents)</td><td>priceUsd</td></tr>
 * <tr><td>status</td><td>state (lowercase)</td><td>status (UPPER)</td><td>fulfilment</td></tr>
 * <tr><td>createdAt</td><td>placedAt</td><td>tsMillis (epoch)</td><td>created (ISO string)</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/channels")
public class ChannelController {

    public static final String WEB = "webOrders";
    public static final String POS = "posOrders";
    public static final String MARKETPLACE = "marketplaceOrders";

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
     * Broadcasts a {@code /cmd} REFRESH for a collection this request just wrote,
     * so every connected client re-fetches the matching view immediately. Called
     * at the end of each write endpoint. This refreshes the SOURCE collection the
     * user wrote (e.g. {@code webOrders}); the derived views ({@code unifiedOrders}
     * and the period summaries) update slightly later, via their own {@code /sync}
     * live-data pushes as the change streams recompute them.
     */
    private void broadcastRefresh(String collection) {
        this.messageService.broadcast(commandMessages.refresh(collection));
    }

    /** Inserts {@code count} random orders into the given channel (default web). */
    @PostMapping("/insert")
    public Map<String, Object> insert(@RequestParam(defaultValue = "web") String channel,
            @RequestParam(defaultValue = "1") int count) {
        String coll = collectionFor(channel);
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < Math.min(Math.max(count, 1), 100); i++)
            docs.add(randomFor(coll));
        mongoTemplate.getCollection(coll).insertMany(docs);
        broadcastRefresh(coll);
        return Map.of("channel", channel, "collection", coll, "inserted", docs.size());
    }

    /** Updates one random order's status in the given channel (drives an update event). */
    @PostMapping("/update-random")
    public Map<String, Object> updateRandom(@RequestParam(defaultValue = "web") String channel) {
        String coll = collectionFor(channel);
        MongoCollection<Document> c = mongoTemplate.getCollection(coll);
        Document victim = c.aggregate(List.of(Aggregates.sample(1))).first();
        if (victim == null)
            return Map.of("updated", 0);
        // Each channel names its status field differently — update the right one.
        String statusField = switch (coll) {
            case WEB -> "state";
            case POS -> "status";
            default -> "fulfilment";
        };
        c.updateOne(Filters.eq("_id", victim.get("_id")),
                Updates.set(statusField, randomStatusFor(coll)));
        broadcastRefresh(coll);
        return Map.of("updated", 1, "channel", channel, "id", String.valueOf(victim.get("_id")));
    }

    /** Deletes one random order from the given channel (drives a delete event). */
    @PostMapping("/delete-random")
    public Map<String, Object> deleteRandom(@RequestParam(defaultValue = "web") String channel) {
        String coll = collectionFor(channel);
        MongoCollection<Document> c = mongoTemplate.getCollection(coll);
        Document victim = c.aggregate(List.of(Aggregates.sample(1))).first();
        if (victim == null)
            return Map.of("deleted", 0);
        c.deleteOne(Filters.eq("_id", victim.get("_id")));
        broadcastRefresh(coll);
        return Map.of("deleted", 1, "channel", channel, "id", String.valueOf(victim.get("_id")));
    }

    /** Recent raw documents from a channel (to show the differing shapes side by side). */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(defaultValue = "web") String channel,
            @RequestParam(defaultValue = "10") int limit) {
        String coll = collectionFor(channel);
        List<Document> docs = new ArrayList<>();
        mongoTemplate.getCollection(coll)
                .find()
                .sort(Sorts.descending("_id"))
                .limit(Math.min(Math.max(limit, 1), 50))
                .forEach(d -> {
                    Documents.stringifyId(d);
                    docs.add(d);
                });
        long total = mongoTemplate.getCollection(coll).estimatedDocumentCount();
        return Map.of("channel", channel, "collection", coll, "content", docs, "total", total);
    }

    private static String collectionFor(String channel) {
        return switch (channel == null ? "" : channel.toLowerCase()) {
            case "web", "weborders" -> WEB;
            case "pos", "posorders" -> POS;
            case "marketplace", "marketplaceorders", "market" -> MARKETPLACE;
            default -> throw new IllegalArgumentException(
                    "Unknown channel '" + channel + "' (use web|pos|marketplace)");
        };
    }

    private static Document randomFor(String coll) {
        return switch (coll) {
            case WEB -> randomWeb();
            case POS -> randomPos();
            default -> randomMarketplace();
        };
    }

    private static String randomStatusFor(String coll) {
        return switch (coll) {
            case WEB -> random(List.of("pending", "paid", "shipped", "delivered", "cancelled"));
            case POS -> random(List.of("OPEN", "PAID", "REFUNDED", "VOID"));
            default -> random(List.of("awaiting", "dispatched", "completed", "returned"));
        };
    }

    // --- web channel: nested customer/items/totals, ObjectId id, Date placedAt ---
    private static Document randomWeb() {
        String product = random(PRODUCTS);
        int qty = ThreadLocalRandom.current().nextInt(1, 6);
        double grand = round(qty * ThreadLocalRandom.current().nextDouble(20, 120));
        return new Document("_id", new ObjectId())
                .append("customer", new Document("name", random(CUSTOMERS)).append("tier", random(List.of("gold", "silver", "bronze"))))
                .append("items", List.of(new Document("sku", product).append("qty", qty)))
                .append("totals", new Document("grand", grand).append("currency", "USD"))
                .append("state", random(List.of("pending", "paid", "shipped", "delivered", "cancelled")))
                .append("placedAt", new Date());
    }

    // --- pos channel: flat, string ticketNo id, amount in CENTS, epoch millis ---
    private static Document randomPos() {
        String product = random(PRODUCTS);
        int qty = ThreadLocalRandom.current().nextInt(1, 6);
        long cents = Math.round(qty * ThreadLocalRandom.current().nextDouble(20, 120) * 100);
        return new Document("_id", "T-" + ThreadLocalRandom.current().nextInt(100000, 999999))
                .append("ticketNo", "T-" + ThreadLocalRandom.current().nextInt(100000, 999999))
                .append("cashier", random(List.of("emma", "liam", "noah", "olivia")))
                .append("storeCode", "S" + ThreadLocalRandom.current().nextInt(1, 9))
                .append("lineItems", List.of(new Document("product", product).append("qty", qty)))
                .append("total", cents)
                .append("status", random(List.of("OPEN", "PAID", "REFUNDED", "VOID")))
                .append("tsMillis", System.currentTimeMillis());
    }

    // --- marketplace channel: externalId, ISO-string date, nested listing ---
    private static Document randomMarketplace() {
        String product = random(PRODUCTS);
        int units = ThreadLocalRandom.current().nextInt(1, 6);
        double priceUsd = round(units * ThreadLocalRandom.current().nextDouble(20, 120));
        return new Document("_id", new ObjectId())
                .append("externalId", "MP-" + ThreadLocalRandom.current().nextInt(100000, 999999))
                .append("buyerHandle", random(CUSTOMERS) + "_" + ThreadLocalRandom.current().nextInt(10, 99))
                .append("listing", new Document("sku", product).append("units", units))
                .append("priceUsd", priceUsd)
                .append("marketplace", random(List.of("amazon", "ebay", "etsy")))
                .append("fulfilment", random(List.of("awaiting", "dispatched", "completed", "returned")))
                .append("created", new Date().toInstant().toString());
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static <T> T random(List<T> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
