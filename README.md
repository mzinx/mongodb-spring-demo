# mongodb-spring-demo

Demo web application showcasing the `mongodb-spring-*` libraries:

| Library | Demonstrated by |
|---|---|
| [`mongodb-spring-change-stream`](../mongodb-spring-change-stream) | Web UI to create / configure / start / stop change streams, live runtime status (mode, leader, term, epoch, members) |
| [`mongodb-spring-discovery`](../mongodb-spring-discovery) | Instance registry shown in the header; heartbeats enabling `AUTO_RECOVER` / `AUTO_SCALE` modes |
| [`mongodb-spring-message-queuing`](../mongodb-spring-message-queuing) | WebSocket (STOMP) endpoint, live data sync (`/sync`, `/cmd`) and MongoDB-backed message queue demo |
| [`mongodb-spring-aggregation`](../mongodb-spring-aggregation) | Pipeline template CRUD (`_pipelines`) and execution with `{"_ph": "variable"}` placeholder substitution |

## Architecture

```
┌───────────────────────────┐        ┌───────────────────────────────────────────┐
│  frontend (React + Vite)  │  REST  │  backend (Spring Boot 4)                  │
│  http://localhost:5173    │───────▶│  http://localhost:8080                    │
│                           │        │                                           │
│  Streams / Data /         │ STOMP  │  REST API  ── ChangeStreamConfigService   │
│  Aggregations / Messaging │  /ws   │            ── ChangeStreamManager (status)│
│  Live event feed          │◀──────▶│  /ws STOMP ── message-queuing module      │
└───────────────────────────┘        │  eventRelay listener ──▶ /events          │
                                     └──────────────────┬────────────────────────┘
                                                        │ change streams, heartbeats,
                                                        │ configs, resume tokens
                                                 ┌──────▼──────┐
                                                 │   MongoDB   │  (replica set / Atlas)
                                                 └─────────────┘
```

- **backend/** — Spring Boot 4 service consuming all four libraries. It only adds thin
  REST controllers on top of the libraries' public APIs plus two demo
  `ChangeStreamListener` beans (`eventRelay` relays events to the `/events` STOMP
  destination, `consoleLog` just logs).
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

1. **Streams** — on first start a stream `orders-demo` is seeded (collection `orders`,
   listener `eventRelay`). Create your own stream: pick a mode
   (`BOARDCAST` / `AUTO_RECOVER` / `AUTO_SCALE`), a resume strategy, an optional
   aggregation pipeline (e.g. `[{"$match": {"operationType": "insert"}}]`) and a
   listener bean. Definitions are persisted to `_changeStreamConfigs`; the library's
   reconciler starts/restarts/stops streams within ~10 s
   (`change-stream.config-refresh-interval`). The table shows live runtime status
   straight from `ChangeStreamManager`: running flag, leader + fencing term, member
   instances and epoch.
2. **Data Generator** — insert/update/delete random documents in the `orders`
   collection to produce change events.
3. **Live Events** — a WebSocket (STOMP) feed of:
   - `/events`: events relayed by the demo `eventRelay` listener,
   - `/sync` and `/cmd`: live data + refresh commands from the message-queuing
     module (it watches `orders`,`products` via `messaging.watch-collections`).
4. **Aggregations** — edit/save pipeline templates and run them. Try the seeded
   `orders-by-status` template with variables `{"status": "PENDING"}` to see
   `{"_ph": ...}` placeholder substitution.
5. **Messaging** — send a message to `/push`; it is persisted in the TTL-indexed
   `_messages` collection and fanned out to the target destination *through a change
   stream*, so you'll see an `ACK` followed by a `RES` on `/cmd`.
6. **Multi-instance modes** — start a second backend instance to see discovery and the
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
| GET/POST | `/api/data/orders`, `/insert`, `/update-random`, `/delete-random` | Test data generator |
| GET/PUT/DELETE | `/api/pipelines`, `/api/pipelines/{name}` | Aggregation pipeline templates |
| POST | `/api/aggregations/run` | Run a pipeline (inline stages or saved template + variables) |

> Security note: the demo permits all requests and disables CSRF
> (`SecurityConfig`) to keep it friction-free. Do not reuse as-is in production.
