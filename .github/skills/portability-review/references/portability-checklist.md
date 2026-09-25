# Portability checklist (canonical)

This is the **single source of truth** for portability rules in this repo.
The portability-review agent, its subagents, and the slim instructions
file at `.github/instructions/portability.instructions.md` all reference
this file rather than copying its content.

The SDK exposes **one** portable contract over Cosmos DB, DynamoDB, and
Spanner. Two invariants govern this repo:

1. **Cross-provider portability.** `CrudConformanceTests` is explicit:
   *"every behaviour they assert MUST hold identically across all
   supported providers… silently producing different results is never
   acceptable."*
2. **Documentation alignment.** Changelogs ship **inside the JAR**, and
   `docs/`, per-module `CHANGELOG.md`, and `specs/<NNN-feature>/`
   artifacts are part of the product surface. Code that ships without
   the matching doc update is incomplete.

Review with understanding, not with a mechanical checklist pass.
**Absence is often a stronger signal than presence** — a new public API
with no CHANGELOG entry, a new behaviour with no conformance assertion,
a new error path mapped in two of three providers — those are the
findings that matter.

## Provider symmetry (🔴 if violated)

- Public surface change in `multiclouddb-api/` (new method, new field,
  new error category, new capability, new behaviour on an existing
  method) → matching change in **all three** providers:
  `multiclouddb-provider-cosmos/`, `multiclouddb-provider-dynamo/`,
  `multiclouddb-provider-spanner/`.
- A provider that cannot implement the new behaviour MUST declare the
  gap via `CapabilitySet` and reject the operation with
  `MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY`. Silent divergence
  (different return value, different ordering, swallowed error) is
  never acceptable.
- New error paths must map to a portable `MulticloudDbErrorCategory` in
  every provider — never a provider-specific exception type leaking
  through the portable surface.
- New `Capability` constants are declared (supported or unsupported)
  in every provider's capability set.

## Conformance coverage (🔴 if portable behaviour has no assertion)

- New portable behaviour → assertion in an abstract base under
  `multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/`
  (existing base such as `CrudConformanceTests` or
  `SortKeyOrderingConformanceTest`, or a new
  `us<N>/<Feature>ConformanceTest`).
- Per-provider subclasses (`CosmosConformanceTest`,
  `DynamoConformanceTest`, `SpannerConformanceTest`, or the per-feature
  subclasses under `us<N>/`) wired up — an abstract base with no
  subclass is a no-op and a 🔴.
- Conformance assertions stay provider-agnostic. Branching on
  `client.providerId()` (or `instanceof`) inside an abstract base is a
  smell; gate via `CapabilitySet` + `Assumptions.assumeTrue(...)`,
  never an instance-of branch.
- New `Capability` constants appear in `CapabilitiesConformanceTest`
  (supported or unsupported with the right error category) for every
  provider.

## Spec conformance (🔴 if shipped behaviour contradicts spec)

For changes under `specs/<NNN-feature>/`:

- The implementation must faithfully realise the spec's behavioural
  requirements. Map spec statements → code paths; call out anything
  in the spec that's not implemented, and anything implemented that's
  not in the spec.
- If the design evolved during review (a common pattern in this repo —
  e.g., a parameter or mode removed late), `spec.md` / `plan.md` /
  `tasks.md` must reflect the **shipped** behaviour, not the original
  design.

## Cost-efficiency parity (🟡 by default; 🔴 when severely asymmetric)

A feature that achieves functional portability by burning
disproportionate cost on one provider (Cosmos RUs, DynamoDB RCU/WCU,
Spanner processing units) is a portability anti-pattern. Functional
`Equivalent` plus a 10× cost on one provider is a real bug — the
user's bill is part of the contract.

For each portable operation introduced or modified, identify the cost
driver on each provider and call out asymmetry:

- **Cosmos**: cross-partition query (no `partitionKey` set) used for
  what could be a point read; `SELECT *` when only a few fields are
  needed; `ORDER BY` on an unindexed field; N point reads where
  `readMany` / bulk would do; large page sizes that magnify RU charges
  per round trip.
- **DynamoDB**: `Scan` (with or without filter) used where `Query` (or
  a GSI `Query`) would do; N individual `GetItem` calls instead of
  `BatchGetItem` (up to 100 per batch); `FilterExpression` consuming
  RCUs for items examined and discarded; GSI queries that fall through
  to a base-table read because the projection is insufficient.
- **Spanner**: full table scan where a secondary-indexed read would
  suffice; DML for high-volume row writes that should use the
  mutations API (or vice versa); strong reads on read-mostly paths
  where bounded staleness would do.

