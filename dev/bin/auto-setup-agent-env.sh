#!/bin/bash
# Auto-setup agent-friendly environment for any ClojureScript project
# Usage: ./auto-setup-agent-env.sh

echo "🤖 Setting up agent-friendly environment..."

# Detect project type
PROJECT_TYPE="unknown"
if [ -f "shadow-cljs.edn" ]; then
    PROJECT_TYPE="shadow-cljs"
elif [ -f "project.clj" ] && grep -q "clojurescript" project.clj; then
    PROJECT_TYPE="lein-cljs"
elif [ -f "deps.edn" ] && grep -q "cljs" deps.edn; then
    PROJECT_TYPE="deps-cljs"
fi

echo "📁 Detected project type: $PROJECT_TYPE"

# Create .clj-kondo config if missing
if [ ! -f ".clj-kondo/config.edn" ]; then
    echo "📝 Creating agent-optimized .clj-kondo/config.edn..."
    mkdir -p .clj-kondo
    cat > .clj-kondo/config.edn << 'EOF'
{:linters 
  {:unresolved-namespace {:level :error :exclude-patterns ["^js/"]}
   :unresolved-symbol {:level :error :exclude-patterns ["js/.*" "requiring-resolve"]}
   :type-mismatch {:level :error}
   
   :unused-binding {:level :off}
   :unused-namespace {:level :off} 
   :unused-referred-var {:level :off}
   :redundant-do {:level :off}
   :redundant-let {:level :off}
   :misplaced-docstring {:level :off}
   :namespace-name-mismatch {:level :off}
   :unused-value {:level :off}
   :inline-def {:level :off}
   :shadowed-var {:level :off}}}
EOF
fi

# Create pre-commit check if missing
if [ ! -f ".pre-commit-check.sh" ]; then
    echo "🔍 Creating pre-commit validation..."
    cat > .pre-commit-check.sh << 'EOF'
#!/bin/bash
set -e
echo "🔍 Pre-commit validation..."

CLJC_FILES=$(git diff --cached --name-only | grep -E "\.cljc$" || true)
if [ -n "$CLJC_FILES" ]; then
    if echo "$CLJC_FILES" | xargs grep -l "js/" 2>/dev/null; then
        echo "❌ Found bare js/ references in .cljc files"
        exit 1
    fi
fi

if [ -f "shadow-cljs.edn" ]; then
    if ! npx shadow-cljs compile test > /dev/null 2>&1; then
        echo "❌ Shadow-cljs compilation failed"
        exit 1
    fi
fi

echo "✅ Pre-commit checks passed"
EOF
    chmod +x .pre-commit-check.sh
fi

# Update package.json if present
if [ -f "package.json" ] && ! grep -q "pre-commit" package.json; then
    echo "📦 Adding agent-friendly npm scripts..."
    # This is a simplified version - in practice you'd use jq or similar
    echo "   Manual step: Add these scripts to package.json:"
    echo '   "lint": "clj-kondo --lint src test --fail-level error"'
    echo '   "validate": "npm run lint && npm run test"'
    echo '   "pre-commit": "./.pre-commit-check.sh"'
fi

# Add to .gitignore
if [ -f ".gitignore" ] && ! grep -q ".clj-kondo/.cache" .gitignore; then
    echo "🚫 Updating .gitignore..."
    echo ".clj-kondo/.cache/" >> .gitignore
fi

echo "✅ Agent environment setup complete!"
echo "   Run './scripts/agent-env-check.sh' to verify health"