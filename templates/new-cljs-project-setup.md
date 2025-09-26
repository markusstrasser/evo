# Agent-Optimized ClojureScript Project Setup

## Essential Files to Create Immediately

### 1. `.clj-kondo/config.edn` - Agent-Friendly Linting
```edn
{:linters 
  {:unresolved-namespace {:level :error :exclude-patterns ["^js/"]}
   :unresolved-symbol {:level :error :exclude-patterns ["js/.*" "requiring-resolve"]}
   :type-mismatch {:level :error}
   
   ; Disable noisy style warnings for agent development
   :unused-binding {:level :off}
   :unused-namespace {:level :off} 
   :unused-referred-var {:level :off}
   :redundant-do {:level :off}
   :redundant-let {:level :off}
   :misplaced-docstring {:level :off}
   :namespace-name-mismatch {:level :off}
   :unused-value {:level :off}
   :inline-def {:level :off}
   :shadowed-var {:level :off}}
   
 :lint-as {your.project/macro-name clojure.test/testing}}
```

### 2. `.pre-commit-check.sh` - Catch Real Issues
```bash
#!/bin/bash
set -e
echo "🔍 Validating changes..."

# Check cross-platform .cljc files
CLJC_FILES=$(git diff --cached --name-only | grep -E "\.cljc$" || true)
if [ -n "$CLJC_FILES" ]; then
    if echo "$CLJC_FILES" | xargs grep -l "js/" 2>/dev/null; then
        echo "❌ Found bare js/ references - add reader conditionals"
        exit 1
    fi
fi

# Validate compilation  
if ! npx shadow-cljs compile test > /dev/null 2>&1; then
    echo "❌ Compilation failed"
    npx shadow-cljs compile test
    exit 1
fi

echo "✅ Validation passed"
```

### 3. `package.json` - Smart Scripts
```json
{
  "scripts": {
    "lint": "clj-kondo --lint src test --fail-level error",
    "validate": "npm run lint && npm run test", 
    "pre-commit": "./.pre-commit-check.sh",
    "test": "./.pre-commit-check.sh && shadow-cljs compile test && node out/tests.js",
    "clean": "rm -rf .shadow-cljs out .cljs_node_repl"
  }
}
```