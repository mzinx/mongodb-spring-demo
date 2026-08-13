# mongodb-spring-demo

Demo web application showcasing the `mongodb-spring-*` libraries:

| Library | Demonstrated by |
|---|---|
| [`mongodb-spring-change-stream`](../mongodb-spring-change-stream) | The seeded consolidation (`unify-*`) and period-rollup (`orders-by-*`) streams (mode `AUTO_RECOVER`, `resumeStrategy=PER_BATCH`); live runtime status shown in the header |
| [`mongodb-spring-sink`](../mongodb-spring-sink) | The event-driven `changeMirrorListener` (incremental per-event mirroring behind the `unify-*` merge streams) **and** the generic `materializedViewListener` (runs the `orders-by-day/week/month` `$dateTrunc` rollups, each `$merge`-ing into its own collection) |
| [`mongodb-spring-discovery`](../mongodb-spring-discovery) | Instance registry shown in the header; heartbeats enabling `AUTO_RECOVER` / `AUTO_SCALE` modes |
| [`mongodb-spring-message-queuing`](../mongodb-spring-message-queuing) | WebSocket (STOMP) endpoint, live data sync (`/sync`) and live command (`/cmd`) MongoDB-backed message queue demo |
| [`mongodb-spring-aggregation`](../mongodb-spring-aggregation) | Pipeline templates (`_pipelines`) with `{"_ph": "variable"}` placeholder substitution, run by the materialized-view listener (e.g. the period-agnostic `orders-by-period` rollup) |

> **Managing the streams:** this demo is a *business app* — it **runs** its own
> streams (the consolidation/routing/rollup materialized views, the message
> queue, discovery). The seeded configs are `runOn=BUSINESS`. To
> create/edit/start/stop streams and pipelines from a UI, run the companion
> [`mongostream`](../mongostream) console against the **same database**: it
> manages the configs but does not execute the business streams. See its README
> for the `runOn` role model.

## Architecture

```
┌───────────────────────────┐        ┌───────────────────────────────────────────┐
│  frontend (React + Vite)  │  REST  │  backend (Spring Boot 4)                  │
│  http://localhost:5173    │───────▶│  http://localhost:8080                    │
│                           │        │                                           │
│  Dashboard / Orders /     │ STOMP  │  REST API  ── ChangeStreamConfigService   │
│  Messaging                │  /ws   │            ── ChangeStreamManager (status)│
│                           │◀──────▶│  /ws STOMP ── message-queuing module      │
│                           │        │  changeMirrorListener ─▶ unifiedOrders     │
│                           │        │  materializedViewListener ─▶ ordersByDay /  │
│                           │        │              ordersByWeek / ordersByMonth  │
└───────────────────────────┘        └──────────────────┬────────────────────────┘
                                                        │ change streams, heartbeats,
                                                        │ configs, resume tokens
                                                 ┌──────▼──────┐
                                                 │   MongoDB   │  (replica set / Atlas)
                                                 └─────────────┘
```

