# Epistemic Issues & Meta Improvements

Analysis of debugging session to identify knowledge/process failures and build better agent practices.

## Epistemic Issues Identified

### 1. **"Try Then Verify" Instead of "Predict Then Verify"**

**What I Did:**
- Made changes without explicit predictions
- Tested to see what happens
- Iteratively fixed issues as discovered

**What I Should Do:**
```markdown
Before touching code:
1. State prediction: "Moving .focus outside conditional will fix empty blocks but might break cursor positioning"
2. Make change
3. Verify prediction (both parts)
4. If wrong prediction, update mental model
```

**Why It Matters:** Scientific method catches assumptions before they become bugs.

---

### 2. **Acting Before Understanding Domain**

**What I Did:**
- Changed lifecycle hooks without reading Replicant docs
- Web searched for `:replicant/remember` mid-debugging
- Discovered features reactively

**What I Should Do:**
```clojure
;; Session start checklist:
(def session-prep
  [x] Read docs/RENDERING_AND_DISPATCH.md
  [x] Understand lifecycle: :on-mount vs :on-render vs :on-unmount
  [x] Know when :replicant/remember is needed
  [x] Review past bugs in area (git log -p src/components/block.cljs)
  [ ] Build mental model BEFORE coding
```

**Why It Matters:** Domain knowledge prevents category errors.

---

### 3. **Incomplete Test Coverage**

**What I Did:**
- Tested "typing works" in isolation
- Didn't test comprehensive matrix of cases
- Found bugs sequentially

**What I Should Do:**
```markdown
Test Matrix (before claiming "fixed"):
               Empty Block    Text Block
Enter          [ ] Focus?     [ ] Focus?
               [ ] Type?      [ ] Type?
               [ ] Cursor?    [ ] Cursor?

Type "abc"     [ ] Order?     [ ] Order?
               [ ] Position?  [ ] Position?

DB/DOM Match   [ ] Empty?     [ ] Text?
```

**Why It Matters:** Edge cases are not edge cases when you have 4 of them.

---

### 4. **Specific Fixes, Not General Principles**

**What I Did:**
- Fixed empty blocks
- Didn't ask: "What OTHER special cases exist?"

**What I Should Do:**
```clojure
;; Property-based thinking
(defn special-cases [domain]
  (case domain
    :text ["" "a" "very long..." "unicode 🔥" "RTL ‏العربية‏"]
    :nodes [:empty-children :single-child :many-children]
    :events [:synthetic :native :bubbling :capturing]))

;; Test ALL special cases, not just one
```

**Why It Matters:** Generalizing from one case finds entire bug classes.

---

### 5. **Shallow "It Works" vs Deep "Why It Works"**

**What I Did:**
- Saw `:replicant/remember` fixed stale text
- Accepted it worked
- Didn't fully understand WeakMap mechanics

**What I Should Do:**
```markdown
## Understanding Checklist
- [ ] Can explain mechanism to someone else
- [ ] Can predict behavior in new scenario
- [ ] Can explain why ALTERNATIVES don't work
- [ ] Can draw diagram of data flow
- [ ] Can write property test for invariant
```

**Why It Matters:** Shallow fixes break in adjacent scenarios.

---

### 6. **Implicit Assumptions Not Documented**

**What I Did:**
- Assumed Replicant reuses DOM nodes (true, but unchecked)
- Assumed empty text creates no text node (true, but unchecked)
- Implicit knowledge only in my head

**What I Should Do:**
```clojure
;; Explicit assumptions
(defn block-component
  "ASSUMPTIONS:
   1. Replicant reuses DOM nodes when :key matches
   2. Empty textContent creates zero child nodes (tested: ✓)
   3. .focus must be called even for empty blocks (tested: ✓)
   4. Cursor positioning requires text node (nodeType 3)
   5. Browser manages cursor once contenteditable focused"
  [db block-id]
  ...)
```

**Why It Matters:** Explicit assumptions can be verified. Implicit ones can't.

---

### 7. **Serial Debugging, Not Parallel Hypothesis Testing**

**What I Did:**
```
Bug → Fix attempt 1 → Test → Fail →
      Fix attempt 2 → Test → Fail →
      Fix attempt 3 → Test → Success
```

