# SRS Architecture Proposals Comparison

## Summary

Three LLM providers (Gemini 2.5 Pro, GPT-5 Codex, Grok 4) generated architectural proposals for extending the three-op kernel with an Anki-like SRS system.

**Directory**: `research/results/srs-architecture-2025-10-05-19-50/`
**Files**: `gemini-new.md` (10.4k), `codex-clean.md` (12.4k), `grok-final.md` (12.9k)

---

## Key Architectural Decisions Comparison

### Node Types

| Provider | Deck | Card | Review | Additional |
|----------|------|------|--------|------------|
| **Gemini** | `:deck` (directory) | `:card` (file) | `:review` (event) | - |
| **Codex** | `:deck` | `:card` + `:card-content` | `:review` | `:media` (for image-occlusion) |
| **Grok** | `:deck` | `:card` | `:review` | `:occlusion` (child nodes for masks) |

**Winner: Codex** - Separating `:card-content` as a child node is clever; it allows markdown body edits to flow through `update-node` on a specific node rather than mixing structure and content.

### Append-Only Log Format

All three use EDN with similar structure:
- `{:timestamp, :intent, :compiled-ops}`

**Best Details: Codex** - Includes `:kernel/after-hash` for integrity checks and `:log/redo-of` for redo tracking.

### 4 SRS Operations (Malli Schemas)

All define: `:create-card`, `:update-card`, `:review-card`, `:schedule-card`

**Most Complete: Grok** - Provides full Malli schemas with proper `:map` structure and `:optional` keys. Gemini uses simplified pseudo-schemas. Codex has detailed schemas matching kernel patterns.

**Winner: Tie (Grok/Codex)** - Both have production-ready schemas.

### Plugin System

| Provider | Extension Point | Dispatch Key |
|----------|----------------|--------------|
| **Gemini** | `compile-intent` multimethod | `(first intent)` |
| **Codex** | `compile-intent` multimethod + registry via `srs.plugin/register-card-type` | `:type` |
| **Grok** | `compile-srs-intent` multimethod | `:op` or `:card-type` |

**Winner: Codex** - Most complete plugin surface with registry pattern and explicit extension points for schema, compiler, and renderer.

### Markdown Reconciliation

**Gemini**: File watcher → parse → emit intents. Conflicts resolved by log precedence.

**Codex**: File watcher → diff against derived index → emit intents. Writes back to markdown after log append succeeds. Conflicts use `:srs/reload-from-markdown` intent.

**Grok**: Markdown = source of truth for content; log = source of truth for events. Replay log onto markdown-loaded state.

**Winner: Codex** - Most detailed reconciliation strategy with explicit conflict resolution via specialized intent.

### Image-Occlusion Cards

**Gemini**: Props store `{:image-url, :occlusions [{:id :shape}]}`. Frontend renders SVG overlays.

**Codex**: Separate `:media` child nodes with YAML frontmatter referencing overlays. Derived index `:media/by-card`.

**Grok**: `:occlusion` child nodes with `:props {:svg "<rect/>" :image-url ...}`. Multimethods for rendering.

**Winner: Codex** - Treats overlays as structured data (child nodes) rather than nested props, allowing precise diffs and plugin-based rendering.

### Undo/Redo

**Gemini**: Invert logged ops via `invert-intent` multimethod. Find previous state from log.

**Codex**: Log cursor with inverse ops (`:create-node` → move to `:trash`). Redo appends new entry with `:log/redo-of`.

**Grok**: Replay log inversely. Invert `:compiled-ops` (e.g., `:update-node` restores old props). Logs truncated on undo.

**Winner: Codex** - Most practical (reuses `:trash` for soft deletes, appends redo entries for auditability).

### Derived Indexes

All propose: `:due-cards`, `:review-history-by-card`, `:scheduling-metadata`

**Best Details: Codex** - Also includes `:media/by-card` for image-occlusion, shows how to register via `core.derive/register!`.

---

## Code Quality

### Concrete Examples

**Gemini**: ✓ Full intent compilation examples  
**Codex**: ✓✓ Complete code with registry, file watchers, intent compilation  
**Grok**: ✓ Detailed examples with mock scheduler

**Winner: Codex** - Most comprehensive code samples.

### Separation of Concerns

All three maintain clean separation:
- Kernel (canonical state, ops)
- SRS layer (intents, scheduling)
- Persistence (append-only log)

**Winner: Tie** - All follow the pattern correctly.

---

## Best Ideas by Provider

### Gemini 🥉
- ✅ Clearest high-level architecture diagram
- ✅ Good explanation of markdown as source of truth for *content*
- ✅ Simple, readable schemas
- ❌ Less detail on implementation edge cases

### Codex 🥇
- ✅ **`:card-content` separation** - Brilliant structural decision
- ✅ **Most complete plugin system** with `srs.plugin/register-card-type`
- ✅ **Checkpointing via `:kernel/after-hash`**
- ✅ **Explicit conflict resolution** (`:srs/reload-from-markdown` intent)
- ✅ **Media as child nodes** for image-occlusion
- ✅ **Derived index registration pattern**
- ✅ Complete workflow integration (import/export scripts, test targets)

### Grok 🥈
- ✅ Most detailed Malli schemas (production-ready)
- ✅ Comprehensive answers to all 8 architectural questions
- ✅ Good use of `:occlusion` child nodes
- ✅ Clear explanation of undo/redo mechanics
- ❌ Slightly verbose (12.9k vs Codex's 12.4k)

---

## Winner: **Codex (GPT-5 with reasoning: high)**

**Reasons**:
1. **Structural innovation**: `:card-content` child nodes
2. **Plugin extensibility**: Most complete registry pattern
3. **Practical details**: Checkpointing, conflict resolution intents, media indexing
4. **Implementation-ready**: Includes workflow scripts, test targets, integration patterns

**Runner-up**: Grok (detailed schemas, complete coverage)  
**Third**: Gemini (clear explanations, good diagrams, but less implementation depth)

---

## Recommendations

Use **Codex's proposal** as the architectural foundation:

1. **Adopt `:card-content` pattern** - Separate structure from content
2. **Implement plugin registry** from `srs.plugin/register-card-type`
3. **Use `:kernel/after-hash`** for log integrity
4. **Add `:srs/reload-from-markdown` intent** for conflict resolution
5. **Model image-occlusion as `:media` child nodes** with derived index

**Borrow from Grok**:
- Production-ready Malli schemas (more complete than Codex's)

**Borrow from Gemini**:
- High-level architecture diagram for documentation
- Clear explanation of markdown reconciliation philosophy

---

## Files

- **Proposals**: `gemini-new.md`, `codex-clean.md`, `grok-final.md`
- **Evaluator Items**: `eval-items.edn`
- **Config**: `eval-config.edn`
- **This Comparison**: `COMPARISON.md`