- **backend/** — Spring Boot 4 service consuming the libraries. It only adds thin
  REST controllers on top of their public APIs. The `changeMirrorListener` (from
  `mongodb-spring-sink`) incrementally mirrors each channel write into
  `unifiedOrders`; the `materializedViewListener` rolls `unifiedOrders` up into
  three period collections (`ordersByDay` / `ordersByWeek` / `ordersByMonth`) via
  `$merge`.
- **Live-update transport.** The **derived** collections — `unifiedOrders` and the
  three period summaries (`ordersByDay/Week/Month`) — are in
  `messaging.watch-collections`, so every recompute write pushes the changed
  document over **`/sync`**; the matching frontend view refreshes off that. The
  **source** channel collections (`webOrders/posOrders/marketplaceOrders`) are not
  watched; instead each write endpoint (`ChannelController`) broadcasts a **`/cmd`
  REFRESH** for the collection it just wrote, so the raw channel views update on
  every client the moment anyone writes. (The refresh is issued by the endpoint
  that made the write — not by the sink library, which stays free of any
  messaging/refresh coupling.)
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

1. **Dashboard** — order summary bucketed by period (day / week / month; toggle
   the granularity). It is *precomputed*: `unifiedOrders` is rolled up by the
   `orders-by-day/week/month` streams (mode `AUTO_RECOVER`, listener
   `materializedViewListener`, `runOn=BUSINESS`), each a `$dateTrunc` `$group` +
   `$merge` into its **own** collection (`ordersByDay` / `ordersByWeek` /
   `ordersByMonth`). Because the streams run in `AUTO_RECOVER` mode, exactly one
   instance (the leader) recomputes; the header shows live runtime status from
   `ChangeStreamManager`. All three period collections are in
   `messaging.watch-collections`, so the dashboard refreshes live from their **`/sync`**
   full-document pushes. See
   [Consolidation scenarios](#consolidation-scenarios) for the pipeline design.
2. **Orders** — the source channels and their consolidated view. A channel
   dropdown selects **Unified (all sources)** — the read-only `unifiedOrders` merge
   with a per-source count — or one of the three writable source channels
   (`webOrders` / `posOrders` / `marketplaceOrders`), each with its own
   differently-shaped documents and generator buttons. Every write is mirrored
   incrementally into `unifiedOrders` by the per-source `unify-*` change streams.
   The unified view refreshes off `unifiedOrders`' `/sync` push; a raw channel view
   refreshes off the `/cmd` REFRESH its write endpoint broadcasts — try writing from
   `mongosh` while it's open (the `/sync`-backed views update; the raw-channel view
   only updates on writes made through the app, which issue the REFRESH).
3. **Live Events** — the raw WebSocket (STOMP) feed:
   - `/sync`: changed documents from watched collections
     (`unifiedOrders`, `ordersByDay`, `ordersByWeek`, `ordersByMonth`),
   - `/cmd`: REFRESH commands (for the source channel a write touched) and messaging ACK/RES.
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

## Consolidation scenarios

Two change-stream driven data-movement patterns, seeded on first start by
`ConsolidationDemoSeeder` (idempotent) and surfaced across the UI: the **merge**
on the *Orders* tab and the **period distribution** on the *Dashboard*. All
streams are `runOn=BUSINESS`, `AUTO_RECOVER`, and use `resumeStrategy=PER_BATCH` —
deliberately trading a small window of eventual consistency for **incremental,
per-event performance** and **restart resilience** (a restart resumes from the
last checkpoint rather than recomputing).

### 1 · Merge / consolidate multiple sources → `unifiedOrders` (Orders tab)

Three source collections model separate upstream systems, each with a
**different field shape**:

| unified   | `webOrders`        | `posOrders`          | `marketplaceOrders`   |
|-----------|--------------------|----------------------|-----------------------|
| customer  | `customer.name`    | `cashier`@`storeCode`| `buyerHandle`         |
| product   | `items[0].sku`     | `lineItems[0].product`| `listing.sku`        |
| quantity  | `items[0].qty`     | `lineItems[0].qty`   | `listing.units`       |
| amount    | `totals.grand` USD | `total` (cents)      | `priceUsd`            |
| status    | `state` (lower)    | `status` (UPPER)     | `fulfilment`          |
| createdAt | `placedAt` (Date)  | `tsMillis` (epoch)   | `created` (ISO string)|

Rather than periodically rescanning all three with a `$unionWith` recompute,
each source has its **own change stream** using the event-driven
`changeMirrorListener` (from `mongodb-spring-sink`). On each source write, the
stream's **event pipeline** normalizes just the one changed document to the
unified schema (`$set` on `fullDocument`) and the listener upserts it — **O(1)
work per event**. The unified `_id` is `"<source>:<original _id>"` so channels
never collide, and `documentKey._id` is rewritten so deletes hit the right
unified doc (the event's top-level `_id`/resume token is never touched). Updates
enable `fullDocument=UPDATE_LOOKUP` so the current document is available to
normalize.

Streams: `unify-web`, `unify-pos`, `unify-marketplace`.

`unifiedOrders` is in `messaging.watch-collections`, so the Orders page's unified
view refreshes live off its `/sync` push as the mirror streams catch up.

### 2 · Distribute by period into separate collections (daily / weekly / monthly) (Dashboard)

A `$dateTrunc`-based rollup groups `unifiedOrders` into period buckets, each
carrying a per-product breakdown (`byProduct`) and a per-status map (`byStatus`,
e.g. `{PAID: 3, SHIPPED: 1}`). Because this is a genuine group aggregation, it
uses the `materializedViewListener` (full recompute + `$merge`) — the deliberate
contrast to the incremental mirrors above.

The three granularities are **distributed across three distinct collections** —
`ordersByDay`, `ordersByWeek`, `ordersByMonth`. **One period-agnostic template**
(`orders-by-period`) is shared by all three streams: its body ends *before* the
write stage, and each stream supplies (a) its `period` attribute — bound into
`$dateTrunc` via `{"_ph": "period"}` — and (b) its own terminal `$merge`
(the `writeStage` attribute) targeting its dedicated collection. The bucket `_id`
is the truncated `bucketStart` date. All three collections are in
`messaging.watch-collections`, so the Dashboard refreshes live from their `/sync`
pushes; it switches between the three collections as you toggle the granularity.

Streams: `orders-by-day` → `ordersByDay`, `orders-by-week` → `ordersByWeek`,
`orders-by-month` → `ordersByMonth` (all share the `orders-by-period` template).

## REST API (backend)

This app is a business app, not a stream-management console, so it exposes no
stream/pipeline CRUD endpoints (use the [`mongostream`](../mongostream) console
for that). It only reads what it needs to render the demo:

| Method | Path | Description |
|---|---|---|
| GET | `/api/instances` | Live instances (discovery heartbeats) |
| GET | `/api/session/me` | Current browser's Spring Session id, private channel and display name |
| POST | `/api/session/name` | Set the display name on the Spring Session |
| GET | `/api/session/active` | Live roster of active (connected) sessions |
| POST | `/api/channels/insert?channel=web\|pos\|marketplace&count=` | Generate orders into a source channel (Scenario 1) |
| POST | `/api/channels/update-random`, `/delete-random?channel=` | Mutate a random channel order (drives update/delete events) |
| GET | `/api/channels/list?channel=&limit=` | Recent raw docs from a channel (shows differing native shapes) — Orders tab |
| GET | `/api/unified?source=&limit=` | Consolidated `unifiedOrders` view + per-source counts (Scenario 1) — Orders tab |
| GET | `/api/periods?period=day\|week\|month&limit=` | Period summary from the matching collection (`ordersByDay`/`Week`/`Month`) (Scenario 2) — Dashboard |

> Security note: the demo permits all requests and disables CSRF
> (`SecurityConfig`) to keep it friction-free. Do not reuse as-is in production.
