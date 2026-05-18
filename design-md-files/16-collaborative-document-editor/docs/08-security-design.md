# 08 — Security Design

## Objective
Define the authentication, authorization, encryption, and threat model for the Collaborative Document Editor. Cover both user-facing security and internal service-to-service security.

---

## Threat Model

| Threat | Vector | Severity |
|---|---|---|
| Unauthorized document access | Stolen/expired JWT, link token abuse | Critical |
| Privilege escalation | ACL bypass via direct API call to internal service | Critical |
| Document content injection | Malicious ops sent via WebSocket | High |
| Denial of service on collaboration layer | Op flooding per document | High |
| Data exfiltration via export | Mass export of shared documents | High |
| Session hijacking | JWT theft (XSS, MITM) | Critical |
| Insider threat (admin access to all docs) | Database admin, storage admin | Medium |
| GDPR violation | User data not purged after deletion request | High |
| Comment injection (XSS) | Malicious HTML in comment body | Medium |

---

## Authentication

### Mechanism: OAuth2 / OIDC with JWT

**Flow:**
1. User authenticates via Google/GitHub/SAML (SSO) through the auth provider
2. Auth Service (or third-party IdP like Auth0/Okta) issues a JWT access token and refresh token
3. Access token: short-lived (15 minutes); signed with RS256 (asymmetric)
4. Refresh token: long-lived (30 days); stored in HttpOnly, Secure, SameSite=Strict cookie
5. Services validate JWT locally using JWKS endpoint — no roundtrip to Auth Service per request

**JWT Claims:**
```json
{
  "sub": "usr_alice",
  "email": "alice@example.com",
  "workspace_id": "ws_abc123",
  "roles": ["workspace_member"],
  "iat": 1716038400,
  "exp": 1716039300,
  "jti": "jwt_unique_id_for_revocation"
}
```

**Token Revocation:** Standard JWTs cannot be revoked before expiry. Solution: maintain a Redis revocation list (`revoked_tokens:{jti}` SET with TTL = token expiry). On logout or suspicious activity, add `jti` to the revocation list. All services check this list on every request.

**WebSocket Authentication:**
- JWT passed as query parameter during WebSocket handshake (WSS): `wss://...?token=...` — acceptable because it's over TLS; token not in HTTP headers (WebSocket upgrade doesn't easily support custom headers from browsers)
- Alternatively: initial HTTP request with token, which issues a short-lived WebSocket session token

---

## Authorization: RBAC + Document-Level ACL

### Role Hierarchy

| Role | Scope | Permissions |
|---|---|---|
| Workspace Admin | Workspace | All document operations within workspace; user management |
| Workspace Member | Workspace | Create documents; access granted documents |
| Document Owner | Document | Full control: edit, share, delete, transfer ownership |
| Editor | Document | Edit content, add comments, view history |
| Commenter | Document | Add/resolve comments; no content edits |
| Viewer | Document | Read-only access; no commenting |
| Link Viewer | Document (via link) | Read-only; no comments |

### Effective Permission Resolution

Permission is resolved in priority order:
1. Workspace Admin → always `owner` level on all workspace documents
2. Explicit user grant on document
3. Group membership grant on document
4. Workspace-level sharing policy (e.g., "all workspace members can view")
5. Link token (weakest; grant expires)

The Permission Service evaluates this chain and returns the highest effective access level.

### API Gateway Enforcement
- Every REST request: API Gateway validates JWT; extracts `userId`; permission check is delegated to Document/Permission Service
- Every WebSocket op: Collaboration Service re-checks permission on session establishment and on any permission-sensitive action (e.g., op by a revoked user)

### Service-to-Service Authorization (Zero Trust)
- Internal services communicate via mutual TLS (mTLS)
- Each service has its own certificate (issued by internal CA, rotated every 90 days)
- Service mesh (Istio or Linkerd) enforces mTLS automatically
- Service identity is used to restrict which services can call which APIs (e.g., only Document Service can call Snapshot Service)

---

## Encryption

### In Transit
- All external traffic: TLS 1.3 mandatory; TLS 1.2 as minimum fallback
- WebSocket connections: WSS (TLS)
- Internal service-to-service: mTLS via service mesh
- Kafka: TLS between producers/consumers and brokers; SASL/SCRAM authentication

### At Rest
- PostgreSQL: Transparent Data Encryption (TDE) at the storage volume level (AWS EBS encryption / GCP disk encryption)
- S3 snapshots: Server-Side Encryption (SSE-S3 or SSE-KMS); KMS key per workspace for customer-managed keys (enterprise feature)
- Redis: Encrypted disk persistence if using Redis Enterprise; ephemeral data (presence) does not require encryption at rest
- Kafka log: Disk encryption at the broker level