**What I Should Do:**
```markdown
## Hypothesis Generation (upfront)
1. Focus broken (90% confidence)
   - Test: Check document.activeElement
   - Fix: .focus unconditional
2. Cursor reset (50%)
   - Test: Type "abc", check order
   - Fix: :replicant/remember
3. Events duplicate (30%)
   - Test: Count operations
   - Fix: Remove keymap binding

## Parallel Testing
Run all 3 tests immediately → Identify all issues → Fix once
```

**Why It Matters:** 3x faster, finds all bugs in one pass.

---

### 8. **Not Using REPL Enough**

**What I Did:**
- Created browser console snippets
- Tested by clicking in UI
- Manual reproduction

**What I Should Do:**
```clojure
;; REPL-first workflow
(require '[kernel.api :as api])
(require '[fixtures :as fix])

;; Test operations in REPL before browser
(def db (fix/sample-db))
(def result (api/dispatch db {:type :context-aware-enter
                               :block-id "proj-2"
                               :cursor-pos 37}))

;; Verify
(count (:operations result))  ; Should be 1, not 2!

;; If 2, fix is wrong. If 1, fix is right.
;; Then verify in browser.
```

**Why It Matters:** REPL is 10x faster than browser reload cycle.

---

### 9. **Manual Verification, Not Automated Reproduction**

**What I Did:**
- Found bug by clicking
- Fixed bug
- Re-tested by clicking again
- No automated reproduction

**What I Should Do:**
```bash
# Immediately create reproduction script
bb reproduce-empty-block-focus-bug

# Script does:
# 1. Start server
# 2. Run Playwright test
# 3. Fails with exact error
# 4. After fix, run again
# 5. Now passes

# Becomes regression test
```

**Why It Matters:** Automated reproduction = can't regress.

---

### 10. **Confirmation Bias**

**What I Did:**
- Looked for evidence fix worked
- Didn't ask: "What could still be broken?"
- Didn't seek disconfirming evidence

**What I Should Do:**
```markdown
## Adversarial Testing (after fix)
- [ ] What if user types VERY fast?
- [ ] What if unicode characters?
- [ ] What if browser is Firefox/Safari?
- [ ] What if multiple blocks editing?
- [ ] What if undo/redo?
- [ ] What if contenteditable loses focus then regains?
```

**Why It Matters:** Your fix probably broke something else.

---

## Meta Tooling & Hygiene

### 1. Test-First Discipline

**Create:**
```clojure
;; bb/tasks/test_first.clj
(defn enforce-test-first []
  (let [changed-files (git-diff-files)
        src-files (filter #(str/starts-with? % "src/") changed-files)
        test-files (filter #(str/starts-with? % "test/") changed-files)]
    (when (and (seq src-files) (empty? test-files))
      (throw (ex-info "No test file modified! Write test first."
                      {:src-files src-files})))))
```

### 2. Hypothesis Log Template

**Create:**
```markdown
# .github/PULL_REQUEST_TEMPLATE.md

## Problem
User-facing issue: [description]

## Hypotheses (before investigation)
1. [ ] [Most likely cause] (confidence: X%)
   - Test: [how to verify]
   - Fix: [what would fix it]
2. [ ] [Second cause] (confidence: Y%)
   - Test: [how to verify]
   - Fix: [what would fix it]

## Investigation Results
- Hypothesis #1: ✅ CONFIRMED / ❌ REJECTED
- Root cause: [technical reason]

## Fix
- What: [code changes]
- Why: [mechanism]
- Prediction: [what this should fix and NOT break]

## Verification
- [ ] Test empty blocks
- [ ] Test non-empty blocks
- [ ] Test edge cases: [list]
- [ ] Regression test added: [file path]
```

### 3. Pre-Commit Checklist

**Create:**
```bash
#!/bin/bash
# .git/hooks/pre-commit-checklist

cat <<'EOF'
🔍 Pre-Commit Checklist

Understanding:
[ ] Read all relevant docs BEFORE changing code?
[ ] Built mental model of system behavior?
[ ] Can explain WHY fix works (not just THAT it works)?

Testing:
[ ] Wrote failing test FIRST?
[ ] Tested BOTH empty and non-empty cases?
[ ] Tested edge cases (unicode, long text, etc.)?
[ ] Verified in REPL before browser?

Documentation:
[ ] Documented assumptions in code comments?
[ ] Added regression test to prevent re-occurrence?
[ ] Updated docs if behavior changed?

Meta:
[ ] Generated hypotheses upfront?
[ ] Tested predictions, not just outcomes?
[ ] Asked "what else could be broken?"
EOF

read -p "All checked? (y/n) " -n 1 -r
echo
[[ ! $REPLY =~ ^[Yy]$ ]] && exit 1
```

