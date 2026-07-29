# mongodb-spring-demo

Demo web application showcasing the `mongodb-spring-*` libraries:

| Library | Demonstrated by |
|---|---|
| [`mongodb-spring-change-stream`](../mongodb-spring-change-stream) | Web UI to create / configure / start / stop change streams, live runtime status (mode, leader, term, epoch, members) |
| [`mongodb-spring-discovery`](../mongodb-spring-discovery) | Instance registry shown in the header; heartbeats enabling `AUTO_RECOVER` / `AUTO_SCALE` modes |
| [`mongodb-spring-message-queuing`](../mongodb-spring-message-queuing) | WebSocket (STOMP) endpoint, live data sync (`/sync`) and live command (`/cmd`) MongoDB-backed message queue demo |
| [`mongodb-spring-aggregation`](../mongodb-spring-aggregation) | Pipeline template CRUD (`_pipelines`) and execution with `{"_ph": "variable"}` placeholder substitution |

## Architecture

```
┌───────────────────────────┐        ┌───────────────────────────────────────────┐
│  frontend (React + Vite)  │  REST  │  backend (Spring Boot 4)                  │
│  http://localhost:5173    │───────▶│  http://localhost:8080                    │
│                           │        │                                           │
│  Streams / Orders /       │ STOMP  │  REST API  ── ChangeStreamConfigService   │
│  Dashboard / Aggregations │  /ws   │            ── ChangeStreamManager (status)│
│  Messaging / Live events  │◀──────▶│  /ws STOMP ── message-queuing module      │
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

1. **Streams** — on first start a stream `order-summary` is seeded (collection
   `orders`, mode `AUTO_RECOVER`, listener `orderSummaryListener`). Create your own
   stream: pick a mode (`BOARDCAST` / `AUTO_RECOVER` / `AUTO_SCALE`), a resume
   strategy, an optional aggregation pipeline (e.g.
   `[{"$match": {"operationType": "insert"}}]`) and a listener bean. Definitions are
   persisted to `_changeStreamConfigs`; the library's reconciler
   starts/restarts/stops streams within ~10 s
   (`change-stream.config-refresh-interval`). The table shows live runtime status
   straight from `ChangeStreamManager`: running flag, leader + fencing term, member
   instances and epoch.
2. **Orders** — paginated view of the `orders` collection (paged through the
   aggregation library's `$facet` pagination), with generator buttons to
   insert/update/delete random documents. The message-queuing live-data service
   watches `orders` and broadcasts a REFRESH command on `/cmd` for every change, so
   the page updates in real time — try writing from `mongosh` while it's open.
3. **Dashboard** — daily order summary (orders, revenue, avg value, per-status
   counts). It is *precomputed*: the `order-summary` change stream triggers
   `orderSummaryListener`, which re-runs the `orders-daily-summary` pipeline template
   (`$merge` into `orderSummaries`, with a `{"_ph": "runId"}` variable). Because the
   stream runs in `AUTO_RECOVER` mode, exactly one instance (the leader) recomputes.
   `orderSummaries` is also in `messaging.watch-collections`, so the dashboard
   refreshes live as summaries change.
4. **Live Events** — the raw WebSocket (STOMP) feed:
   - `/sync`: changed documents from watched collections (`orders`, `orderSummaries`),
   - `/cmd`: REFRESH commands and messaging ACK/RES.
5. **Aggregations** — edit/save pipeline templates and run them. Try the seeded
   `orders-by-status` template with variables `{"status": "PENDING"}` to see
   `{"_ph": ...}` placeholder substitution.
6. **Messaging** — send a message to `/push`; it is persisted in the TTL-indexed
   `_messages` collection and fanned out to the target destination *through a change
   stream*.
7. **Multi-instance modes** — start a second backend instance to see discovery and the
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
| GET | `/api/data/orders?page=&size=` | Paginated orders (aggregation library `$facet` pagination) |
| POST | `/api/data/orders/insert`, `/update-random`, `/delete-random` | Test data generator |
| GET | `/api/summary` | Precomputed daily order summaries |
| POST | `/api/summary/recompute` | Force a summary recompute |
| GET/PUT/DELETE | `/api/pipelines`, `/api/pipelines/{name}` | Aggregation pipeline templates |
| POST | `/api/aggregations/run` | Run a pipeline (inline stages or saved template + variables) |

> Security note: the demo permits all requests and disables CSRF
> (`SecurityConfig`) to keep it friction-free. Do not reuse as-is in production.
