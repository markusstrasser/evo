# Why We Keep Failing to Copy Logseq Behavior

**Date**: 2025-11-13
**Question**: Why can't we just copy Logseq with the source code available?

---

## The Actual Problem

### ❌ What We're Doing Wrong

**Our approach:**
1. Observe Logseq behavior manually
2. Write specs based on what we see
3. Implement features one by one
4. Discover we missed basic stuff (like Enter while editing!)

**Result:** Constant surprises, missing features, incorrect behavior

### ✅ What We Should Do Instead

**Correct approach:**
1. **Read Logseq's keymap file FIRST** (`shortcut/config.cljs`)
2. **Copy the entire keymap structure**
3. **Verify each binding has a handler**
4. **Test systematically**

**Result:** Nothing missed, complete coverage from day one

---

## Example: How We Failed with Enter

### What We Did:

1. **Spec said:** "Enter on selected block enters edit mode"
2. **Implemented:** `:enter-edit-selected` in `:non-editing` mode ✅
3. **Missed:** Enter while editing! ❌

**Why we missed it:**
- We spec'd based on "what user sees" (selected block behavior)
- We never looked at Logseq's FULL keymap
- We assumed "Enter" only had one behavior

### What Logseq Actually Has:

**From `shortcut/config.cljs`:**

```clojure
;; Enter in EDITING mode
:editor/new-block {:binding "enter"
                   :fn editor-handler/keydown-new-block-handler}

;; Enter in NON-EDITING mode (selected block)
:editor/open-edit {:binding "enter"
                   :fn (fn [e]
                         (editor-handler/open-selected-block! :right e))}
```

**Two separate handlers, same key, different contexts!**

If we had just **read this file first**, we would have known:
- Enter has 2 behaviors
- Need both handlers
- Obvious what each does

---

## The Core Issue: Our Spec-First Mentality

### Current Process (BROKEN):

```
1. Observe behavior
2. Write spec
3. Implement
4. Discover we missed stuff
5. Go back to step 1
```

**Problems:**
- Human observation is incomplete
- Specs are guesses
- Implementation is based on guesses
- Endless iteration

### Correct Process:

```
1. Read Logseq's keymap/config files
2. List ALL bindings
3. Create handler mapping table
4. Implement systematically
5. Verify with tests
```

**Benefits:**
- Source code is complete truth
- No guessing
- Know exactly what's needed
- Can check coverage (have we implemented all bindings?)

---

## Systematic Failure Points

### 1. Multiple Spec Files, No Master List

**We have:**
- `LOGSEQ_SPEC.md` - Incomplete
- `EDGE_CASES.md` - Random edge cases
- `CRITICAL_INTERACTION_EDGE_CASES.md` - More edge cases
- `ENTER_ESCAPE_BEHAVIOR.md` - Specific to two keys

**Problem:** No single source says "here are ALL the keybindings"

**What we need:**
- `LOGSEQ_KEYMAP_COMPLETE.md` - EVERY key from Logseq
- Mapping table: Logseq handler → Evo intent
- Coverage checklist

### 2. No Systematic Comparison Tool

**What we should have:**

```markdown
| Key | Context | Logseq Handler | Evo Intent | Status |
|-----|---------|----------------|------------|--------|
| Enter | Editing | keydown-new-block-handler | :context-aware-enter | ✅ |
| Enter | Selected | open-selected-block! | :enter-edit-selected | ✅ |
| Escape | Editing | escape-editing | :exit-edit | ✅ |
| Backspace | Editing | editor-backspace | ... | ❌ MISSING |
| Tab | Editing | keydown-tab-handler | :indent-selected | ✅ |
...
```

**This would make it OBVIOUS what's missing!**

### 3. Implementing Features Instead of System

**We think:** "Let's add Enter at position 0 feature"
**We should think:** "Let's implement the ENTIRE editing keymap"

**The difference:**
- Feature-first: Random collection of behaviors
- System-first: Complete, coherent implementation

---

## How to Fix This

### Step 1: Extract Logseq's Complete Keymap

