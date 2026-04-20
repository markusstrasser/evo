# DOM-Scanner Hardening & Text-Round-Trip Integrity

**Session:** `49665f92-6a87-4379-aa0b-fa5126307053`
**Date:** 2026-04-19
**Scope:** Close the class of bugs where a downstream renderer (MathJax today; Prism/highlight.js/etc. tomorrow) mutates user text in the DOM such that `textContent ≠ DB text`.

## Motivating incident

User typed `asdadwad $key(map_entry){return cljs.core._key(map_entry);}$` and saw garbled output. Root cause: parser accepted the `$...$` pair (space-bounded, non-whitespace inner), emitted `[:span.math "..."]`, MathJax CHTML-typeset the contents — dropping delimiters, braces, semicolons, and whitespace from the rendered glyphs. Three prior commits (`3f4021fe`, `a2557236`, `71846480`) each patched one symptom without addressing the structural problem.

Already shipped in this session:
- `0fb73022` — reject `$...$` containing `;`, `function `, `return `.
- `075e894c` — extend to `\n`, `[[`, and `$$...$$` block variant; add e2e round-trip corpus (42 inputs); unify `blocks.html` with `index.html`; tighten `processHtmlClass` anchor to space-boundary.
- `1c11875c` — preserve user's typed marker char in copy round-trip (`*x*` no longer renders as `_x_`).

The heuristic blocklist now has 5 signals. Each was correct. The growth rate is the signal.

## Scope constraint

**MathJax stays as-is.** Prior sessions tried alternatives; decision was to keep MathJax with the current `processHtmlClass`/`ignoreHtmlClass` approach. This plan does not re-open that choice.

The plan's job is to make the current architecture **safe to live with** — detect corruption at the write boundary, replace the parser blocklist with a principled grammar, expand test coverage to the long tail, and stop future scanners from being added without the contract.

## Invariant we want to enforce

> For every block, `render(db-text).textContent == db-text`. Today this holds contingently — via `math-ignore` per-element opt-outs, a regex anchor, and a growing parser blocklist. One missed class, one new scanner added without the contract, and corruption returns.

---

## Phase 0 — Write-side tripwire (ship first, highest ROI)

**Why first:** turns silent corruption into a loud error at the exact moment it happens. Covers MathJax, paste handlers, future scanners, and bugs we haven't thought of.

**What to build:**
- Add `valid-block-text?` predicate in `src/kernel/schema.cljc` (or a new `src/kernel/text_validation.cljc`). Rejects:
  - Unicode private-use-area codepoints (`\uE000`–`\uF8FF`) — MathJax CHTML glyphs live here.
  - `<mjx-` / `<script` / `<style` literal substrings.
  - Control chars other than `\n` and `\t`.
- Wire into the `:update-node` op in the transaction pipeline — invalidate the whole transaction if any text field fails. Log the offending block-id and the failing signal.
- Dev-mode: throw. Production: drop the op and surface a user-visible notification via `vs/show-notification!`.

**Files:**
- `src/kernel/schema.cljc` (new predicate) or new ns.
- `src/kernel/transaction.cljc` (wire into validate phase).
- `test/kernel/text_validation_test.cljc` (unit tests for the predicate).
- `test/e2e/text-corruption-tripwire.spec.js` (negative test: simulate a MathJax-typeset blur, assert the update is rejected).

**Exit criteria:**
- Unit test passes for all characters the predicate should reject/accept.
- E2E test that injects `\uE001` into buffer before blur sees `:update-content` rejected.
- If any of the three pre-existing parser fixes regress, the tripwire catches the resulting corrupt commit rather than silently persisting it.

**Risk:** low. Validation happens at a single chokepoint. Rollback is deleting one predicate call.

**Deferred decision — USER INPUT NEEDED:**
- Production behavior: reject-and-notify, reject-silently, or warn-and-sanitize (strip private-use chars and accept)?

---

## Phase 1 — Generative property tests at the parser ↔ renderer boundary

**Why:** the 42-input corpus in `inline-format-rendering.spec.js` is a start. The long tail (Unicode oddities, unbalanced markers, adversarial inputs) needs random coverage.

**Approach:**
- Extend `test/parser/inline_format_property_test.cljc` — namespace already exists.
- Add a generator that produces random inputs mixing: ASCII prose, CLJS identifiers, shell fragments, TeX-shaped strings, page refs, Unicode.
- Property 1 (parser): `split-with-formatting` output's `:value` chars concatenate back to a subsequence of the input (no chars invented, no chars dropped).
- Property 2 (integration, JVM-only via a stub renderer): for every parser output, rendering to hiccup and extracting textContent-equivalent strings round-trips to the input.
- Run with 1000+ iterations in CI; fewer locally.

**Files:**
- `test/parser/inline_format_property_test.cljc` (extend).
- `test/parser/render_contract_test.cljc` (new — renderer contract).

**Exit criteria:**
- Both properties pass at 1000 iterations.
- At least one shrunk counterexample recorded as a regression test.

**Risk:** low. Pure test addition.

---

## Phase 2 — Parser grammar strategy — DECIDED: C (status quo + blocklist)

