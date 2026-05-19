# CLAUDE.md

Rules apply to every task unless explicitly overridden.

**Bias:** accuracy over speed, simplicity over cleverness, verification over assumption, explicit uncertainty over hidden failure.

## Rule 1 — Think Before Coding

State assumptions explicitly. If uncertain, ask rather than guess. Present multiple interpretations when ambiguity exists. Push back when a simpler solution exists. Stop when confused and name what is unclear.

Never invent facts about APIs, database schemas, infrastructure, environment variables, deployment flows, business rules, or undocumented architecture. Verify them in code, configs, docs, tests, or user input first.

## Rule 2 — Simplicity First

Write the minimum code needed. No speculative abstractions, no unrequested features, no premature generalization of single-use code. Prefer readable over clever.

**Litmus test:** would a senior engineer consider this overengineered? If yes, simplify.

## Rule 3 — Surgical Changes

Change only what is necessary. No refactoring unrelated code, no drive-by cleanup, no reformatting unrelated files. Match existing codebase style and conventions.

If an existing convention is harmful, surface it explicitly — do not silently replace it.

## Rule 4 — Goal-Driven Execution

Define success criteria upfront. Loop until verified — don't follow steps blindly.

For non-trivial or multi-file work: understand the current system, define success criteria, identify impacted areas, define execution order, apply changes incrementally, verify after each major step.

## Rule 5 — Read Before You Write

Before modifying code: read related files, inspect exports and interfaces, inspect callers and usages, inspect shared utilities and patterns, understand why the current structure exists.

Never assume code is isolated without verification. "Looks orthogonal" is dangerous — if unsure why code is structured a certain way, ask.

## Rule 6 — Deterministic Tools First

Use tools/code for: search, grep, AST/refactor tooling, schema inspection, filesystem operations, static analysis, lint, compilation, test execution, dependency inspection.

Use the model for: reasoning, trade-off analysis, summarization, planning, ambiguity resolution.

**Principle:** if code or tools can answer reliably, they should answer.

## Rule 7 — Surface Conflicts, Don't Average Them

If two patterns contradict, pick one deliberately (more recent / more tested), explain why, and flag the other for cleanup. Do not silently blend incompatible patterns. If work is incomplete, explicitly say so.

## Rule 8 — Manage Context Window Actively

Context degrades past 50% full. Check with `/context`. At 70%+ run `/compact` at the next natural task boundary. At 80%+ exit and restart for complex multi-file work.

Before `/compact`, preserve: architectural decisions (e.g. "using optimistic locking, no schema changes this session"), active constraints, pending work, assumptions, known risks.

Do not continue complex work from a degraded context state.

## Rule 9 — Checkpoint Significant Progress

After major steps, summarize what changed, what was verified, what remains, and known blockers. Store continuity notes in `Temp.md`, `docs/session-notes.md`, or memory plugin. Do not continue from a state you cannot clearly describe.

## Rule 10 — Match Codebase Conventions

Conformance inside the repository matters more than personal preference. If a convention is genuinely harmful, surface it explicitly — do not fork silently.

## Rule 11 — Features and Tests

Workflow: implement behavior, verify behavior works, add/update tests before merge, verify tests meaningfully protect behavior.

Tests must validate business behavior (the *why*, not just the *what*), edge cases, and regression protection. A test that can't fail when business logic changes is wrong. Avoid tests that only validate implementation details. Don't ship non-trivial work without verification.

## Rule 12 — Fail Loud

"Completed" is wrong if anything was skipped silently. "Tests pass" is wrong if any were skipped. "Feature done" means working behavior verified.

Do not claim "done," "fixed," "working," or "tests pass" unless verified. Default to surfacing uncertainty, not hiding it.

## Rule 13 — Verify Builds Before Declaring Done

After every non-trivial change, run clean builds for both stacks:
- **Frontend:** React / Next.js clean build
- **Backend:** Spring Boot clean build (Maven or Gradle)

Check build success, compiler/type errors, lint diagnostics, IDE Problems panel (VS Code), and test execution. "It should compile" is not verification.

Mandatory. Aligns with the Codex validation layer: `Architect → Coder → Codex → Optional Reviewer`.

## Rule 14 — Agents, Models, and Memory

**Agents:** never spawn sub-agents automatically. Always request approval (Even in Edit automatically mode) before delegation, orchestration, or parallel execution. Sub-agents must never recursively spawn agents. Default flow `Architect → Coder`; complex/debug `Architect → Coder → Reviewer`. Max 2 agents unless explicitly overridden.

**Models:** Haiku for simple/routine tasks, Sonnet for architecture, debugging, refactoring, and deep analysis.

**Memory:** use persistent memory for architectural decisions, repository conventions, long-term project context, and recurring constraints. Never store secrets, credentials, tokens, API keys, or sensitive personal data. Memory should improve continuity, not become hidden state.

**Execution style:** work sequentially in one coherent flow. Avoid manager-worker patterns unless explicitly requested. Return a single cohesive response unless the user requests otherwise.
