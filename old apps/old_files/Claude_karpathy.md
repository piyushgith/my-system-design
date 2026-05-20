# CLAUDE.md — 12-rule template

These rules apply to every task in this project unless explicitly overridden.
Bias: caution over speed on non-trivial work. Use judgment on trivial tasks.

## Rule 1 — Think Before Coding
State assumptions explicitly. If uncertain, ask rather than guess.
Present multiple interpretations when ambiguity exists.
Push back when a simpler approach exists.
Stop when confused. Name what's unclear.

## Rule 2 — Simplicity First
Minimum code that solves the problem. Nothing speculative.
No features beyond what was asked. No abstractions for single-use code.
Test: would a senior engineer say this is overcomplicated? If yes, simplify.

## Rule 3 — Surgical Changes
Touch only what you must. Clean up only your own mess.
Don't "improve" adjacent code, comments, or formatting.
Don't refactor what isn't broken. Match existing style.

## Rule 4 — Goal-Driven Execution
Define success criteria. Loop until verified.
Don't follow steps blindly. Define success and iterate.
Strong success criteria let you loop independently.

## Rule 5 — Use the Model Only for Judgment Calls
Use for: classification, drafting, summarization, extraction.
Do NOT use for: routing, retries, deterministic transforms.
If code can answer, code answers.

## Rule 6 — Manage Context Window Actively
Context degrades past 50% full. Check with /context.
At 70%+: run /compact at the next natural task boundary.
Before /compact: explicitly state decisions to preserve
(e.g. "we're using optimistic locking, no schema changes this session").
At 80%+: exit and restart for complex multi-file work.

## Rule 7 — Surface Conflicts, Don't Average Them
If two patterns contradict, pick one (more recent / more tested).
Explain why. Flag the other for cleanup.
Don't blend conflicting patterns.

## Rule 8 — Read Before You Write
Before adding code, read exports, immediate callers, shared utilities.
"Looks orthogonal" is dangerous. If unsure why code is structured a way, ask.

## Rule 9 — Features First, Tests Follow
Get the feature working first. Tests come after behavior is confirmed.
Working order: feature works → next feature → tests added before merge.
Tests must encode WHY the behavior matters, not just WHAT it does.
A test that can't fail when business logic changes is wrong.
Don't block feature progress on test coverage — but don't ship without it.

## Rule 10 — Checkpoint After Every Significant Step
Summarize what was done, what's verified, what's left.
Store checkpoints in CLAUDE.md or docs/session-notes.md for cross-session continuity.
Don't continue from a state you can't describe back.
If you lose track, stop and restate.

## Rule 11 — Match the Codebase's Conventions, Even If You Disagree
Conformance > taste inside the codebase.
If you genuinely think a convention is harmful, surface it. Don't fork silently.

## Rule 12 — Fail Loud
"Completed" is wrong if anything was skipped silently.
"Tests pass" is wrong if any were skipped.
"Feature done" means working behavior verified — tests can follow in the next step.
Default to surfacing uncertainty, not hiding it.

## Rule 13 — Verify Builds Before Declaring Done
After non-trivial changes: confirm build succeeds.
In VS Code: check Problems panel, not just Claude's output.
Run clean builds for both React/Next.js and Spring Boot after every change.
"It should compile" is not verification.

## Rule 14 — Agent Usage, Models, and Memory

- Use the `/memory` plugin for persistent project context, architectural decisions, conventions, and long-term task state.
- Never store secrets, credentials, tokens, API keys, or sensitive personal data in memory.
- Never spawn agents automatically. Ask for user approval before using sub-agents, delegation, orchestration, or parallel execution.
- Default model selection:
  - Haiku → simple, routine, low-complexity tasks
  - Sonnet → complex reasoning, debugging, architecture, refactoring, and deep analysis
- Sub-agents must never spawn additional agents or recursive workflows.
- Prefer direct, single-flow execution over manager-worker systems, task trees, or autonomous coordination.
- For large tasks, work sequentially and transparently in one continuous context.
- Return a single cohesive response unless the user explicitly requests otherwise.