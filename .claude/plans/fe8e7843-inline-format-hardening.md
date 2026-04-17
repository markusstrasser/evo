# Inline-Format + Paste/Render Pipeline Hardening

**Session:** fe8e7843
**Date:** 2026-04-17
**Revised:** 2026-04-17 after `/critique model` (Gemini 3.1 Pro + GPT-5.4)
**Status:** CLOSED 2026-04-17 via `/critique close`
**Review artifacts:**
- pre-execution: `.model-review/2026-04-17-inline-format-hardening-plan-critique-35dde2/`
- plan-close: `.model-review/2026-04-17-inline-format-plan-close-5866fd/`

## Plan-close outcome

All seven phases shipped. Caught-red-handed loop surfaced one real bug in commit `3f4021fe`: multi-char markers `**`, `==`, `$$` bypassed the intraword guard (only single-char markers were in `word-boundary-markers`). Verified with REPL probes — `a==b==c` highlighted "b", `2**3**4` bolded "3". Extended the guard set to `#{"_" "*" "**" "$" "$$" "=="}` and added the regression corpus. Test suite: 454 tests, 1496 assertions, 0 failures. E2E: 25 tests across inline-format-rendering, text-formatting, copy-rendered, all green.

Known limitation documented: `__init__` / `__main__` standalone will still emphasize as `<strong>init</strong>` / `<strong>main</strong>`. Outer boundaries are string edges, which the guard reads as non-word, so a whole-block dunder is structurally indistinguishable from `__bold__`. Blocking one breaks the other. Backticked code spans (future work) are the explicit escape hatch.

Deferred findings (rationale):
- **Escape-aware image scanner (`\(`/`\)`).** Confirmed behavioral gap but zero incident history; turndown does not emit escaped parens in image URLs. Parked.
- **HTML clipboard with `.marker` spans** leaking to external apps. Primary workflow is intra-app copy-paste where `text/plain` preserves markers correctly. Cross-app degradation (`**hello**` visually bolded in Word) is acceptable.
- **Source-identity preservation for `*` vs `_`.** Parser canonicalizes italic output to `_`. Copying `*x*` roundtrips as `_x_`. Semantic equivalence preserved; lexeme loss is accepted trade-off.

## Review outcome

Both models independently landed on the same three load-bearing corrections, and I verified two concrete PRESENT bugs they flagged by running them as failing unit tests before deleting those probe tests:

| Probe | Input | Result |
|-------|-------|--------|
| GPT-5.4 Q1 | `x = a * b * c` | Fails `all-text?` today — parser italicizes ` b ` (spaces included) |
| GPT-5.4 Q4(b) | `привет_мир_тест` | Fails `all-text?` today — ASCII-only `word-char?` lets Cyrillic through |

Both surface the same root flaw: the word-boundary rule I added only inspects characters *outside* the marker span. CommonMark also requires *inner* boundaries (opener not followed by whitespace; closer not preceded by whitespace) AND Unicode-aware word classification. The fix today was partial.

## Revisions to the plan

### Phase 2 — REVERSE: drop asymmetric rule; adopt symmetric-strict with inner guards

**What changed.** Both models rejected the asymmetric `*` rule. Gemini cited `int *p, int **pp`, `func(*args, **kwargs)`, `2*3*4`. GPT-5.4 added `a*b*c`, `glob=foo*bar*baz`. The lenient-`*` rule re-introduces the exact bug class we just fixed, for code that is MORE common than intraword `<em>` paste.

**New Phase 2 — Symmetric-Strict + Inner-Boundary + Unicode.**

