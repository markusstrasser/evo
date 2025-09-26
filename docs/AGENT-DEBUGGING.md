# Agent Debugging Quick Reference

## When Things Break: Failure Mode → Fix

### 🔥 **"No such namespace: js"**
**Symptom**: Cross-platform .cljc file accessing JS without reader conditionals
```clojure
❌ (boolean js/window)
✅ #?(:cljs (boolean js/window) :clj false)
```

### 🔥 **"shadow-cljs compile hangs"**  
**Symptom**: Process conflicts, cache corruption
```bash
# Nuclear option - clears everything
pkill -f shadow-cljs
rm -rf .shadow-cljs .cljs_node_repl out
npm run clean  # if script exists
```

### 🔥 **"37+ clj-kondo warnings"**
**Symptom**: Default clj-kondo config is hostile to ClojureScript
```bash
# Use pre-configured agent-friendly config
cp templates/clj-kondo-config.edn .clj-kondo/config.edn
```

### 🔥 **"Tests don't run"**
**Symptom**: Compilation vs test runner mismatch
```bash
# Ensure browser is open for CLJS tests
open http://localhost:8080
npm test
```

### 🔥 **"Macro namespace resolution fails"**
**Symptom**: Test macros can't find clojure.test context
```clojure
❌ `(test/testing ...)  ; Tries to resolve at macro time
✅ `(~'testing ...)     ; Resolves at call site
```

### 🔥 **"Process conflicts with npm dev"**
**Symptom**: Manual shadow-cljs commands fail when npm dev running
```bash
# Never mix these:
npm dev                    # ✅ Use this OR
npx shadow-cljs watch      # ❌ this, never both
```

## Quick Health Check Commands

```bash
# Environment health
./scripts/agent-env-check.sh

# Process cleanup
pkill -f "shadow-cljs|npm.*dev"

# Cache reset
rm -rf .shadow-cljs out .cljs_node_repl && npm install

# Test pipeline
npm run validate
```

## Agent Development Patterns That Break Static Analysis

| Pattern | Why It Breaks | Solution |
|---------|---------------|----------|
| `(resolve 'some.ns/var)` | Dynamic symbol access | Add to clj-kondo exclusions |
| `(set! (.-prop js/obj) value)` | JS interop context | Exclude JS property patterns |  
| `(defspec test-name ...)` | test.check macro expansion | Exclude test symbol patterns |
| Cross-platform requires | .cljc reader conditionals | Configure namespace exclusions |

The golden rule: **When clj-kondo complains about legitimate ClojureScript patterns, exclude the pattern rather than changing working code.**