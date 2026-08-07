# mongodb-spring-demo

Demo web application showcasing the `mongodb-spring-*` libraries:

| Library | Demonstrated by |
|---|---|
| [`mongodb-spring-change-stream`](../mongodb-spring-change-stream) | The seeded `order-summary` stream (mode `AUTO_RECOVER`) that precomputes the Dashboard summaries; live runtime status shown on the Dashboard |
| [`mongodb-spring-discovery`](../mongodb-spring-discovery) | Instance registry shown in the header; heartbeats enabling `AUTO_RECOVER` / `AUTO_SCALE` modes |
| [`mongodb-spring-message-queuing`](../mongodb-spring-message-queuing) | WebSocket (STOMP) endpoint, live data sync (`/sync`) and live command (`/cmd`) MongoDB-backed message queue demo |
| [`mongodb-spring-aggregation`](../mongodb-spring-aggregation) | Pipeline templates (`_pipelines`) with `{"_ph": "variable"}` placeholder substitution, run internally by the summary listener and the Orders `$facet` pagination |

## Architecture

```
┌───────────────────────────┐        ┌───────────────────────────────────────────┐
│  frontend (React + Vite)  │  REST  │  backend (Spring Boot 4)                  │
│  http://localhost:5173    │───────▶│  http://localhost:8080                    │
│                           │        │                                           │
│  Dashboard / Orders /     │ STOMP  │  REST API  ── ChangeStreamConfigService   │
│  Messaging / Live events  │  /ws   │            ── ChangeStreamManager (status)│
│                           │◀──────▶│  /ws STOMP ── message-queuing module      │
└───────────────────────────┘        │  orderSummaryListener ─▶ orderSummaries   │
                                     └──────────────────┬────────────────────────┘
                                                        │ change streams, heartbeats,
                                                        │ configs, resume tokens
                                                 ┌──────▼──────┐
                                                 │   MongoDB   │  (replica set / Atlas)
                                                 └─────────────┘
```

- **backend/** — Spring Boot 4 service consuming all four libraries. It only adds thin
  REST controllers on top of the libraries' public APIs plus two demo
  `ChangeStreamListener` beans: `orderSummaryListener` precomputes the daily order
  summary collection (`orderSummaries`) by running the `orders-daily-summary`
  pipeline template with `$merge`, and `consoleLog` just logs.
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

1. **Dashboard** — daily order summary (orders, revenue, avg value, per-status
   counts). It is *precomputed*: on first start a stream `order-summary` is seeded
   (collection `orders`, mode `AUTO_RECOVER`, listener `orderSummaryListener`,
   persisted in `_changeStreamConfigs`). That change stream triggers
   `orderSummaryListener`, which re-runs the `orders-daily-summary` pipeline template
   (`$merge` into `orderSummaries`, with a `{"_ph": "runId"}` variable). Because the
   stream runs in `AUTO_RECOVER` mode, exactly one instance (the leader) recomputes;
   the header line shows live runtime status straight from `ChangeStreamManager`
   (leader + running flag). `orderSummaries` is also in
   `messaging.watch-collections`, so the dashboard refreshes live as summaries change.
2. **Orders** — paginated view of the `orders` collection (paged through the
   aggregation library's `$facet` pagination), with generator buttons to
   insert/update/delete random documents. The message-queuing live-data service
   watches `orders` and broadcasts a REFRESH command on `/cmd` for every change, so
   the page updates in real time — try writing from `mongosh` while it's open.
3. **Live Events** — the raw WebSocket (STOMP) feed:
   - `/sync`: changed documents from watched collections (`orders`, `orderSummaries`),
   - `/cmd`: REFRESH commands and messaging ACK/RES.
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

## REST API (backend)

| Method | Path | Description |
|---|---|---|
| GET | `/api/streams` | Persisted change stream definitions |
| POST | `/api/streams` | Create / reconfigure a stream |
| POST | `/api/streams/{id}/start` | Enable (start) a stream |
| POST | `/api/streams/{id}/stop` | Disable (stop) a stream |
| DELETE | `/api/streams/{id}` | Delete a stream definition |
| GET | `/api/streams/status` | Runtime status of all registered streams |
| GET | `/api/streams/{id}/status` | Runtime status of one stream |
| GET | `/api/streams/listeners` | Available `ChangeStreamListener` bean names |
| GET | `/api/instances` | Live instances (discovery heartbeats) |
| GET | `/api/session/me` | Current browser's Spring Session id, private channel and display name |
| POST | `/api/session/name` | Set the display name on the Spring Session |
| GET | `/api/session/active` | Live roster of active (connected) sessions |
| GET | `/api/data/orders?page=&size=` | Paginated orders (aggregation library `$facet` pagination) |
| POST | `/api/data/orders/insert`, `/update-random`, `/delete-random` | Test data generator |
| GET | `/api/summary` | Precomputed daily order summaries |

> Security note: the demo permits all requests and disables CSRF
> (`SecurityConfig`) to keep it friction-free. Do not reuse as-is in production.