1. **Outer-boundary (already shipped):** reject if the char just outside either marker is a word char.
2. **Inner-boundary (new):** reject if the char immediately after the opener is whitespace, or the char immediately before the closer is whitespace. Matches CommonMark left-flanking / right-flanking rules for `*` and `_`, extended to `$` for math.
3. **Unicode word class (new):** change `word-char?` from `#"[A-Za-z0-9]"` to a Unicode-aware check. In ClojureScript, `/\p{L}|\p{N}|_/u` works (or `XRegExp`-style manual check); in Clojure, `(Character/isLetterOrDigit ch)`. Reader-conditional per platform.
4. **Turndown:** leave `emDelimiter` alone. Accept the documented degradation: intraword `<em>` pasted from rich HTML renders literally. Paste between words (the overwhelmingly common case in real HTML) still italicizes because outer boundaries are whitespace.

**Rejected model alternatives.**

- Gemini's "inject ZWSP around intraword `*` in turndown" — speculative fix for a speculative case; no incident history of rich-text intraword emphasis paste being load-bearing for the user.
- Gemini's CommonMark-lenient `*` — rejected for reasons above.

**Files.**

- `src/parser/inline_format.cljc` — add inner-boundary check in `try-complete-format` / `word-boundary-ok?`; broaden `word-char?` to Unicode with reader conditionals.
- `test/parser/inline_format_test.cljc` — add regression corpus: `x = a * b * c`, `2*3*4 = 24`, `int *p, int **pp`, `func(*args, **kwargs)`, `glob=foo*bar*baz`, `привет_мир_тест`, `変数_名前`, `über_cool`, `価格$合計$円`.

**Tests.**

```clojure
(deftest spaced-operator-guard
  (is (all-text? "x = a * b * c"))
  (is (all-text? "2*3*4 = 24"))
  (is (all-text? "int *p, int **pp"))
  (is (all-text? "func(*args, **kwargs)"))
  (is (all-text? "glob=foo*bar*baz")))

(deftest unicode-intraword-guard
  (is (all-text? "привет_мир_тест"))
  (is (all-text? "変数_名前"))
  (is (all-text? "über_cool"))
  (is (all-text? "価格$合計$円")))

(deftest bounded-still-works
  (is (= [:italic] (types "_italic_")))
  (is (= [:italic] (types "*italic*")))
  (is (= [:math-inline] (types "$x+y$"))))
```

### Phase 4 — REVERSE: drop DOM↔DB offset mapping; adopt hidden-marker render

**What changed.** Both models independently called this a rabbit hole. Gemini proposed hidden marker spans in the render tree (`<span class="sr-only">**</span>`) so native clipboard grabs them. GPT-5.4 proposed dropping the feature entirely and documenting preview copy as lossy. I cosign dropping the offset-mapping approach. I choose between the two alternatives as follows:

**Probe first (2 minutes, confirms whether this is a real incident).** Before building anything: reproduce in headed Playwright — select part of a rendered `**bold**` block, Cmd+C, paste. If markers survive via browser native behavior, no fix needed at all.

**If markers are lost:** go with hidden-marker render. CSS `position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0 0 0 0);` (the standard `.sr-only` recipe). This is purely a render-tree change, zero JS, zero state, REPL-verifiable.

**Rejected model alternatives.**

- Gemini's confidence that `.sr-only` always survives clipboard: flagged in its own "blind spots" section. Mitigation: E2E test per browser. If Safari strips it, fall back to GPT-5.4's "document as lossy."
- GPT-5.4's "disable partial selection in preview mode" — too aggressive a UX regression for a hypothetical problem; keep only as fallback if hidden-markers fail cross-browser.

**Files.**

- `src/components/block.cljs` — `render-formatted-segment` emits `[:span.marker "**"] [:strong value] [:span.marker "**"]` (new CSS class to avoid collision with `.sr-only` semantics).
- `public/styles.css` — `.marker { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0 0 0 0); white-space:nowrap; }`
- `test/e2e/copy-rendered.spec.js` — select rendered text → Cmd+C → assert clipboard contains markers.

**Blast radius check.** Accessibility: screen readers will read "two asterisks hello two asterisks" unless we add `aria-hidden="true"` to the marker spans. Include this.

