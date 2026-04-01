# Evo DX Index

Single landing page for humans and AI agents. Start here.

Repo truth lives in this index, [README.md](../README.md), and
[AGENTS.md](../AGENTS.md). Generated `dev/overviews/AUTO-*.md` files are
orientation artifacts only and are not authoritative.

---

## Quick Start

```bash
npm install
npm start        # Clean + watch CLJS + watch CSS
# visit http://localhost:8080/blocks.html once [CLJS] Build completed
```

---

## Orientation

| Doc | Purpose |
|-----|---------|
| [README.md](../README.md) | Project quick start + constraints |
| [GOALS.md](GOALS.md) | Project mission, strategy, success metrics |
| [VISION.md](../VISION.md) | Product north star |
| [AGENTS.md](../AGENTS.md) | Canonical agent guidance |
| [LOGSEQ_BEHAVIOR_TRIADS.md](LOGSEQ_BEHAVIOR_TRIADS.md) | Behavior triads: keymap → intent → scenario |

---

## Specs (What to Build)

| Doc | Scope |
|-----|-------|
| **[STRUCTURAL_EDITING.md](STRUCTURAL_EDITING.md)** | Core editor: state machine, navigation, selection, editing, structure ops |
| **[LOGSEQ_UI_FEATURES.md](LOGSEQ_UI_FEATURES.md)** | Logseq-specific: slash commands, sidebar, clipboard |
| **[LOGSEQ_SPEC.md](LOGSEQ_SPEC.md)** | Full Logseq reference with source links |

---

## Implementation

| Doc | Scope |
|-----|-------|
| **[RENDERING_AND_DISPATCH.md](RENDERING_AND_DISPATCH.md)** | Replicant + runtime adapter boundaries |
| **[KEYBOARD_OWNERSHIP.md](KEYBOARD_OWNERSHIP.md)** | Canonical keyboard ownership matrix |
| **[DEPENDENCY_REVIEW.md](DEPENDENCY_REVIEW.md)** | Upstream package assessment: keep, narrow, bump, or remove |
| **[LOGSEQ_PARITY_EVO.md](LOGSEQ_PARITY_EVO.md)** | Gaps + guardrails |
| **[CODING_GOTCHAS.md](CODING_GOTCHAS.md)** | Common pitfalls |

---

## Testing

| Doc | Scope |
|-----|-------|
| **[TESTING.md](TESTING.md)** | Commands, helpers, patterns |

```bash
bb test    # Unit tests
bb e2e     # E2E tests
bb check   # Quality gate
bb check:kernel  # Kernel extraction harness
```

---

## Data

| File | Scope |
|------|-------|
| **[specs.edn](../resources/specs.edn)** | FR registry (44 functional requirements with scenarios) |
| **[failure_modes.edn](../resources/failure_modes.edn)** | Known bugs/anti-patterns with symptoms and fixes |

---

## Task Routing

What to read before acting on a task.

| Task | Read first | Then |
|------|-----------|------|
| Implement navigation/editing intent | STRUCTURAL_EDITING.md | LOGSEQ_BEHAVIOR_TRIADS.md → RENDERING_AND_DISPATCH.md |
| Debug cursor/focus bug | CODING_GOTCHAS.md | failure_modes.edn → cljs-ui-debugging skill |
| Add keyboard shortcut | KEYBOARD_OWNERSHIP.md | keymap/bindings_data.cljc |
| Understand transaction pipeline | CLAUDE.md §Architecture | kernel/transaction.cljc |
| Write tests | TESTING.md | specs.edn (FR registry) |
| Check Logseq parity | LOGSEQ_PARITY_EVO.md | LOGSEQ_SPEC.md |
