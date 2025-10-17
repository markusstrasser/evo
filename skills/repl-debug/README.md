# REPL-First Debugging Skill

Interactive Clojure/ClojureScript debugging workflow - test hypotheses in REPL before editing code.

## Philosophy

**Fast debugging: REPL-first, not code-first**

❌ Slow: Edit → Compile → Reload → Check (5+ minutes)
✅ Fast: REPL → Test → Apply (30 seconds)

## Quick Commands

```bash
# Show workflow guide
./run.sh guide

# Common patterns
./run.sh patterns

# Common pitfalls
./run.sh pitfalls

# Browser console helpers
./run.sh browser-helpers

# Examples
./run.sh examples
```

## Browser Console Helpers

```javascript
DEBUG.summary()       // State overview
DEBUG.events()        // All events
DEBUG.inspectEvents() // Recent with ✅/❌ status
DEBUG.reload()        // Hard reload
```

## Core Principle

Test fixes interactively BEFORE editing code. Know the fix works, then apply it once.

## See Also

- Full docs: `SKILL.md`
- Dev helpers: `../../dev/debug.cljs`
- Quick test: `../../scripts/quick-test.sh`
