#!/usr/bin/env bash
# Check that dependency versions match between deps.edn and shadow-cljs.edn

echo "Checking version sync between deps.edn and shadow-cljs.edn..."

# Extract versions from deps.edn (version is on same line)
DEPS_REPLICANT=$(grep 'no.cjohansen/replicant' deps.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+|[0-9]{4}\.[0-9]{2}\.[0-9]{2})".*/\1/')
DEPS_MALLI=$(grep 'metosin/malli' deps.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')
DEPS_MEDLEY=$(grep 'medley/medley' deps.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')
DEPS_SPECTER=$(grep 'com.rpl/specter' deps.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')
DEPS_DEVTOOLS=$(grep 'binaryage/devtools' deps.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')

# Extract versions from shadow-cljs.edn (Leiningen format)
SHADOW_REPLICANT=$(grep 'no.cjohansen/replicant' shadow-cljs.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+|[0-9]{4}\.[0-9]{2}\.[0-9]{2})".*/\1/')
SHADOW_MALLI=$(grep 'metosin/malli' shadow-cljs.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')
SHADOW_MEDLEY=$(grep '\[medley ' shadow-cljs.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')
SHADOW_SPECTER=$(grep 'com.rpl/specter' shadow-cljs.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')
SHADOW_DEVTOOLS=$(grep 'binaryage/devtools' shadow-cljs.edn | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+)".*/\1/')

# Check for mismatches
ERRORS=0

check_version() {
    local lib=$1
    local deps_ver=$2
    local shadow_ver=$3

    if [ -z "$deps_ver" ] || [ -z "$shadow_ver" ]; then
        echo "⚠️  $lib: Could not extract version"
        return
    fi

    if [ "$deps_ver" != "$shadow_ver" ]; then
        echo "❌ $lib version mismatch:"
        echo "   deps.edn:        $deps_ver"
        echo "   shadow-cljs.edn: $shadow_ver"
        ERRORS=$((ERRORS + 1))
    else
        echo "✅ $lib: $deps_ver"
    fi
}

check_version "replicant" "$DEPS_REPLICANT" "$SHADOW_REPLICANT"
check_version "malli" "$DEPS_MALLI" "$SHADOW_MALLI"
check_version "medley" "$DEPS_MEDLEY" "$SHADOW_MEDLEY"
check_version "specter" "$DEPS_SPECTER" "$SHADOW_SPECTER"
check_version "devtools" "$DEPS_DEVTOOLS" "$SHADOW_DEVTOOLS"

if [ $ERRORS -gt 0 ]; then
    echo ""
    echo "❌ Found $ERRORS version mismatch(es)"
    exit 1
else
    echo ""
    echo "✅ All shared dependencies have matching versions!"
    exit 0
fi
