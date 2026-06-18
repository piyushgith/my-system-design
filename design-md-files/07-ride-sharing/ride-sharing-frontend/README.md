# NightRide Frontend

Production-ready React frontend for the Ride Sharing Spring Boot V1 API.

## Stack

- React 19 + TypeScript (strict)
- Vite 8 + Tailwind CSS 4
- React Router 7
- TanStack Query v5
- Axios + Zustand
- React Hook Form + Zod
- Leaflet maps
- Vitest + React Testing Library

## Quick start

```bash
# Terminal 1 — backend
cd ../ride-sharing-service
./mvnw spring-boot:run

# Terminal 2 — frontend
cp .env.example .env
npm install
npm run dev
```

Open http://localhost:5173

## Demo flow

1. **Driver tab/browser:** `/login/dev` → Continue as Driver → Go online
2. **Rider tab/browser:** `/login/dev` → Continue as Rider → Get fare estimate → Request ride
3. **Driver:** Accept offer within 15s → Arrive → Start (OTP from driver accept response) → Complete
4. **Rider:** Rate trip on completion

## Auth (V1)

Backend uses dev headers, not JWT:

| Mode | Headers |
|------|---------|
| Demo shortcut | `X-Uid: rider` or `driver` |
| OTP login | `X-Uid: {uuid}` + `X-Role: RIDER` |

Mock OTP: `123456`

## Scripts

| Command | Purpose |
|---------|---------|
| `npm run dev` | Dev server (:5173, proxies `/v1` → :8080) |
| `npm run build` | Production build |
| `npm run test` | Vitest unit tests |
| `npm run preview` | Preview production build |

## Realtime note

Browser WebSocket API cannot send custom auth headers. Active trip screens use **HTTP polling (2–3s)** instead of WebSocket. Use `wscat` with headers for raw WebSocket testing.

## Documentation

- [`../FRONTEND_ANALYSIS.md`](../FRONTEND_ANALYSIS.md)
- [`../FRONTEND_ARCHITECTURE.md`](../FRONTEND_ARCHITECTURE.md)
- [`../IMPLEMENTATION_PROGRESS.md`](../IMPLEMENTATION_PROGRESS.md)