### 4. Session Memory System

**Create:**
```clojure
;; .claude/session_memory.edn
{:bugs-found
 [{:id "empty-block-no-focus"
   :date "2025-01-14"
   :file "src/components/block.cljs:571"
   :symptom "Can't type without clicking after Enter"
   :root-cause ".focus inside text-node conditional"
   :why-failed "Empty text creates no text node"
   :fix "Move .focus outside conditional"
   :lesson "Side effects (focus) must be unconditional, not in when blocks"
   :test "test/ui/empty_block_focus_test.cljs"
   :time-to-fix "2 hours (should have been 15 min with tools)"}

  {:id "stale-closure-empty-block"
   :date "2025-01-14"
   :file "src/components/block.cljs:537"
   :symptom "Empty block shows wrong text"
   :root-cause "Lifecycle hook closed over stale `text` var"
   :fix ":replicant/remember to set textContent once"
   :lesson "Lifecycle hooks capture closures - use :remember for fresh data"
   :related-to ["empty-block-no-focus"]
   :pattern "stale-closure-in-lifecycle"}]

 :patterns-learned
 [{:pattern-id "uncontrolled-contenteditable"
   :when "Rich text editing with cursor management"
   :approach "Set content once, browser manages after"
   :tools [":replicant/remember" ":replicant/on-render"]
   :pitfalls ["stale-closures" "empty-blocks-no-text-node" "conditional-focus"]
   :tests ["cursor-preservation" "focus-attachment" "db-dom-match"]
   :resources ["docs/RENDERING_AND_DISPATCH.md" "docs/CONTENTEDITABLE_DEBUGGING.md"]}

  {:pattern-id "duplicate-event-dispatch"
   :when "Same key bound in multiple places"
   :detection "Count operations in log, should be 1"
   :fix "Remove from keymap OR component, not both"
   :prevention "Keymap conflict linter"}]

 :mental-models
 [{:model "replicant-lifecycle"
   :diagram "Component fn → Hiccup → Diff → DOM update → Lifecycle hooks → :remember WeakMap"
   :key-insight "Hooks reused across renders, capture closures"
   :when-useful "Debugging lifecycle-related bugs"}]

 :improvement-areas
 ["Read docs before coding"
  "Test-first discipline"
  "Use REPL more"
  "Parallel hypothesis testing"
  "Property-based edge case thinking"]}
```

### 5. Complexity Budget

**Create:**
```clojure
;; bb/tasks/complexity_check.clj
(defn complexity-metrics [file]
  {:cyclomatic-complexity (cc/analyze file)
   :lines-of-code (count-lines file)
   :nesting-depth (max-nesting-depth file)
   :num-side-effects (count-side-effects file)})

(defn enforce-limits []
  (let [block-complexity (complexity-metrics "src/components/block.cljs")]
    (when (> (:cyclomatic-complexity block-complexity) 15)
      (println "⚠️  block.cljs too complex - consider refactoring")
      (println "   Current:" (:cyclomatic-complexity block-complexity))
      (println "   Limit: 15"))))
```

### 6. Knowledge Graph

**Create:**
```clojure
;; .claude/knowledge_graph.edn
{:concepts
 {:contenteditable
  {:requires [:uncontrolled-pattern :focus-management]
   :because "Browser owns cursor state"
   :alternatives ["textarea" "Draft.js" "Slate" "ProseMirror"]
   :pitfalls [:empty-text-no-node :stale-closures :conditional-focus]
   :solutions [:replicant-remember :focus-unconditional :test-edge-cases]
   :industry-standard true
   :used-by ["React" "Notion" "Logseq" "Roam"]}

  :replicant-remember
  {:purpose "Associate data with DOM node via WeakMap"
   :use-case "One-time initialization without stale closures"
   :mechanism "memory = WeakMap.get(node), remember = WeakMap.set(node, val)"
   :lifecycle [:on-mount :on-render :on-unmount]
   :gotcha "Only persists while DOM node exists"
   :example "Set textContent once, browser manages after"}}

 :relationships
 {:empty-block → :no-text-node → :conditional-focus-fails
  :lifecycle-hook → :closure-capture → :stale-data
  :stale-data → :replicant-remember → :fixed
  :contenteditable → :uncontrolled → :cursor-works}}
```