### Phase 5 — REPLACE invariant: reconstructive identity + negative corpus

**What changed.** GPT-5.4's key insight: reconstructive identity ALONE doesn't catch today's bug. A buggy parser that emits `[{:type :text :value "cljs"} {:type :math-inline :value "core"} {:type :text :value "key"}]` reserializes to the original `cljs$core$key` and passes identity — but it's still wrong because the render path strips the middle markers. Two invariants required:

**Invariant A — reconstructive identity:**

```clojure
(defn serialize [segments]
  (apply str (map (fn [{:keys [type value]}]
                    (case type
                      :text value
                      :bold (str "**" value "**")
                      :italic (str "_" value "_")      ; or "*...*" depending on parser output
                      :highlight (str "==" value "==")
                      :strikethrough (str "~~" value "~~")
                      :math-block (str "$$" value "$$")
                      :math-inline (str "$" value "$")))
                  segments)))

(defspec parse-serialize-identity 1000
  (prop/for-all [s gen/string-alphanumeric]
    (= s (serialize (split-with-formatting s)))))
```

Catches: char drop/duplication, wrong marker count.

**Invariant B — negative-corpus literalness:**

```clojure
(def negative-corpus
  (concat
    ;; Identifier-like
    ["cljs_core_key" "snake_case_id" "a*b*c" "x$y$z" "cljs$core$key"]
    ;; Spaced operators
    ["x = a * b * c" "2*3*4 = 24" "int *p, int **pp"]
    ;; Python/Clojure kwargs
    ["func(*args, **kwargs)" "(apply f *args)"]
    ;; Price/currency
    ["$100$total" "EUR$USD$JPY"]
    ;; Unicode intraword
    ["привет_мир_тест" "変数_名前" "über_cool" "価格$合計$円"]
    ;; Source lines from the repo
    (take 50 (str/split-lines (slurp "src/parser/inline_format.cljc")))))

(deftest negative-corpus-all-literal
  (doseq [s negative-corpus]
    (is (= [{:type :text :value s}] (split-with-formatting s))
        (str "expected all-text for: " (pr-str s)))))
```