**Decision 2026-04-20:** keep `math-content-ok?` as a growing blocklist.
Rationale: dollars signal math intent; code lives inside backticks and
code blocks. Users don't naturally write `$let x = 1$` — the existing
incident was a test input. When they do accidentally create a
code-flavored `$...$` pair, the garbled MathJax output provides its
own feedback loop, and Phase 0's tripwire backstops any persisted
corruption. Blocklist has grown twice in practice (`;`, newlines);
each growth is cheap.

**Rejected alternatives:**

**Strategy A: Explicit fencing.**
- Require math to be inside backtick-escaped form: `` `$x^2$` ``.
- Any bare `$...$` is text, period. Breaking change for existing user content.
- Requires migration: scan existing blocks, wrap math-looking spans with backticks.

**Strategy B: Strict TeX whitelist.**
- Content must contain at least one of: `\<letters>`, `^`, `_` (TeX sub/superscript).
- Otherwise, not math. Rejects `$key$`, `$core$`, `$100$` automatically — no blocklist maintenance.
- Breaks pure-letter inline math ($x$ alone) but that's rare in prose.

**Strategy C: Status quo + growing blocklist.**
- Keep the five signals. Add more as bugs emerge. Accept that parsers for ambiguous syntax always look like this.

B was the initial recommendation but rejects simple math like `$a=b$`
and `$x+y$` (no TeX marker), which users legitimately write in notes.
Too strict for a generalist writing app.

**Exit criteria for C (already met):**
- Phase 0 tripwire is live as the backstop.
- Blocklist is tested at unit + e2e level.
- Future additions follow the same pattern: add signal to
  `math-content-ok?`, add negative case to
  `test/parser/inline_format_test.cljc`, add entry to
  `test/e2e/inline-format-rendering.spec.js` round-trip corpus.

---

## Phase 3 — Consolidate HTML entry points

**Why:** we already saw `blocks.html` and `index.html` drift apart on MathJax config. CSP headers, keymap registrations, tracking pixels, storage init — any page-level concern can drift.

**Approach:**
- Single source: `public/index.html` is canonical.
- `public/blocks.html` becomes a symlink to `index.html`, OR is deleted and shadow-cljs `:dev-http` rewrites `/blocks.html` → `/index.html`.
- `public/quick-test.html`: same treatment unless it has a genuine reason to differ (document inline if so).

**Files:**
- `public/blocks.html`, `public/quick-test.html` — remove or symlink.
- `shadow-cljs.edn` — verify `:dev-http` serves the consolidated file.

**Exit criteria:**
- `diff public/index.html public/blocks.html` returns nothing, or both files don't independently exist as separate copies.

**Risk:** trivial.

---

## Phase 4 — Enforce "test at the level of the claim"

**Why:** commit `71846480` claimed `\bmath\b` anchors. An integration test incidentally passed because MathJax's internal wrapping saved us. A claim-level test (`/\bmath\b/.test("math-ignore")`) would have failed immediately.

**Approach:**
- Add a lightweight rule `.claude/rules/commit-claim-testing.md`: commits whose message contains "anchor", "escape", "validate", "sanitize", or "reject" should include a test named for the claim (e.g. `test-math-class-anchor-rejects-ignore-variant`).
- Not enforceable by hook without parsing commit-body text; instead, extend the session-analyst subagent checklist.

**Files:**
- `.claude/rules/commit-claim-testing.md` (new).
- `~/.claude/agents/session-analyst.md` (append the check).

**Risk:** trivial. Advisory rule.

---

## Sequencing

1. **Phase 0 (tripwire)** — ship first. Prerequisite for sleeping soundly. Single session.
2. **Phase 3 (HTML consolidation)** — ship alongside Phase 0. Trivial.
3. **Phase 1 (property tests)** — ship alongside Phase 0; independent.
4. **Phase 2 (grammar decision)** — after Phase 0 lands. Needs user decision on A/B/C.
5. **Phase 4 (claim testing rule)** — drop-in any time.

## Open questions

1. **Phase 0 production behavior:** currently reject-on-tripwire (transaction drops the op, DB unchanged). User-visible notification is not yet wired; dev-mode just logs via issue-kw. Revisit if a real-world reject ever fires.
2. ~~**Phase 2:** A/B/C~~ — decided 2026-04-20: C.
3. **Other scanners?** Current roster per `rules/global-dom-scanners.md`: only MathJax. `grep -r "typesetPromise\|highlightAll\|katex\|prism\|mermaid" src/ public/` returns only MathJax hooks. Confirmed.

## Exit criteria for the whole plan

- Silent text corruption becomes a loud, traceable error at the write boundary (Phase 0).
- Parser behavior is principled, not a blocklist (Phase 2, if B chosen).
- Regression coverage is generative, not anecdotal (Phase 1).
- Single source of HTML truth (Phase 3).
- Commits that claim a guarantee carry a test at the level of that claim (Phase 4).

## Non-goals

- **Changing MathJax.** Keep `processHtmlClass`/`ignoreHtmlClass` approach. This was decided in prior sessions.
- Redesigning the kernel.
- Touching `specs.edn`, `failure_modes.edn`, or the FR registry.
- Adding new formatting syntaxes (backtick code spans, etc.) — that's its own plan.
- Migrating existing user journals except where a phase explicitly requires it (Phase 2A only).