Create `LOGSEQ_KEYMAP_REFERENCE.md`:

```bash
# Extract all keybindings
grep -A 3 ":binding" /path/to/logseq/shortcut/config.cljs > keymap_raw.txt

# Parse into table format
# List every key, every context, every handler
```

### Step 2: Create Coverage Matrix

```markdown
# Editing Mode Keybindings

| Key | Modifiers | Logseq Handler | Handler File | Evo Status |
|-----|-----------|----------------|--------------|------------|
| Enter | - | keydown-new-block-handler | editor.cljs:3200 | ✅ DONE |
| Enter | Shift | insert-newline | editor.cljs:2500 | ✅ DONE |
| Enter | Cmd | toggle-checkbox | editor.cljs:1800 | ❌ TODO |
| Backspace | - | editor-backspace | editor.cljs:2100 | ❌ TODO |
| Backspace | Cmd | merge-with-prev | editor.cljs:800 | ✅ DONE |
...
```

### Step 3: Implement Systematically

**Priority order:**
1. Core editing (Enter, Backspace, typing)
2. Navigation (arrows, tab)
3. Formatting (bold, italic)
4. Advanced (search, commands)

**For each binding:**
1. Read Logseq handler source
2. Understand what it does
3. Find/create equivalent Evo intent
4. Add to keymap
5. Test
6. Mark as ✅ in coverage matrix

### Step 4: Automated Coverage Check

Create a script:

```bash
#!/bin/bash
# Check keymap coverage

echo "Logseq keybindings: $(count_logseq_bindings)"
echo "Evo keybindings: $(count_evo_bindings)"
echo "Coverage: $((evo/logseq * 100))%"
echo ""
echo "Missing bindings:"
diff logseq_keys.txt evo_keys.txt
```

---

## The Fundamental Assumption Error

### We Assumed:

"Evo's architecture is pure/declarative, so we can just spec behavior and implement intents."

### Reality:

**The keymap IS the spec!**

Logseq's `shortcut/config.cljs` is:
- Complete
- Authoritative
- Easy to read
- Maps directly to handlers

**We should have copied this FIRST**, then built intents to support it.

### Architecture Doesn't Excuse Incompleteness

Yes, Evo's intent system is cleaner than Logseq's handler soup.

But that doesn't mean we can:
- Skip reading their keymap
- Guess at behaviors
- Implement features in random order

**The right way:**
1. Copy Logseq's keymap structure
2. Implement intents to match each binding
3. Enjoy clean architecture AND complete functionality

---

## Action Items

### Immediate:

1. ✅ **Extract Logseq's complete keymap** → Reference doc
2. ✅ **Create coverage matrix** → Track what's done/missing
3. ✅ **Audit current Evo keymap** → What do we have vs. what's needed?
4. ✅ **List missing critical bindings** → Prioritize implementation

### Short-term:

1. Implement ALL core editing bindings (Enter, Backspace, Delete, Tab)
2. Implement ALL navigation bindings (arrows + modifiers)
3. Test each systematically
4. Mark progress in coverage matrix

### Long-term:

1. Create automated coverage checker
2. Add CI check: "Keymap coverage must be 100%"
3. Document mapping: Logseq handler → Evo intent
4. Never add a feature without checking the keymap first

---

## Lessons Learned

### ❌ Don't:
- Spec based on observation
- Implement features in isolation
- Assume anything
- Write incomplete specs

### ✅ Do:
- Read source code FIRST
- Extract complete system (keymap)
- Implement systematically
- Verify coverage programmatically

### The Real Failure:

**We treated Logseq like a black box** (observe, guess, implement)

**When we should have treated it like open source** (read, understand, copy)

---

## Why This Matters

Every time we miss a basic binding like "Enter while editing":
- User tries to type → nothing works
- Frustration
- "This is broken, Logseq works better"

**Complete > Perfect**

Better to have ALL of Logseq's bindings implemented simply
Than to have 50% implemented "perfectly" with clean architecture.

**The keymap is not a detail. It's the UX.**
