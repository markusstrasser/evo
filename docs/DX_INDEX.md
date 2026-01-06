# Evo DX Index

Single landing page for humans and AI agents. Start here.

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
| `README.md` | Project quick start + constraints |
| `VISION.md` | Product north star |
| `CLAUDE.md` | AI agent guidance |
| `logseq_behaviors.md` | Behavior triads: keymap → intent → scenario |

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
| **[RENDERING_AND_DISPATCH.md](RENDERING_AND_DISPATCH.md)** | Replicant + Nexus |
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
```

