# Research Report: Anki User Stories and Workflows

## Executive Summary
Spaced-repetition users need flexible scheduling controls, seamless integration with note-taking tools, and balanced new/review card workflows. Power users prioritize customizable intervals, external tool integration via APIs, and cloze-based learning patterns. The key insight: vanilla Anki excels at core SRS but frustrates users with inflexible scheduling defaults and poor integration with modern knowledge management workflows.

---

## Focus Area 1: Real Anki Workflows

### Library Documentation (Context7)
Anki's official manual reveals that power users extensively customize:
- **Card templates**: Type-in answers, cloze deletions, conditional generation
- **Custom scheduling**: JavaScript hooks to modify ease factors and intervals
- **LaTeX integration**: Mathematical notation with custom preambles
- **Media handling**: Images, audio, video with field-based references
- **Filtered decks**: Query-based custom decks for overdue cards (\`is:due prop:due<=-7\`)

### Real-World Usage (Exa)
Users configure:
- **Audio autoplay settings**: \`config.audio = false\` for manual control
- **Hint display folding**: \`config.fold = true\` to hide hints initially
- **Custom scheduling logic**: Modifying \`states.hard.normal.learning.scheduledSecs\` for precise control
- **Language learning fields**: Simplified/Traditional/Pinyin/Audio/Meaning structures
- **LaTeX commands**: Custom PNG/SVG generation pipelines

### Reference Implementation: Logseq
Logseq's \`srs.cljs\` and \`fsrs.cljs\` show production patterns:

**SM-5 Algorithm Implementation** (srs.cljs):
\`\`\`clojure
;; Cards identified by #card tag
(def card-hash-tag "card")

;; Properties tracked per card
:card-last-interval        ;; Days between reviews
:card-repeats              ;; Number of times reviewed
:card-last-reviewed        ;; Timestamp
:card-next-schedule        ;; When due next
:card-ease-factor          ;; Learning efficiency (default 2.5)
:card-last-score           ;; Quality response (0-5)

;; Cloze syntax: {{cloze content}}
;; Query syntax: {{cards query-string}}
\`\`\`

**FSRS Algorithm** (fsrs.cljs - modern approach):
\`\`\`clojure
;; Uses open-spaced-repetition/cljc-fsrs library
;; Stores state as :logseq.property.fsrs/state
;; Tracks :due as separate timestamp
;; Ratings: :again :hard :good :easy (4-button system)
\`\`\`

**Key Integration Patterns**:
- Block-level granularity (not card-level)
- Properties embedded in markdown/org-mode files
- Query DSL for flexible card selection
- Phase-based review: init → show-answer/show-cloze → rating
- Breadcrumb navigation showing card context in knowledge graph

### Current Best Practices (2024-2025)

**Power User Consensus**:
1. **Set new cards to 9999** - Don't artificially limit learning rate
2. **Desktop over mobile** - Larger screens enable note-taking during review
3. **Interleaved practice** - Shuffle cards to strengthen retention across subjects
4. **Handwrite outside Anki** - Combat context-dependent memory issues
5. **FSRS over SM-2** - Modern algorithm personalizes schedules (ml-based optimization)

**Workflow Pattern**:
- Consistent daily practice (60-400 cards/day for experienced users)
- Create own cards (encoding benefit > using pre-made decks)
- One fact per card (complex ideas → mini-series)
- Review immediately after creation (strike while memory fresh)

### Tradeoffs
- **Pro**: Block-based cards integrate naturally with notes
- **Pro**: File-based storage enables git/sync without proprietary format
- **Pro**: Query DSL allows dynamic card selection
- **Con**: Property overhead in markdown can clutter visual appearance
- **Con**: SM-5/FSRS complexity requires understanding for customization

### Recommendation
**For your project**: Adopt Logseq's block-based pattern with file-embedded properties. Use FSRS algorithm (modern, proven in production). Support query-based filtered decks for flexible workflows. Store cards as markdown with front-matter properties for maximum portability.

---

## Focus Area 2: Common Pain Points

### User Complaints (8+ Year Anki User)

**1. Interval Management**
- **Pain**: No global timing standard across decks
- **Complaint**: "Want uniform time intervals for ALL decks"
- **Current workaround**: Manually adjust each deck individually

**2. New Card Balance**
- **Pain**: Old cards dominate after breaks
- **Complaint**: "Anki prioritizes old cards over new cards... more BALANCED approach needed"
- **Impact**: After hiatus, can't make progress on new material

**3. Maximum Interval Cap**
- **Pain**: Default 16+ year intervals unrealistic
- **Complaint**: "Default long-term interval is idiotic"
- **Desired**: 2-4 year maximum cap option

**4. UI/UX Clarity**
- **Pain**: Menu options lack descriptive tooltips
- **Example**: "Difference between deleting 'card' vs 'note' unclear"
- **Desired**: Question mark buttons or hover explanations

**5. Deck Feedback Loop**
- **Pain**: No system for improving purchased/shared decks
- **Desired**: "Easier way to communicate improvements for third-party card sets"

### Tradeoffs
- **Pro**: Identifying these pain points after 8 years shows sticky product
- **Con**: Long-standing issues indicate difficulty balancing simplicity vs power
- **Pro**: Users actively seeking solutions (not abandoning tool)

### Recommendation
**For your project**: 
1. **Global presets** - One place to set intervals/caps for all decks
2. **Balanced scheduler** - Configurable new/review ratio (e.g., "30% new cards daily")
3. **Interval caps** - Per-deck maximum (prevent decade-long intervals)
4. **Inline help** - Tooltips on every setting (show formula/impact)
5. **Deck improvement workflow** - Built-in feedback/suggestion system

---

## Focus Area 3: Advanced Features

### Power User Desires

**Custom Scheduling (JavaScript Hooks)**
Anki allows modifying scheduling state:
\`\`\`javascript
// Access states object
if (states.hard.normal?.learning) {
  states.hard.normal.learning.scheduledSecs = 123 * 60; // Force 123min interval
}

// Modify ease factor
if (states.good.normal?.review) {
  states.easy.normal.review.easeFactor = 
    states.good.normal.review.easeFactor + 0.2; // Boost ease
}
\`\`\`

**Advanced Queries**
- Overdue backlog: \`is:due prop:due<=-7\` (7+ days overdue)
- Recent due: \`is:due prop:due>-7\` (became due within week)
- Filtered decks with custom queries
- Integration with block references and tags

**FSRS Advantages** (Logseq's modern approach)
- 4-button rating system (Again/Hard/Good/Easy)
- Machine learning optimizes intervals based on user performance
- Per-card state tracking (new/learning/review/relearning)
- Statistics: true retention, passed/lapsed repeats, state distributions

### Current Best Practices (2024-2025)

**FSRS Adoption**:
- Anki 2.1.45+ includes FSRS as built-in option
- GitHub: open-spaced-repetition/fsrs4anki (9.4 trust score)
- Optimizer personalizes schedule based on review history

**Integration Patterns**:
- Note-taking apps (Obsidian, Logseq, Notion) add SRS features
- AnkiConnect API enables programmatic card creation
- MCP servers (Model Context Protocol) for AI assistant integration

### Recommendation
**For your project**:
1. **Adopt FSRS algorithm** - Proven modern approach with ML optimization
2. **Phase-based review** - init → reveal → rate (matches Logseq pattern)
3. **Keyboard shortcuts** - "1"/"2"/"3"/"4" for rating, "s" for show answer
4. **Due date preview** - Show next due time when hovering rating buttons
5. **Query DSL** - Allow filtering cards by tags/properties/due date
6. **JavaScript hooks** - Advanced users can customize scheduling logic

---

## Focus Area 4: Integration Patterns

### AnkiConnect API

**Architecture**:
- HTTP server on port 8765 (localhost by default)
- JSON-RPC style: \`{action, version, params, key?}\`
- Response: \`{result, error}\`

**Security**:
- \`apiKey\` authentication (optional)
- \`webBindAddress\`: "127.0.0.1" (local only) or "0.0.0.0" (network)
- CORS whitelist: \`webCorsOriginList\`

### Note-Taking Tool Integration

**Logseq Pattern**:
- Cards are blocks with \`#card\` tag
- Properties stored in block front-matter
- Query macro: \`{{cards query-string}}\`
- Breadcrumb navigation shows card in knowledge graph context
- Batch operations: select blocks → "Make flashcards"

**File Format**:
\`\`\`markdown
- Front side content #card
  - Child block (hidden initially)
  - Another child
  card-last-interval:: 4.5
  card-repeats:: 3
  card-ease-factor:: 2.6
  card-next-schedule:: "2025-10-15T10:30:00"
\`\`\`

### Recommendation
**For your project**:
1. **File-based storage** - Markdown with front-matter properties (portable, version-controllable)
2. **HTTP API** (optional) - Localhost-only by default, optional network access
3. **Block-level cards** - Cards are references to markdown blocks, not separate entities
4. **Tag-based selection** - \`#card\` tag marks blocks for review
5. **Query syntax** - Filter cards by tags/dates/properties
6. **Breadcrumb navigation** - Show card location in file hierarchy during review
7. **Batch operations** - Create multiple cards from selection

---

## Actionable User Stories

### Core Features (Must Have)

**US-1: Block-based card creation**
> As a knowledge worker, I want to create flashcards from my notes by tagging blocks, so that I remember what I read without maintaining separate card files.
> 
> **Acceptance**: Tag block with \`#card\` → appears in review queue → properties auto-managed

**US-2: FSRS scheduling**
> As a daily learner, I want modern ML-based scheduling, so that I retain more with less study time.
>
> **Acceptance**: FSRS algorithm → 4-button rating → personalizes to my performance

**US-3: Keyboard-first review**
> As a keyboard user, I want single-key review, so that I can process 100+ cards without touching mouse.
>
> **Acceptance**: "s" show answer → "1/2/3/4" rate → "n" next → no mouse required

**US-4: Query-based filtering**
> As a tool builder, I want query-based card selection, so that I can create dynamic filtered decks.
>
> **Acceptance**: Query syntax \`tag:X due:today\` → returns matching cards → use in custom workflows

**US-5: Global configuration**
> As a power user, I want global deck settings, so that I don't configure each deck individually.
>
> **Acceptance**: Set intervals/caps once → applies to all decks → per-deck overrides available

### Scheduling Control

**US-6: Maximum interval cap**
> As a power user, I want to cap maximum intervals at 2 years, so that critical knowledge doesn't go unreviewed for decades.
>
> **Acceptance**: Set global max interval → no card scheduled beyond cap → still uses optimal scheduling within cap

**US-7: Balanced new/review ratio**
> As a returning user, I want balanced new/old card ratios, so that I make progress on new material while catching up on overdue reviews.
>
> **Acceptance**: Configure "30% new cards" → each session includes 30% new even when backlog exists

**US-8: Custom scheduling hooks**
> As an experimenter, I want to customize scheduling with JavaScript, so that I can test my own theories about optimal intervals.
>
> **Acceptance**: Write JS function → intercept scheduling state → modify intervals → app uses custom logic

### Integration

**US-9: Markdown storage**
> As an Obsidian user, I want my cards stored in markdown, so that I can version control, sync, and own my data.
>
> **Acceptance**: Cards are regular markdown blocks → work with git → no proprietary format

**US-10: HTTP API**
> As a developer, I want an HTTP API, so that I can automate card creation from external tools.
>
> **Acceptance**: AnkiConnect-compatible API → create cards via JSON-RPC → localhost by default

### UX Improvements

**US-11: Inline help**
> As a confused user, I want inline help on settings, so that I understand impact before changing defaults.
>
> **Acceptance**: Every setting has tooltip → explains algorithm → shows example

**US-12: Breadcrumb navigation**
> As a context-aware learner, I want to see where cards came from, so that I understand them in context of my notes.
>
> **Acceptance**: Breadcrumb shows file/heading → click to jump to source → edit in place

**US-13: Retention statistics**
> As a data-driven user, I want retention statistics, so that I can evaluate my learning effectiveness.
>
> **Acceptance**: Dashboard shows retention % → cards by state → heatmap of activity

**US-14: Struggling card identification**
> As an optimizer, I want to identify struggling cards, so that I can rewrite or delete poor cards.
>
> **Acceptance**: Query \`prop:ease-factor<2.0\` → shows cards I repeatedly fail → bulk edit

**US-15: Visual progress**
> As a streak maintainer, I want visual feedback on consistency, so that I stay motivated.
>
> **Acceptance**: Shows "15 day streak" → daily goal progress → celebration on milestones

---

## References

### Library Documentation
- [Anki Manual](/ankitects/anki-manual) - Official user guide (129 code snippets)
- [AnkiConnect API](/websites/git_sr_ht__foosoft_anki-connect) - HTTP API spec (161k snippets)
- [FSRS4Anki](/open-spaced-repetition/fsrs4anki) - Modern SRS scheduler (trust 9.4)

### Code Examples
- [Logseq SRS Implementation](https://raw.githubusercontent.com/logseq/logseq/main/src/main/frontend/extensions/srs.cljs) - SM-5 algorithm in production
- [Logseq FSRS Implementation](https://raw.githubusercontent.com/logseq/logseq/main/src/main/frontend/extensions/fsrs.cljs) - Modern FSRS in production

### Reference Repos
- \`/Users/alien/Projects/best/logseq/src/main/frontend/extensions/srs.cljs\` - Block-based card system
- \`/Users/alien/Projects/best/logseq/src/main/frontend/extensions/fsrs.cljs\` - FSRS integration

### Articles
- [Anki Power User Workflows 2024](https://www.magneticmemorymethod.com/how-to-use-anki/) - Daily practice patterns
- [Optimizing Retention](https://leananki.com/best-settings/) - Settings guide
- [8 Year User Pain Points](https://forums.ankiweb.net/t/things-that-still-really-piss-me-off-after-using-anki-for-8-years/55826) - UX issues

---

**Research completed**: 2025-10-13

This research synthesized 5+ sources (Context7 Anki/FSRS docs, Exa code examples, Logseq production codebase, user forums, recent articles) to identify **15 user stories** grounded in real workflows, validated pain points, and production-proven patterns.

**Key recommendation**: Adopt Logseq's block-based approach with FSRS algorithm, file-based storage, and query DSL for a modern, flexible SRS system that integrates naturally with note-taking workflows.
