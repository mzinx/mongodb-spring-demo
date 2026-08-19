# mongodb-spring-demo

Demo web application showcasing the `mongodb-spring-*` libraries:

| Library | Demonstrated by |
|---|---|
| [`mongodb-spring-change-stream`](https://github.com/mzinx/mongodb-spring-change-stream) | The seeded period-rollup (`orders-by-*`) streams (mode `AUTO_RECOVER`, `resumeStrategy=PER_BATCH`); live runtime status shown in the header |
| [`mongodb-spring-sink`](https://github.com/mzinx/mongodb-spring-sink) | `materializedViewListener` — the slice enrichments (`paymentLog`/`shippingAck`/`fulfillmentLog` → order), the multi-target rollup (`writeStages` → `customerSummary`+`productInventory`) and the single-pass period rollup (`$unionWith` → `ordersByPeriod`), each a `$merge`; **and** `changeMirrorListener` — the whole-document audit mirror (`orders` → `ordersAudit`) |
| [`mongodb-spring-discovery`](https://github.com/mzinx/mongodb-spring-discovery) | Instance registry; heartbeats enabling `AUTO_RECOVER` / `AUTO_SCALE` modes |
| [`mongodb-spring-message-queuing`](https://github.com/mzinx/mongodb-spring-message-queuing) | WebSocket (STOMP) endpoint, live data sync (`/sync`) and live command (`/cmd`) MongoDB-backed message queue demo |
| [`mongodb-spring-aggregation`](https://github.com/mzinx/mongodb-spring-aggregation) | Pipeline templates (`_pipelines`) with `{"_ph": "variable"}` placeholder substitution, run by the materialized-view listener (e.g. the period-agnostic `orders-by-period` rollup) |

Demo site: https://demo.mzinx.com/

> **Managing the streams:** this demo is a *business app* — it **runs** its own
> streams (the consolidation/routing/rollup materialized views, the message
> queue, discovery). The seeded configs are `runOn=BUSINESS`. To
> create/edit/start/stop streams and pipelines from a UI, run the companion
> [`mongostream`](https://github.com/mongodb-ps/mongostream) console against the **same database**: it
> manages the configs but does not execute the business streams. See its README
> for the `runOn` role model.
> MongoStream demo: https://mongostream.mzinx.com/

## Architecture

```
┌───────────────────────────┐        ┌───────────────────────────────────────────┐
│  frontend (React + Vite)  │  REST  │  backend (Spring Boot 4)                  │
│  http://localhost:5173    │───────▶│  http://localhost:8080                    │
│                           │        │                                           │
│  Dashboard / Orders /     │ STOMP  │  REST API  ── ChangeStreamConfigService   │
│  Messaging                │  /ws   │            ── ChangeStreamManager (status)│
│                           │◀──────▶│  /ws STOMP ── message-queuing module      │
│                           │        │  ChannelController ─▶ orders (polymorphic) │
│                           │        │  WorkflowService ─▶ paymentLog/shippingAck/ │
│                           │        │                     fulfillmentLog (ledgers)│
│                           │        │  enrich-* streams ─$merge slice▶ orders     │
│                           │        │  mirror-audit ─copy▶ ordersAudit            │
│                           │        │  rollup-orders ─▶ customerSummary+productInv.│
│                           │        │  orders-by-period ─▶ ordersByPeriod (d/w/m) │
└───────────────────────────┘        └──────────────────┬────────────────────────┘
                                                        │ change streams, heartbeats,
                                                        │ configs, resume tokens
                                                 ┌──────▼──────┐
                                                 │   MongoDB   │  (replica set / Atlas)
                                                 └─────────────┘
```

- **backend/** — Spring Boot 4 service consuming the libraries. It only adds thin
  REST controllers on top of their public APIs. Orders from every source land in a
  single **polymorphic `orders`** collection. The UI-triggered `WorkflowService`
  demonstrates the shapes of MongoDB **`$merge`**: enrichment tasks that write
  ledger collections (`paymentLog` / `fulfillmentLog` / `shippingAck`), which
  `enrich-*` change streams merge as *slices* into the *same* order docs; a direct
  `risk` merge; one `rollup-orders` change stream that fans out (via the listener's
  multi-target `writeStages`) to *different* collections (`customerSummary` /
  `productInventory`); and one `orders-by-period` stream that computes day/week/month
  in a single `$unionWith` pass into `ordersByPeriod`. These use the
  `materializedViewListener`; a separate `mirror-audit` stream uses
  `changeMirrorListener` to copy every order 1:1 into `ordersAudit` (audit/history).
  All from `mongodb-spring-sink`.
- **Schema — polymorphic single collection.** Rather than one collection per source
  (a schema anti-pattern — cross-source reads then need `$unionWith`, indexes get
  duplicated, and each new source is a new collection), all orders share one
  `orders` collection with a `source` discriminator, common core fields, and
  per-source fields under `sourceData`. This follows MongoDB's
  [polymorphic pattern](https://mongodb.com/docs/manual/data-modeling/design-patterns/polymorphic-data/polymorphic-schema-pattern/).
- **Live-update transport.** `orders`, the enrichment ledgers
  (`paymentLog`/`shippingAck`/`fulfillmentLog`), the period summary
  (`ordersByPeriod`), and the rollups (`customerSummary` / `productInventory`) are
  all in `messaging.watch-collections`, so every write pushes the changed document over
  **`/sync`**; the matching frontend view refreshes off that. Order-intake and
  workflow endpoints additionally broadcast a
  **`/cmd` REFRESH** for the collection they wrote.
- **frontend/** — React SPA (Vite). In dev mode it proxies `/api` and `/ws` to the backend.

## Prerequisites

- Java 17+ and Maven
- Node.js 20+
- A MongoDB **replica set** (change streams do not work on a standalone `mongod`):
  - easiest: a free [MongoDB Atlas](https://www.mongodb.com/atlas) cluster, or
  - locally: `mongod --replSet rs0 ...` then `mongosh --eval 'rs.initiate()'`

## Build the libraries

The demo depends on the local snapshot versions, so install them first (in this order):

```bash
cd ../mongodb-spring-aggregation     && mvn -DskipTests install
cd ../mongodb-spring-change-stream   && mvn -DskipTests install
cd ../mongodb-spring-discovery       && mvn -DskipTests install
cd ../mongodb-spring-message-queuing && mvn -DskipTests install
```

## Run

Backend (terminal 1):

```bash
cd backend
MONGODB_URI="mongodb://localhost:27017/mongodb-spring-demo" mvn spring-boot:run
# or an Atlas URI - include the database name in the URI:
# MONGODB_URI="mongodb+srv://user:pass@cluster.mongodb.net/mongodb-spring-demo"
```

Frontend (terminal 2):

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

To serve the UI from the backend instead, run `npm run build` and copy
`frontend/dist/*` into `backend/src/main/resources/static/`, then open
http://localhost:8080.

## Demo walkthrough

1. **Dashboard** — two precomputed **$merge** outputs (toggle between them):
   - *By period* — order summary by **day / week / month** (toggle). All three are
     computed in one `$unionWith` pass by the `orders-by-period` stream (mode
     `AUTO_RECOVER`, listener `materializedViewListener`, `runOn=BUSINESS`) into a
     single `ordersByPeriod` collection; the view filters by `period`.
   - *Rollups* — one `rollup-orders` change stream that fans out (via the listener's
     `writeStages`) to `customerSummary` and `productInventory`, kept live
     automatically (no button).

   All these collections are in `messaging.watch-collections`, so the dashboard
   refreshes live from their **`/sync`** pushes.
2. **Orders** — the single polymorphic `orders` collection and the fulfillment
   workflow. A source selector filters by the `source` discriminator (**all** /
   web / pos / marketplace); the generator buttons insert/update/delete orders for
   the chosen source. The **workflow** toolbar demonstrates `$merge`:
   - per-task buttons — **Run Payment / Inventory / Shipping / Risk**. Payment,
     inventory and shipping write a ledger collection (`paymentLog` /
     `fulfillmentLog` / `shippingAck`); an `enrich-*` change stream then merges the
     slice into the order. Risk merges directly. Either way you watch the order get
     built up step by step by independent writers.
   - **Run all** — fires every task at once, so the merges run concurrently.

   The Enrichment column shows which slices have merged in so far. The order
   enrichment arrives via each mirror stream's `/sync` push (plus the `/cmd` REFRESH
   the endpoints broadcast).
3. **Live Events** — the raw WebSocket (STOMP) feed:
   - `/sync`: changed documents from watched collections
     (`orders`, `paymentLog`, `shippingAck`, `fulfillmentLog`, `ordersByPeriod`, `customerSummary`, `productInventory`),
   - `/cmd`: REFRESH commands (for the collection a write/workflow task touched) and messaging ACK/RES.
4. **Messaging** — private messaging backed by **Spring Session (MongoDB)**. On load
   each browser is prompted for a display name, which is stored on its Spring Session
   (persisted in the `sessions` collection via `@EnableMongoHttpSession`, so identity
   is stable across reconnects and shared across backend instances).    To keep session
   attributes queryable, the demo swaps in a `JacksonMongoSessionConverter` so
   attributes are stored as native BSON under `attrs.*` (not an opaque JDK blob) —
   this is how `attrs.presenceDisplayName` is read back for the roster. Presence
   (who is "online") is derived from the **session keep-alive**: each session
   carries an `expireAt` timestamp that MongoDB's TTL index removes once it lapses
   (`maxInactiveIntervalInSeconds`, 30 min here), and every request the browser
   makes slides it forward. The live **active sessions** roster is then produced by
   **querying the `sessions` collection directly** for every non-expired document —
   there is no in-memory presence registry, and no separate connected flag to keep
   in sync. (An explicit `attrs.presenceConnected` flag was tried first but could
   not be updated reliably: Spring Session's request filter re-saves the *whole*
   session document at the end of every HTTP request, clobbering out-of-band writes
   to a single attribute.) To make presence drop off *promptly* — rather than waiting
   out the 30-min TTL after a browser leaves — the WebSocket `SessionDisconnectEvent`
   pulls the session's `expireAt` in to a short **grace window** (`~30 s`, via a
   targeted `$set` on `expireAt` only, so it stays clear of the full-document
   overwrite race). A reconnect within that window re-extends the expiry through the
   normal session save, so a transient blip doesn't evict an active user. To detect a
   dead connection after an *intermittent network drop* (where no clean close is ever
   received), the **`message-queuing` library enables STOMP heartbeats** by default
   (10 s/10 s, tunable via `messaging.heartbeat.server-ms` / `client-ms`, `0` to
   disable) — without them the disconnect event would only fire on a clean close.
   Connect/disconnect events also trigger an immediate re-broadcast of the roster.
   The roster is broadcast on `/cmd`
   (`{type: "PRESENCE", sessions: [...]}`). Every browser subscribes to its own inbox
   destination `/private/<sessionId>`; the panel shows who is online — pick a session
   to open a private channel and send a `{type: "PRIVATE"}` message to their
   `/private/<sessionId>` destination. The message still travels *through MongoDB*: it
   is persisted in the TTL-indexed `_messages` collection and fanned out by the
   `message-service` change stream, but only the target session's subscriber receives
   it. Open a second browser (or a private window) to see another session appear in the
   roster and exchange private messages.
5. **Multi-instance modes** — start a second backend instance to see discovery and the
   coordination modes in action:

   ```bash
   HOSTNAME=node-2 SERVER_PORT=8081 MONGODB_URI="..." mvn spring-boot:run
   ```

   Both hostnames appear in the header (discovery heartbeats). An `AUTO_RECOVER`
   stream elects a single leader (kill it and watch failover); an `AUTO_SCALE` stream
   partitions events across both instances.

## Merge scenarios

The demo showcases the MongoDB **`$merge`** operator in three complementary shapes
(scenarios 1–3), a whole-document **mirror** (`changeMirrorListener`, scenario 4),
plus a best-practice **polymorphic** schema for the source data.

### Schema · one polymorphic `orders` collection

Orders from every source share a single `orders` collection, following MongoDB's
[polymorphic pattern](https://mongodb.com/docs/manual/data-modeling/design-patterns/polymorphic-data/polymorphic-schema-pattern/):
a `source` discriminator (`web` / `pos` / `marketplace`), a common core schema,
and source-specific fields under `sourceData`.

| field       | meaning                                            |
|-------------|----------------------------------------------------|
| `_id`       | `"<source>:<n>"` — stable, source-prefixed         |
| `source`    | discriminator: `web` \| `pos` \| `marketplace`     |
| `customer`  | customer/account label                             |
| `product`   | product sku                                         |
| `quantity`  | units                                              |
| `amount`    | order value (USD)                                  |
| `status`    | `PENDING` \| `PAID` \| `SHIPPED` \| `DELIVERED` \| `CANCELLED` |
| `createdAt` | order time (Date)                                  |
| `sourceData`| per-source fields (web: `tier`, `items`; pos: `cashier`, `storeCode`, `ticketNo`; marketplace: `marketplace`, `externalId`, `buyerHandle`) |

The previous design split the same base entity across three collections
(`webOrders` / `posOrders` / `marketplaceOrders`) and mirrored them together — a
schema anti-pattern (cross-source reads need `$unionWith`, indexes duplicate, and
each new source is a new collection). Because the polymorphic model is already
unified at write time, no separate `unifiedOrders` consolidation step is needed.

### 1 · Same-document enrichment — `$merge whenMatched: "merge"` (Orders tab)

The order-fulfillment **workflow** (`WorkflowService`) is a set of independent,
UI-triggered `@Async` tasks that each own a different *slice* of the order
document and progressively enrich the **same** `orders` docs without overwriting
each other:

| task        | writes slice   | how                                                          |
|-------------|----------------|--------------------------------------------------------------|
| Payment     | `payment.*`    | writes `paymentLog`; `enrich-payment` stream merges it in    |
| Inventory   | `fulfillment.*`| writes `fulfillmentLog`; `enrich-fulfillment` merges it in   |
| Shipping    | `shipping.*`   | writes `shippingAck`; `enrich-shipping` merges it in         |
| Risk        | `risk.*`       | `$merge` directly into `orders`                              |

Because each task uses `whenMatched: "merge"` (partial update — never a full
replace) and `whenNotMatched: "discard"`, the tasks can run in any order, or
concurrently (**Run all**), and the order document accumulates all slices — the
canonical "build up a document from multiple async writers" merge. Trigger them
step by step from the per-task buttons.

**Payment, inventory and shipping go through a change stream** instead of writing
`orders` inline. Each task records one document per order in its ledger collection
(`paymentLog` / `fulfillmentLog` / `shippingAck`, `_id = orderId`). The matching
**`enrich-*`** change stream watches that ledger and, for just the changed record
(scoped via `{"_ph": "event.documentKey._id"}`), `$merge`s the slice into the
matching order with `whenMatched: "merge"` — O(1) per write, not a full recompute.
They use the `materializedViewListener` because that listener's terminal `$merge`
can do a *partial* merge; the library's `changeMirrorListener` does a full
`replaceOne` and would wipe the order's other fields, so it is deliberately not
used for a slice merge (for a genuine whole-document mirror see scenario 4).
**Risk** is the contrast: it `$merge`s straight into `orders`, no ledger.

**The rollup and period streams ignore enrichment changes.** They watch `orders`,
so every enrichment merge would otherwise trigger a needless full recompute (those
slices aren't in the rollups). Their change-stream `$match` drops an `update` whose
changed fields are *all* enrichment fields (`payment`/`fulfillment`/`shipping`/`risk`);
order intake, status edits and deletes still recompute.

### 2 · Fan-out rollups into different collections — `$merge` (Dashboard → Rollups)

A **single** `rollup-orders` change stream watches `orders` and, via the
listener's multi-target **`writeStages`**, `$merge`s aggregate rows into **two
different** collections in one stream (kept current automatically — no button):

- → `customerSummary` — one doc per customer (orders, revenue, units);
- → `productInventory` — one doc per product (orders, revenue, units).

The recompute is **scoped to the changed order's key**, not a full re-group: the
shared body opens with a `$match` (via `$getField`, so the field name itself is the
per-target `{"_ph": "matchField"}` placeholder) that limits the scan to the one
`customer` (or `product`) from `event.fullDocument`, then `$group`s and
`$merge`s (`whenMatched: "replace"`) that single authoritative row. So it stays
cheap and runs **immediately — no coalescing** (contrast the period rollup below).
Deletes are skipped (a delete carries no `fullDocument`, so the key can't be
resolved; that rollup self-heals on the key's next write). Viewed on the
Dashboard's *Rollups* toggle.

### 3 · Distribute by period — day/week/month in one pass, one collection (Dashboard)

A `$dateTrunc` rollup groups `orders` into buckets, each carrying a per-product
breakdown (`byProduct`) and a per-status map (`byStatus`, e.g.
`{PAID: 3, SHIPPED: 1}`). **All three granularities are computed in a single
`$unionWith` pass** (day, then `$unionWith` week, then `$unionWith` month) and
`$merge`d into **one** `ordersByPeriod` collection — each bucket keyed
`_id = "<period>|<bucketStart>"` with a `period` discriminator. One stream
(`orders-by-period`), one terminal `$merge`. `runOn=BUSINESS`, `AUTO_RECOVER`,
`resumeStrategy=PER_BATCH`. Because this is a genuine full recompute (it can't be
scoped to one order), it uses **coalescing** (`recomputeDebounceMs=750`,
`recomputeMaxDelayMs=3000`) so a burst like "insert 10 orders" folds into a single
recompute instead of ten.

> **Why one collection + one pass?** Previously each granularity was its own
> change stream doing a full `$group` recompute — running day + week + month
> tripled both the stream count and the recompute cost. Collapsing them into a
> single `$unionWith` pass into one polymorphic `ordersByPeriod` collection gives
> all three granularities back from **one** stream. The read side filters by
> `period`.

### 4 · Whole-document mirror — `changeMirrorListener` (audit / history)

The other sink listener, `changeMirrorListener`, is for a cheap **1:1 copy** of the
changed document into another collection — no aggregation over the source. The
`mirror-audit` stream mirrors every `orders` change into **`ordersAudit`**: on
insert/update/replace it upserts the whole current order by `_id` (O(1) per event);
`mirrorDelete=false` means deletes are **retained**, so `ordersAudit` doubles as a
history / soft-delete trail. This is the deliberate contrast to the `$merge`-based
materialized-view streams above — use `changeMirrorListener` when you want the whole
document copied, and `materializedViewListener` when you want to aggregate or merge
a partial slice. (`ordersAudit` is in `messaging.watch-collections`, so its writes
appear in the Live Events feed; there's no dedicated UI view.)

## REST API (backend)

This app is a business app, not a stream-management console, so it exposes no
stream/pipeline CRUD endpoints (use the [`mongostream`](https://github.com/mongodb-ps/mongostream) console
for that). It only reads what it needs to render the demo:

| Method | Path | Description |
|---|---|---|
| GET | `/api/session/me` | Current browser's Spring Session id, private channel and display name |
| POST | `/api/session/name` | Set the display name on the Spring Session |
| GET | `/api/session/active` | Live roster of active (connected) sessions |
| POST | `/api/channels/insert?channel=web\|pos\|marketplace&count=` | Generate orders (into the polymorphic `orders` collection) for a source |
| POST | `/api/channels/update-random`, `/delete-random?channel=` | Mutate a random order for a source (drives update/delete events) |
| GET | `/api/channels/list?channel=all\|web\|pos\|marketplace&limit=` | Recent orders (optionally filtered by source) — Orders tab |
| GET | `/api/unified?source=&limit=` | The `orders` collection + per-source composition counts — Orders tab |
| POST | `/api/workflow/run/{payment\|inventory\|shipping\|risk}` | Run one async workflow task (writes a ledger / merges a slice) — Orders tab |
| POST | `/api/workflow/run-all` | Run every workflow task at once (concurrent merges) — Orders tab |
| GET | `/api/workflow/rollups?kind=customer\|product&limit=` | Read the fan-out rollups (`customerSummary` / `productInventory`, kept live by the `rollup-*` streams) — Dashboard |
| GET | `/api/periods?period=day\|week\|month&limit=` | Period summary from `ordersByPeriod` filtered by `period` — Dashboard |

> Security note: the demo permits all requests and disables CSRF
> (`SecurityConfig`) to keep it friction-free. Do not reuse as-is in production.
