# 08 — Security Design: Chat Application

---

## Objective

Design a security model that protects message confidentiality, prevents unauthorized access to conversations, secures media files, prevents abuse, and provides a clear path toward end-to-end encryption.

---

## Threat Model

| Threat | Attack Vector | Severity |
|--------|--------------|---------|
| Unauthorized message access | JWT theft, session hijack | Critical |
| Message injection into a conversation | Missing membership validation | Critical |
| Message enumeration | Sequential message IDs | High |
| Media leakage | Unauthenticated CDN URLs | High |
| WebSocket hijacking | Unvalidated token on reconnect | High |
| DoS via large groups | Malicious 1,000-member group spam | High |
| Typing indicator spam | Excessive TYPING frames | Medium |
| Account takeover | Credential stuffing | High |
| Push notification spoofing | Fake delivery receipts | Medium |

---

## Authentication Architecture

### JWT Token Flow

```
Auth Service issues JWT on login:
{
  "sub": "user-001",           // user_id — stable identifier
  "device_id": "device-xyz",  // which device this token is for
  "session_id": "sess-abc",   // server-side session (for revocation)
  "iat": 1716000000,          // issued at
  "exp": 1716086400,          // 24-hour expiry
  "jti": "jwt-unique-id"      // prevents replay
}
Signed with RS256 (asymmetric)
```

**Why RS256 over HS256?**
- RS256: Private key signs; Chat Service only needs the public key to verify
- HS256: Shared secret — all services that verify must hold the secret (larger blast radius if leaked)
- With RS256: Chat Service never holds the private key → key compromise doesn't affect Chat

### Token Lifecycle

| Event | Action |
|-------|--------|
| Login | Issue new JWT + refresh token |
| JWT expiry (24h) | Client uses refresh token to get new JWT |
| Logout | Invalidate `session_id` in Redis (blacklist); JWT is no longer accepted |
| Logout from all devices | Invalidate all sessions for user_id |
| Password change | Invalidate all sessions for user_id |
| Device deregistered | Invalidate `device_id` specific session |

**Session blacklist**: Redis set `revoked_sessions:{session_id}` with TTL = original JWT expiry. Validated on every request (< 1ms Redis lookup).

### WebSocket Authentication

1. Client opens WebSocket with JWT in query param: `wss://chat.example.com/ws?token=JWT`
2. API Gateway validates JWT, extracts `user_id` + `device_id`
3. On valid: route to WS Server, pass `user_id` + `device_id` in header
4. WS Server trusts these headers (API Gateway is the trust boundary)
5. WS Server creates session: `HSET ws:sessions:{user_id} {device_id} {server_id}`

**Reconnection token**: On connect, server issues an opaque `reconnect_token` (signed, server-side only). Client can reconnect with this token for 30 minutes without re-authenticating with JWT. Prevents repeated JWT validation overhead on flaky mobile connections.

---

## Authorization Model

### Conversation Membership Enforcement

Every message send must validate: **"Is this user currently an active member of this conversation?"**

```
Message Service → gRPC → Conversation Service:
  IsMember(user_id, conversation_id) → bool

Implementation:
  Redis cache: conversation_members:{conv_id} → SET of active user_ids (TTL 5 min)
  On cache miss: PostgreSQL query → rebuild cache
  On member add/remove: invalidate Redis cache
```

**Why validate on every send (not just on connection)?**
A user's membership can be revoked while their WebSocket connection is still active. Checking membership only at connection time creates a window where a removed user continues to send messages.

### Role-Based Actions

| Action | Required Role |
|--------|-------------|
| Send message to group | MEMBER or above |
| Add member to group | ADMIN or OWNER |
| Remove member from group | ADMIN (can remove MEMBER), OWNER (can remove anyone) |
| Change group name/settings | ADMIN or OWNER |
| Delete conversation | OWNER only |
| Edit/delete any message | OWNER (admin override) or sender within 24h |

---

## Message Authorization Rules

| Rule | Enforcement |
|------|------------|
| Cannot send to a conversation you're not a member of | Message Service validates membership |
| Cannot read messages from before you joined | Message Service filters `WHERE seq >= member.joined_seq` |
| Cannot read deleted messages | is_deleted flag checked in all queries |
| Cannot delete someone else's message (for-everyone) | Message Service validates sender_id == requesting_user_id |
| Cannot edit someone else's message | Same as delete |

---

## Transport Security

### TLS Configuration

All client connections use TLS 1.3 minimum:
- HTTP API: TLS 1.3, HSTS (strict-transport-security) with 1-year max-age
- WebSocket: WSS (TLS 1.3 underneath)
- Certificate pinning: Recommended for mobile clients (prevents MITM by rogue CAs)

### Internal Service Communication

| Channel | Security |
|---------|---------|
| API Gateway → WS Servers | TLS (internal mTLS) |
| WS Servers → Message Service (gRPC) | mTLS with service certificates |
| Message Service → Cassandra | TLS + client certificate authentication |
| Services → Redis | TLS + AUTH password |
| Services → Kafka | SASL/SCRAM + TLS |

---

## Media Security

### The CDN URL Problem

If media CDN URLs are permanent and guessable, a leaked URL gives permanent access to the media file.

**Solution: Time-limited signed CDN URLs**

```
Media upload flow:
1. Client requests pre-signed S3 upload URL (valid 5 minutes)
2. Client uploads to S3 directly
3. Media Service generates signed CDN URL (valid 24 hours)
4. Client embeds CDN URL in message

Media access flow:
1. Client requests to view media → sends cdn_url to Media Service for refresh
2. Media Service validates: is requester a member of the conversation that owns this media?
3. If yes: issue new signed URL (valid 1 hour)
4. Client loads media from CDN using fresh signed URL
```

