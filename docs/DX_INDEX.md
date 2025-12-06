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

---

## Machine-Readable Truth

| File | Purpose |
|------|---------|
| **`resources/specs.edn`** | Functional Requirements registry (44 FRs with priority, status, tags) |
| **`resources/failure_modes.edn`** | Known bugs/anti-patterns with symptoms, fixes, tests |
| **`docs/logseq_behaviors.md`** | Behavior triads: keymap → intent → scenario |

Tests cite FR IDs via `^{:fr/ids #{:fr.nav/...}}` metadata. CI enforces coverage with `bb lint:fr-tests --strict`.

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

---

```edn
{:orientation ["README.md" "VISION.md" "CLAUDE.md"]
 :registries ["resources/specs.edn"
              "resources/failure_modes.edn"
              "docs/logseq_behaviors.md"]
 :specs ["docs/STRUCTURAL_EDITING.md"
         "docs/LOGSEQ_UI_FEATURES.md"
         "docs/LOGSEQ_SPEC.md"]
 :implementation ["docs/RENDERING_AND_DISPATCH.md"
                  "docs/LOGSEQ_PARITY_EVO.md"
                  "docs/CODING_GOTCHAS.md"]
 :testing ["docs/TESTING.md"]}
```