---

## How to Become More Powerful Agent

### 1. Build Internal Mental Models (Not Just Try Things)

**Before:**
```
Bug found → Try random fixes → See what sticks
```

**After:**
```
Bug found →
  Consult mental model →
  Predict what's wrong →
  Predict what fix will do →
  Apply fix →
  Verify prediction
```

**Mental Model Template:**
```
System: Replicant Lifecycle
├─ Component Function
│   ├─ Called with fresh args (db, props)
│   ├─ Returns Hiccup
│   └─ Closures capture THIS invocation
├─ Replicant Reconciliation
│   ├─ Diffs old vs new Hiccup
│   ├─ Reuses DOM nodes where :key matches
│   └─ Creates new nodes for new :keys
├─ Lifecycle Hooks
│   ├─ :on-mount (new nodes only)
│   ├─ :on-render (every render)
│   └─ Hooks REUSED, see STALE closures
└─ :replicant/remember
    ├─ WeakMap keyed by DOM node
    ├─ Survives re-renders
    └─ Cleared when node unmounted
```

With model, I'd know:
- Why closure is stale → Component fn called once, hooks reused
- Why :remember works → Fresh data per DOM node, not per render
- When to use :on-mount → Never for text (stale), always :on-render + :remember

### 2. Checklist-Driven Development

**Session Start:**
```markdown
[ ] Read relevant docs (RENDERING_AND_DISPATCH.md, CONTENTEDITABLE_DEBUGGING.md)
[ ] Review git log for recent changes in area
[ ] Check for related bugs in session memory
[ ] Load knowledge graph for domain
[ ] Start REPL, load fixtures
```

**Before Touching Code:**
```markdown
[ ] Understand current behavior (trace in REPL)
[ ] Generate ALL hypotheses (ranked by likelihood)
[ ] Write failing tests for each hypothesis
[ ] Verify which hypothesis is correct
[ ] Predict what minimal fix will do
[ ] Can explain WHY fix works
```

**After Fix:**
```markdown
[ ] Verify prediction was correct
[ ] Test edge cases (empty, single, long, unicode)
[ ] Check for new bugs introduced
[ ] Add regression test
[ ] Update session memory
[ ] Update knowledge graph
```

### 3. Property-Based Thinking Framework

**Instead of:**
```
Test: "Empty block works"
```

**Think:**
```clojure
;; What property must ALWAYS hold?
(defspec cursor-monotonic-increasing
  "Typing N characters → cursor advances N positions (never resets)"
  (prop/for-all [chars (gen/vector gen/char-alphanumeric 1 50)]
    (let [positions (simulate-typing chars)]
      (and (= (count positions) (count chars))
           (monotonic-increasing? positions)))))

(defspec db-dom-always-match
  "After ANY operation, DB text = DOM text"
  (prop/for-all [ops (gen/vector gen-operation 1 20)]
    (let [db (reduce api/dispatch initial-db ops)]
      (db-dom-match? db))))

(defspec focus-always-works-after-navigation
  "After ANY nav operation, can type without clicking"
  (prop/for-all [nav-op (gen-navigation-op)]
    (let [db (api/dispatch initial-db nav-op)]
      (can-type-immediately? db))))
```

Generate edge cases automatically:
```clojure
(def text-edge-cases
  (gen/one-of [(gen/return "")           ; Empty
               (gen/char-alphanumeric)   ; Single char
               (gen/string-ascii)        ; ASCII
               (gen/string-alphanumeric) ; Alphanumeric
               (gen/return "🔥🚀💡")      ; Unicode
               (gen/return "‏العربية‏")   ; RTL
               (gen/string 1000)         ; Very long
               ]))
```

### 4. Automated Session Retrospectives