**CloudFront signed URLs**: Use an RSA key pair. URL contains signature = HMAC of (resource path + expiry + policy). CloudFront verifies signature without calling back to the server.

### Virus Scanning

All uploaded media goes through virus scanning before becoming accessible:
```
Upload → S3 (private bucket) → Lambda trigger → ClamAV scan
If CLEAN: copy to public CDN bucket, update media_files.scan_status = 'CLEAN'
If FLAGGED: do NOT copy, mark as FLAGGED, alert moderation team
```

Media is quarantined until scanned — clients see "Processing..." until scan_status = 'CLEAN'.

---

## Rate Limiting and Abuse Prevention

### Message Rate Limiting (Anti-Spam)

```
Per user:
  - 60 messages/minute (prevents message flooding)
  - 10 media uploads/minute
  - 5 conversation creates/hour

Per conversation:
  - If a single user sends > 10 messages in 10 seconds → temporary 30-second ban from that conversation
  - Repeated violations → account flagged for review
```

### Bot Detection

Typing indicators and send patterns:
- Legitimate users show variable inter-message timing
- Bots send at machine speed (< 100ms between messages)
- Suspicious accounts with 0 typing indicators + <100ms message timing → flagged

### CSRF Protection

WebSocket connections: Not vulnerable to CSRF (browser same-origin policy prevents cross-origin WebSocket to non-matching domains, plus JWT in request validates origin).

REST API: Double-submit cookie pattern + `X-Requested-With: XMLHttpRequest` header check for browser clients. Not needed for mobile clients (no browser CSRF attack vector).

---

## End-to-End Encryption (E2EE) Path

### Why Not in v1?

- E2EE requires key distribution infrastructure (Signal's Extended Triple Diffie-Hellman)
- Server-side search becomes impossible (can't index ciphertext)
- Server-side moderation becomes impossible
- Multi-device key sync (one-time prekeys) is complex
- Backup encryption adds more complexity

### Architecture Constraints That Must NOT be Violated

To allow E2EE in future versions:
1. Message `content` field must be treated as opaque — server never parses it for functional logic
2. No server-side message transformation (no HTML rendering, no emoji conversion on server)
3. Media metadata (dimensions, duration) stored separately from content blob — content can be encrypted independently
4. Message IDs and sequence numbers are server-generated (not encrypted) — they're metadata, not content

### E2EE Design (Future, Signal Protocol)

When added:
- Each device generates an identity key pair (Ed25519) + signed prekeys + one-time prekeys
- Server stores PUBLIC keys only (device key registry)
- Sender fetches recipient's public key → performs X3DH key agreement → derives session key
- Messages encrypted client-side with AES-256-GCM → `content` field contains ciphertext only
- Server routes ciphertext without knowledge of plaintext
- Search service disabled for E2EE conversations

---

## Audit Logging

All security-relevant events are written to an immutable audit log:

| Event | Fields Logged |
|-------|-------------|
| User login | timestamp, user_id, device_id, IP, user_agent, success/failure |
| WebSocket connect | timestamp, user_id, device_id, IP, server_id |
| WebSocket disconnect | timestamp, duration, reason |
| Member added to group | timestamp, actor_id, conversation_id, target_user_id |
| Member removed from group | timestamp, actor_id, conversation_id, target_user_id |
| Message deleted for everyone | timestamp, actor_id, message_id, conversation_id |
| Media upload flagged | timestamp, media_id, uploader_id, scan_result |
| Rate limit exceeded | timestamp, user_id, action, count |

Audit logs: append-only, written to ClickHouse, retained for 2 years, accessible only to compliance team.

---

## Secrets Management

| Secret | Management |
|--------|-----------|
| JWT signing private key | AWS Secrets Manager, rotation every 90 days |
| Database credentials | AWS Secrets Manager, rotation every 30 days |
| Redis AUTH password | Kubernetes Secret (sealed-secret encrypted) |
| Kafka SASL credentials | AWS Secrets Manager |
| S3 signing keys | AWS IAM role (no static credentials) |
| CDN signing key pair | AWS Secrets Manager |
| Third-party push tokens (per user) | PostgreSQL, encrypted at rest (pgcrypto) |

Services retrieve secrets at startup via AWS SDK — secrets are never embedded in container images or environment variables committed to Git.

---

## SQL Injection Prevention

- All database access via parameterized queries (JDBC PreparedStatement / Cassandra CQL prepared statements)
- No dynamic SQL construction
- ORM (if used): Spring Data JPA with named queries; no native query concatenation

## XSS Prevention

- Message content is stored as raw text, never interpreted as HTML on the server
- Client-side rendering: all message content HTML-escaped before DOM insertion
- Content Security Policy (CSP) headers on web client: `default-src 'self'`
- Media served from separate CDN domain to isolate any injected scripts

## OWASP Top 10 Coverage

| OWASP Vulnerability | Mitigation |
|--------------------|-----------|
| A01 Broken Access Control | Membership validation on every message send/read |
| A02 Cryptographic Failures | TLS 1.3 everywhere, RS256 JWT, AES-256 at rest |
| A03 Injection | Parameterized queries, input validation at API boundary |
| A04 Insecure Design | Threat model documented, least-privilege service accounts |
| A05 Security Misconfiguration | Secrets management, no default credentials, CSP headers |
| A06 Vulnerable Components | Automated dependency scanning (Snyk/Dependabot) |
| A07 Auth & Session Failures | JWT expiry, session revocation, reconnect token TTL |
| A08 Software & Data Integrity | Signed CDN URLs, virus scanning for media |
| A09 Security Logging | Audit log for all security events |
| A10 SSRF | Media URL validation (only CDN domain allowed in messages) |
