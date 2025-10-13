#!/usr/bin/env bash
# Evaluator health check - catches common failures before they waste time

set -e

echo "🩺 Evaluator Doctor - Diagnostic Check"
echo "======================================="
echo ""

# Check 1: Classpath resolution
echo "1️⃣  Checking classpath resolution..."
if clojure -M -e "(require '[dev.eval.core-v3 :as v3]) (println \"✓ core-v3 loads\")" 2>&1 | grep -q "✓"; then
    echo "   ✅ Namespace resolution OK"
else
    echo "   ❌ FAIL: Cannot load dev.eval.core-v3"
    echo "   💡 Fix: Check that files are in src/dev/eval/ not dev/eval/"
    exit 1
fi

# Check 2: API keys
echo ""
echo "2️⃣  Checking API keys..."
missing_keys=()
[[ -z "$OPENAI_API_KEY" ]] && missing_keys+=("OPENAI_API_KEY")
[[ -z "$GROK_API_KEY" && -z "$XAI_API_KEY" ]] && missing_keys+=("GROK_API_KEY or XAI_API_KEY")
[[ -z "$GOOGLE_API_KEY" && -z "$GEMINI_API_KEY" ]] && missing_keys+=("GOOGLE_API_KEY or GEMINI_API_KEY")

if [[ ${#missing_keys[@]} -gt 0 ]]; then
    echo "   ⚠️  Missing keys: ${missing_keys[*]}"
    echo "   💡 Source .env or export keys manually"
else
    echo "   ✅ All API keys present"
fi

# Check 3: Dependencies
echo ""
echo "3️⃣  Checking dependencies..."
if clojure -M -e "(require '[clj-http.client :as http]) (println \"✓\")" 2>&1 | grep -q "✓"; then
    echo "   ✅ clj-http available"
else
    echo "   ❌ FAIL: clj-http not found"
    echo "   💡 Run: clojure -P to download deps"
    exit 1
fi

# Check 4: Smoke test with mock judge
echo ""
echo "4️⃣  Running smoke test (mock judge)..."
cat > /tmp/eval-smoke-test.clj <<'EOF'
(require '[dev.eval.core-v3 :refer [evaluate!]])
(def items [{:id :a :text "Option A"} {:id :b :text "Option B"}])
(def result (evaluate! items {:providers [:mock] :max-rounds 2}))
(if (and (seq (:ranking result))
         (= 2 (count (:ranking result))))
  (println "✓ Smoke test passed")
  (do (println "✗ Smoke test failed") (System/exit 1)))
EOF

if clojure -M /tmp/eval-smoke-test.clj 2>&1 | grep -q "✓"; then
    echo "   ✅ Pipeline produces rankings"
    rm /tmp/eval-smoke-test.clj
else
    echo "   ❌ FAIL: Evaluator returned empty ranking"
    echo "   💡 Check tournament seeding and data flow"
    exit 1
fi

# Check 5: Data flow validation
echo ""
echo "5️⃣  Validating data flow..."
if grep -q "run-tournament-round" src/dev/eval/core_v3.cljc && \
   grep -q "\[new-state edge-map\]" src/dev/eval/core_v3.cljc; then
    echo "   ✅ Tournament returns tuple (state + edges)"
else
    echo "   ⚠️  WARNING: run-tournament-round may not return [state edges] tuple"
fi

# Summary
echo ""
echo "======================================"
echo "✅ Evaluator health check PASSED"
echo ""
echo "Quick start commands:"
echo "  ./scripts/quick-eval.sh        # Fast test (10s)"
echo "  clojure -M run_eval_grok.clj   # Test Grok API"
echo "  clojure -M run_eval_verbose.clj # Full debug"