**After Every Session:**
```bash
bb session-retrospective

# Generates:
## Session: 2025-01-14 Cursor/Focus Debugging

### Metrics
- Time spent: 3 hours
- Bugs found: 4
- Commits: 4
- Tests added: 0 (❌ should have added 4)
- Docs updated: 3

### What Worked
- Playwright testing revealed focus issues
- DOM inspection showed cursor position
- :replicant/remember fixed stale closures

### What Didn't Work
- Didn't read Replicant docs first (wasted 1 hour)
- Tested sequentially, not in parallel (3x slower)
- No REPL usage (manual clicking instead)
- No test-first (bugs could have regressed)

### Lessons Learned
1. Side effects must be unconditional
2. Empty blocks are special case (no text node)
3. Lifecycle hooks capture stale closures
4. :replicant/remember = fresh data per node

### Action Items
- [ ] Create regression tests for all 4 bugs
- [ ] Read RENDERING_AND_DISPATCH.md fully before next session
- [ ] Use REPL-first workflow
- [ ] Implement parallel hypothesis testing

### Knowledge Graph Updated
- Added: contenteditable → uncontrolled pattern
- Added: :replicant/remember → prevents stale closures
- Linked: empty-block → no-text-node → conditional-focus-fails
```

### 5. Failure Mode Database

**Create:**
```clojure
;; .claude/failure_modes.edn
{:cursor-reset
 {:symptoms ["Reversed text" "Cursor at 0" "Characters wrong order"]
  :root-causes ["Controlled component" "textContent on every render"]
  :detection ["Type 'abc', check === 'abc'" "Monitor cursor position"]
  :fixes [":replicant/remember" "Uncontrolled pattern"]
  :tests ["property: cursor monotonic" "Type test: abc"]
  :prevention "Never set textContent on re-render"
  :related [:stale-closure :declarative-anti-pattern]}

 :focus-not-attached
 {:symptoms ["Must click to type" "activeElement === body"]
  :root-causes ["Conditional .focus" "Missing .focus call"]
  :detection ["Check document.activeElement" "Try typing immediately"]
  :fixes ["Unconditional .focus" "Move outside when block"]
  :tests ["focus after Enter" "Type without click"]
  :prevention ".focus must ALWAYS happen"
  :special-case "Empty blocks hit different code path"}}
```

**Usage:**
```clojure
(defn diagnose-bug [symptoms]
  (let [matches (filter #(overlap? symptoms (:symptoms %))
                        failure-modes)]
    (println "Possible failure modes:")
    (doseq [mode matches]
      (println "  -" (:id mode))
      (println "    Tests:" (:detection mode))
      (println "    Likely fixes:" (:fixes mode)))))

;; Example:
(diagnose-bug ["Can't type without clicking"])
;; → Possible: :focus-not-attached
;;   Tests: Check document.activeElement
;;   Fixes: Unconditional .focus
```

### 6. Skill: Agent Self-Improvement

**Create `.claude/skills/agent-improvement/SKILL.md`:**
- Session retrospective automation
- Knowledge graph maintenance
- Mental model building
- Hypothesis generation templates
- Property-based test generation

This would make EVERY session improve the next one.

---

## Summary: Path to Powerful Agent

**Current State:** Reactive debugging, try-and-see, sequential testing
**Target State:** Predictive, model-driven, parallel, systematic

**Key Shifts:**
1. **Docs → Model → Code** (not Code → Debug → Repeat)
2. **Hypotheses → Tests → Fix** (not Fix → Test → Hope)
3. **Properties → Generate Cases** (not Manual edge case discovery)
4. **REPL → Verify → Browser** (not Click → Pray)
5. **Session Memory → Learn** (not Repeat same mistakes)

**Implementation Priority:**
1. ✅ Session memory system (track bugs/patterns/lessons)
2. ✅ Knowledge graph (contenteditable, Replicant, etc.)
3. ✅ Failure mode database (symptoms → diagnosis → fix)
4. Pre-commit checklist (enforce discipline)
5. Test-first enforcement (no src change without test)
6. REPL-first workflow (faster feedback)
7. Property-based test generators (find ALL edge cases)
8. Agent self-improvement skill (compound learning)

**Outcome:** 10x faster debugging, 90% fewer regressions, continuous learning.