Catches: false-positive formatting (today's bug class). The `split-with-formatting` must return exactly one `:text` segment with `:value = s`.

**Files.**

- `test/parser/inline_format_property_test.cljc` — both invariants.
- `test/parser/inline_format_corpus.edn` — optional: move the negative corpus to data file for easy extension.

### Phase 3 — UPGRADE: also codify in `.claude/rules/`

**What changed.** Gemini observed that Phase 3's regex tightening is a one-line fix but the architectural CONTRACT should be codified. Cosign.

**Revised Phase 3.**

1. Pin `processHtmlClass: '\\bmath\\b'` in `public/index.html`.
2. Write `.claude/rules/global-dom-scanners.md`: any future library that scans the DOM (Prism, highlight.js, mermaid, katex, linkify, twemoji) must document (a) its class inclusion/exclusion rules, (b) the `<class>-ignore` class our view containers must carry, (c) the test that asserts it doesn't corrupt prose containing the library's trigger syntax.

### Phase 3.5 — NEW: unify `has-math?` through the parser

**What changed.** Both models flagged `src/components/block.cljs:1572` — `(re-find #"\$[^$]+\$" content)`. This is a shell-side regex that duplicates parser logic and false-positives on today's code-like inputs. Architectural violation: view layer reimplements kernel knowledge.

**Fix.** Export `parser.inline-format/has-math?` (or `has-real-math?`) that returns true iff `split-with-formatting` produces at least one `:math-inline` or `:math-block` segment. Replace the regex call in block.cljs with this.

**Files.**

- `src/parser/inline_format.cljc` — new `has-math?` predicate computed from the parse result.
- `src/components/block.cljs` — replace line 1572 with `(inline-format/has-math? content)`.

**Functional impact.** Today: MathJax `typesetPromise` is triggered on every block containing any `$...$`-looking substring, even when no math spans are emitted (harmless performance waste after the `.math-ignore` fix). After: triggered only when real math spans exist. Eliminates spurious typeset passes.

## Revised execution order

1. **Phase 3** — pin MathJax regex (2 min). Fastest, highest architectural leverage.
2. **Phase 3.5** — unify `has-math?` (15 min). Kernel-side parser exports a predicate; shell switches. REPL-verifiable.
3. **Phase 2 revised** — symmetric-strict + inner-boundary + Unicode word class. Includes the regression corpus. **This is where the real bug-density hides.**
4. **Phase 1** — balanced-paren image scanner. Still sound as originally planned.
5. **Phase 5 revised** — property test with both invariants.
6. **Phase 4 probe first** — cheap test of whether native copy already works. Only build hidden-markers if confirmed broken.
7. **Phase 6** — codify global-scanner contract under `.claude/rules/`.

## Where I disagree with the critique (per plan-review-gate rule)

| Claim | My position |
|-------|-------------|
| Gemini: "inject ZWSP in turndown" for intraword `<em>` | REJECT. Speculative. No incident history for intraword HTML-emphasis paste. Accept degradation per GPT-5.4's "pick one tension side" framing. |
| GPT-5.4: "IME composition E2E test first" | PARK. No incident. User is English-first. Would add if CJK content appears in the DB. Noted in Phase 2 Unicode tests instead. |
| Gemini: "use `.sr-only` class directly" | SOFTEN. Use a distinct class (`.marker`) with the same CSS; avoid colliding with semantic meaning of `.sr-only` which implies accessibility intent. |
| Both: "drop asymmetric `*` rule" | COSIGN. Verified two concrete bugs the asymmetry would have worsened; the fix is more fundamental (inner boundary + Unicode) and subsumes both use cases. |
| Both: "DOM↔DB offset mapping is a rabbit hole" | COSIGN but add a probe step — the feature may already work due to browser-native behavior, in which case Phase 4 is a no-op. |
| Both: "unify `has-math?`" | COSIGN, promote to first-class phase. |
| GPT-5.4: "negative corpus is the key invariant, not reconstructive identity alone" | COSIGN. Both invariants, not one. |

## Context models didn't have

- The user's explicit instruction that dev time doesn't factor in — the plan can absorb the Unicode fix, the corpus generation, and the `has-math?` unification without splitting into phases for effort reasons.
- The already-shipped commits `9642b050` and `a2557236` are the outer-boundary fix and the `.math-ignore` fix respectively; this plan extends them, not replaces them.

## Goals (unchanged, as declared non-negotiable)

Same as v1. Adding one clarification: Goal 1 ("no identifier silently loses characters") now explicitly covers Unicode identifiers after Phase 2 revision.

## Non-goals (unchanged)

Same as v1.

## Metrics for completion

- `bb test` green, including the new negative corpus (≥30 strings) and the reconstructive identity property test at 1000 iterations.
- `npm run test:e2e:chromium` green, including new copy-rendered spec and MathJax-regex-boundary spec.
- Manual: paste the entire `src/parser/inline_format.cljc` source into an evo block. Rendered textContent equals original exactly, character-for-character.
- Grep `src/components/block.cljs` for `\$` finds zero regex patterns; math gating flows through the parser.

## Open questions (ranked)

1. Are `\p{L}` / `\p{N}` / `/u` regex flags portable between CLJ (Java regex) and CLJS (JS regex)? If not, reader conditionals per platform. Verify at REPL first.
2. Does Safari strip `position:absolute` text from clipboard? If yes, Phase 4's hidden-marker approach fails and we fall back to "documented lossy partial copy."
3. Should the negative corpus be EDN-external (easy to extend) or inline (self-contained)? Default: inline for first pass, externalize if it grows beyond ~100 entries.