### Key Management
- AWS KMS / HashiCorp Vault for application-level secrets
- No hardcoded credentials anywhere; all secrets via Vault agent injection in Kubernetes pods
- Database passwords: Vault dynamic secrets (rotated every 24 hours)
- JWT signing keys: RSA-4096; rotated every 90 days; public key published via JWKS endpoint

---

## API Security

### Rate Limiting
Enforced at API Gateway using Redis token buckets:
- Per-user: 1,000 REST requests/minute; 100 WS ops/second
- Per-IP (unauthenticated): 60 requests/minute
- Per-document (op stream): 10,000 ops/second (prevents a single document from overwhelming the collaboration layer)

### Input Validation
- All REST request bodies validated via JSON Schema before entering service layer
- WebSocket op payloads validated: position must be within document bounds; content length limits (max 10 KB per op insert)
- Operations that would produce a document exceeding the maximum size (50 MB) are rejected at the Collaboration Service

### XSS Prevention
- Comment body stored as markdown; rendered via a sanitized markdown parser (client-side, not server-side HTML rendering)
- No raw HTML stored in document content; all formatting via rich-text op attributes (bold, italic, etc.)
- Content Security Policy (CSP) headers on the web app: `default-src 'self'; script-src 'self'; object-src 'none'`

### CSRF Protection
- REST API: CORS headers restrict cross-origin requests to known origins
- `SameSite=Strict` on session cookies prevents CSRF for cookie-based auth
- JWT in Authorization header (not cookie) for API calls: immune to CSRF

### SQL Injection Prevention
- All database access via Spring Data JPA with parameterized queries
- No string-concatenated SQL anywhere; code review rule enforced via static analysis (SpotBugs + custom rule)

---

## Document Permission Security

### Share Link Security
- Link tokens are cryptographically random (256 bits; base62-encoded)
- Link access is logged: each view recorded with IP, timestamp, user agent
- Links can be revoked instantly; revocation reflected within 60 seconds (permission cache TTL)
- Optional password: PBKDF2-hashed; required before serving document content
- Optional expiry: link access denied after expiry without additional action

### View-Only Enforcement
- Viewer-level clients receive document content but WebSocket op submissions are rejected at Collaboration Service (not just UI-enforced)
- This prevents a malicious client from bypassing UI to submit ops directly
- "Print to PDF" in the UI generates a server-side PDF (controlled environment); client cannot directly access raw document content for bulk export

---

## Audit Logging

All security-relevant events are written to an immutable audit log in a separate PostgreSQL database (append-only, no UPDATE/DELETE permissions for application user):

| Event | Logged Fields |
|---|---|
| Document access | userId, docId, accessLevel, IP, timestamp |
| Permission change | actorId, targetPrincipal, docId, oldLevel, newLevel, timestamp |
| Document delete | actorId, docId, timestamp |
| Failed permission check | userId, docId, attemptedAction, timestamp |
| Token revocation | userId, jti, reason, timestamp |
| Export request | userId, docId, format, timestamp |
| GDPR deletion request | userId, requestId, timestamp |

Audit logs are shipped to a SIEM (Security Information and Event Management) system for anomaly detection. Alerts trigger on: bulk document access from a single account (> 100 documents in 1 minute), permission grants from a non-owner account, mass exports.

---

## GDPR Compliance

| Requirement | Implementation |
|---|---|
| Right to erasure | Background job replaces author_id in op_log with tombstone UUID; nulls op content for insert ops by that user |
| Data portability | Export API generates GDPR data package: all documents owned, all ops authored |
| Data minimization | Only necessary user data stored; analytics data anonymized |
| Data residency | EU workspace documents stored on EU PostgreSQL cluster and EU S3 bucket; enforced by workspace creation policy |

---

## Interview Discussion Points
- Why are JWTs validated locally (using JWKS) rather than via a roundtrip to the Auth Service on every request? What is the tradeoff?
- How does the 15-minute JWT expiry interact with long-lived WebSocket connections — does a user get disconnected every 15 minutes?
- If a Workspace Admin revokes a user's access while they are actively editing, how quickly is the session terminated? What is the mechanism?
- Why is view-only enforcement done server-side (Collaboration Service rejects ops) rather than just at the UI level?
- What is the risk of storing JWT in localStorage vs HttpOnly cookie, and which approach do you recommend?
- How would you implement document watermarking to detect leaks from view-only access?
