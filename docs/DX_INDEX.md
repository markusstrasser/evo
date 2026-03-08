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
| [VISION.md](../VISION.md) | Product north star |
| [AGENTS.md](../AGENTS.md) | Canonical agent guidance |
| [logseq_behaviors.md](logseq_behaviors.md) | Behavior triads: keymap → intent → scenario |

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
| **[ARCHITECTURE_UNIFICATION_PLAN.md](ARCHITECTURE_UNIFICATION_PLAN.md)** | Canonical execution plan for runtime, keyboard, session, and docs unification |

---

## Testing

| Doc | Scope |
|-----|-------|
| **[TESTING.md](TESTING.md)** | Commands, helpers, patterns |

```bash
bb test    # Unit tests
bb e2e     # E2E tests
bb check   # Quality gate
```
