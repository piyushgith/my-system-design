# Chat Application MVP

Phase 0 implementation of the chat platform described in [`docs/15-implementation-roadmap.md`](docs/15-implementation-roadmap.md).

## Recommended stack (MVP)

| Layer | Choice | Why |
|-------|--------|-----|
| Language | **Java 21** | Virtual threads, strong typing, matches your other capstone projects |
| Framework | **Spring Boot 3 + Spring Modulith** | WebSocket, JPA, Redis, Security out of the box; modular monolith aligns with docs |
| Real-time | **Spring WebSocket (raw JSON frames)** | Matches API design in `docs/04-api-design.md` |
| Primary DB | **PostgreSQL** | Conversations, users, messages for MVP (<10M messages) |
| Cache / presence | **Redis** | TTL-based online/offline state |
| Async fan-out | **Spring Application Events** | In-process MVP; Kafka in Phase 1 |

### When to consider alternatives

- **Node.js + Socket.io** — faster initial prototype, weaker domain modeling for a learning capstone.
- **Go + gorilla/websocket** — excellent connection scaling, but you lose Spring ecosystem parity with your URL shortener project.
- **Elixir/Phoenix** — best-in-class real-time, steeper learning curve if your goal is Java interview prep.

## MVP scope delivered

- JWT register/login
- 1:1 and group conversations (max 20 members)
- Real-time messaging over WebSocket
- REST fallback for message history and send
- Message persistence with per-conversation sequence numbers
- Redis presence (online/offline)
- Offline push notification stub (logs only)
- Simple web UI at `http://localhost:8080`

## Not in MVP (Phase 1+)

Media, read receipts, typing indicators, multi-device sync, search, Kafka, horizontal WebSocket scaling.

## Run locally

```bash
cd chat-service
docker compose up -d
./mvnw spring-boot:run
```

Open http://localhost:8080

### Ports

- App: `8080`
- PostgreSQL: `5433`
- Redis: `6380`

## API quick reference

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/auth/register` | Create account |
| POST | `/api/v1/auth/login` | Get JWT |
| POST | `/api/v1/conversations` | Create direct/group chat |
| GET | `/api/v1/conversations` | List inbox |
| GET | `/api/v1/conversations/{id}/messages` | History |
| POST | `/api/v1/conversations/{id}/messages` | Send (REST fallback) |
| POST | `/api/v1/presence/query` | Batch presence lookup |
| WS | `/ws?token=<JWT>` | Real-time messaging |

## Project structure

```
chat-service/
├── identity/       Auth + users
├── conversation/   Groups, membership
├── messaging/      Messages, WebSocket
├── presence/       Redis TTL presence
└── notification/   Push stub
```

## Build & test

```bash
./mvnw clean test
./mvnw clean install -DskipTests=true
```

## Evolution path

1. **Phase 1** — Redis connection registry, Kafka fan-out, delivery receipts
2. **Phase 2** — Cassandra for messages, Elasticsearch search
3. **Phase 3** — Reactions, threads, link previews
4. **Phase 4** — E2EE, WebRTC calls, multi-region

See `docs/` for full architecture, tradeoffs, and interview discussion points.