Tie the finding to the cheap path the provider offers (e.g., *"this
routes through `executeScanWithFilter` on DynamoDB; use `Query` on the
partition key instead"*), not just *"this is expensive."*

When a provider genuinely cannot match the cost of the others, **prefer
declaring it `Capability-gated`** (`CapabilitySet` +
`UNSUPPORTED_CAPABILITY`) over implementing it at a steep cost penalty.
A documented gap is portable; a silent cost spike is not. Promote a
cost-efficiency finding from 🟡 to 🔴 when the cost asymmetry is
**severe** (an order of magnitude or worse) **and unbounded** (scales
with data size or request volume, not capped).

## Documentation alignment (🔴 if user-visible change ships without docs)

| Change kind | Doc(s) that must update in the same PR |
|---|---|
| New / changed public API in `multiclouddb-api` | `docs/guide.md` + `multiclouddb-api/CHANGELOG.md` (`[Unreleased]`) |
| New behaviour in a provider adapter | per-provider `CHANGELOG.md` (`[Unreleased]`) |
| New / changed config knob | `docs/configuration.md` |
| Any user-visible behaviour change | `docs/changelog.md` |
| New / changed `Capability` constant | `docs/compatibility.md` (per `.github/PULL_REQUEST_TEMPLATE.md` checklist) |
| Feature work under `specs/<NNN-feature>/` | `spec.md` / `plan.md` / `tasks.md` reflect *shipped* behaviour |
| New example or runtime entry point | `multiclouddb-e2e/README.md` + module README |

CHANGELOG entries follow Keep a Changelog format and land under
`[Unreleased]`. A PR that bumps a release-version header is out of
scope here — that's `release.agent.md`.

## E2E coverage (🟡)

New cross-provider behaviour exercised by `multiclouddb-e2e/`
(currently driven by `Main.java`, which runs the same CRUD + query
calls against whichever provider the active `*.properties` selects).
New scenarios should either extend `Main.java` or add an analogous
entry point under `multiclouddb-e2e/src/main/java/.../e2e/`, or
document why they aren't cheap to exercise end-to-end.

## Severity self-challenge (run before finalising any 🔴)

1. **Construct a concrete failure scenario** — Describe the exact
   sequence of events that produces the bad outcome. If you can't
   construct one that doesn't self-correct, downgrade to 🟡.
2. **Trace the full lifecycle** — Don't reason about one code path in
   isolation. Follow the data through creation, usage, cleanup, and
   error recovery. Does the issue persist or self-correct?
3. **Check for compensating mechanisms** — Is there a `CapabilitySet`
   gate, a `MulticloudDbErrorCategory` mapping, an assertion in a
   conformance base, or a doc that already covers the concern? If so,
   the impact is bounded.
4. **Distinguish correctness from efficiency** — Wrong results, data
   loss, or silent provider divergence is 🔴. A suboptimal but
   correct implementation is 🟡 at most — unless cost asymmetry is
   severe and unbounded (see Cost-efficiency parity), in which case
   it escalates to 🔴.

**Mandatory escalation:** any cross-provider divergence not gated by
`CapabilitySet` stays 🔴, regardless of perceived severity.

## Review and publication boundaries

This section is the canonical publication policy. The orchestrator
references this state machine; agent files must not restate or override
its invariants.

### Review and edit gate

- Comment on the gap. Cite the **exact file path** and (where
  applicable) the section/line where the missing change belongs.
- Quote the specific code or doc being discussed, explain **why it
  matters** (tie the consequence back to the portability or
  doc-alignment invariant), and offer a concrete suggestion when
  possible (e.g., the exact CHANGELOG line text).
- The review phase is read-only and ends at a hard gate. All reviewer
  subagents remain read-only; only the orchestrator may apply fixes
  after the user explicitly approves them.
- Applying fixes does **not** authorize publication. The orchestrator
  leaves changes unstaged, uncommitted, and unpushed by default.
- The orchestrator may commit only after the user explicitly requests
  it or approves an immediately preceding commit proposal. Apply
  approval does not authorize a commit.

### Push-mode state machine

- Push mode starts as **`confirm_each`** for every portability-review
  workflow. In this mode the orchestrator shows the exact remote,
  destination branch, PR, and commit range, then obtains a fresh,
  single-use confirmation before each `git push`.
- "Enable auto-push for this PR" activates
  **`auto_push_current_pr`**. "Disable auto-push", "turn auto-push
  off", or "ask before pushes" restores `confirm_each`. The
  orchestrator echoes the resulting mode and scope after every switch.
- The user may explicitly enable **`auto_push_current_pr`** for one
  exact PR during the current review workflow. The orchestrator records
  and echoes the repository, PR number, head repository/owner, head
  branch, and destination remote. While those values still match, it
  announces each push but doesn't pause for confirmation.
- Auto-push remains valid only while the destination exactly matches
  the recorded PR head, the PR is open with an unchanged head identity,
  required validation passed, unrelated changes remain excluded, and
  the outgoing range contains only agent-created commits for findings
  approved in the current review workflow.
- Auto-push is session-scoped. It never authorizes applying fixes,
  committing, PR actions, force-pushing, hook bypasses, or history
  rewrites.
- Auto-push resets to `confirm_each` when disabled, when review scope or
  PR-head identity changes, when the PR closes or merges, when validation
  fails, when unexpected commits appear, or when the workflow/session
  ends. It never carries into a later review workflow.

### Branch and history safety

- The repository **default branch** means GitHub's canonical integration
  branch (`defaultBranchRef`, usually `main`). The orchestrator must
  never push directly to that branch.
- Existing PR head branches are valid and preferred push targets when
  updating those PRs. The orchestrator resolves the head repository,
  owner, remote, and branch and must not push to the PR base by mistake.
- If work starts on the default branch and isn't updating an existing PR
  head, create a descriptively named feature branch before staging.
- An authorized commit or push includes only paths changed for approved
  findings, preserves unrelated work, and avoids force-pushing,
  amending, bypassing hooks, or rewriting history.

### PR actions

- The orchestrator may open or update PRs and post review comments when
  the user explicitly authorizes the specific target and content. Each
  authorization is action-specific rather than blanket permission, and
  every posted review comment includes the required AI disclosure.
- Style, formatting, and naming nits are out of scope unless they
  obscure the portability or doc-alignment story.
