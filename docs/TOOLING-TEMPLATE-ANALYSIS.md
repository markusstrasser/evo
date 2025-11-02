# Clojure Project Tooling: Template vs Manual Analysis

> **Based on:** Deep analysis of `evo` project architecture + research into modern Clojure tooling (deps-new, neil, babashka, shadow-cljs)
>
> **Goal:** Identify reusable patterns for future projects vs project-specific customization

## Executive Summary

**Templatable (80%):** Core tooling configuration, quality gates, dev workflow scripts, skill scaffolding
**Manual per project (20%):** Domain-specific deps, build targets, business logic, project-specific skills

## Modern Clojure Tooling Ecosystem (2025)

### Standard Tools
1. **deps-new** (Sean Corfield) - Project generation from templates
2. **neil** (Babashka) - Dependency management CLI
3. **babashka** - Task automation (replaces Makefiles/npm scripts)
4. **shadow-cljs** - ClojureScript compilation
5. **clj-kondo** - Linting

### Installation Pattern
```bash
# One-time global setup
clojure -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
brew install babashka/brew/neil
npm install -g shadow-cljs
brew install clj-kondo
```

---

## I. FULLY TEMPLATABLE (Copy & Minimal Edit)

### 1. `deps.edn` Structure

**Template:**
```clojure
{:paths ["src" "test" "dev" "agent"]

 :deps {org.clojure/clojurescript {:mvn/version "1.12.42"}
        ;; PROJECT-SPECIFIC: Add domain deps here
        }

 :aliases
 {;; Standard across all projects
  :nrepl {:extra-paths ["src" "test" "dev" "agent"]
          :extra-deps {nrepl/nrepl {:mvn/version "1.5.1"}}
          :jvm-opts ["-Djdk.attach.allowAttachSelf"]
          :main-opts ["-m" "nrepl.cmdline" "--port" "7888"]}

  :test {:extra-paths ["test"]
         :extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}}
         :main-opts ["-m" "kaocha.runner"]
         :exec-fn kaocha.runner/exec-fn
         :exec-args {:color? true :fail-fast? false}}

  ;; OPTIONAL: If using ClojureScript
  :mcp-shadow-dual {:extra-deps {io.github.bhauman/clojure-mcp
                                 {:git/sha "2dd09e595df7bb9b01a7c811955a354777253dc2"}}
                    :exec-fn clojure-mcp.main/start-mcp-server
                    :exec-args {:port 55449}}}}
```

**Customization needed:**
- `:deps` - Add project-specific libraries
- `:paths` - Adjust if different source structure
- Port numbers if running multiple projects

**Management workflow:**
```bash
# Add dependencies using neil
neil dep add :lib metosin/malli
neil dep add :lib datascript/datascript

# Upgrade all dependencies
neil dep upgrade
```

### 2. `bb.edn` - Babashka Tasks

**Fully templatable structure:**
```clojure
{:paths ["dev" "src" "test"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        org.clojure/clojurescript {:mvn/version "1.11.60"}}

 :tasks
 {:requires ([babashka.fs :as fs]
             [clojure.string :as str])

  ;; UNIVERSAL QUALITY GATES (copy as-is)
  lint {:doc "Run clj-kondo linter"
        :task (shell "clj-kondo --lint src test dev")}

  check {:doc "Lint + compile check (full quality gate)"
         :task (do
                 (run 'lint)
                 (shell "npx shadow-cljs compile :frontend"))}

  test {:doc "Run test suite"
        :task (do
                (shell "npx shadow-cljs compile :test")
                (shell "node out/tests.js"))}

  check-deps-sync {:doc "Verify deps.edn and shadow-cljs.edn versions match"
                   :task (shell "scripts/check-version-sync.sh")}

  ;; UNIVERSAL CACHE MANAGEMENT
  clean {:doc "Clear all caches and semantic search index"
         :task (do
                 (println "Clearing caches...")
                 (fs/delete-tree ".shadow-cljs")
                 (fs/delete-tree ".cpcache")
                 (when (fs/exists? "node_modules/.cache")
                   (fs/delete-tree "node_modules/.cache"))
                 (println "Cleaning semantic search index...")
                 (shell "ck --clean-orphans .")
                 (doseq [dir (fs/glob "." "**/.ck")]
                   (println (str "  Removing " dir))
                   (fs/delete-tree dir))
                 (println "✓ Caches and index cleaned"))}

  index {:doc "Rebuild ck (semantic search) embeddings index"
         :task (do
                 (println "Rebuilding semantic search index...")
                 (println "Cleaning orphaned files...")
                 (shell "ck --clean-orphans .")
                 (println "Indexing source files...")
                 (shell "ck --sem --threshold 0.0 --limit 1 'test' src/")
                 (println "Indexing session files...")
                 (shell "ck --sem --threshold 0.0 --limit 1 'test' ~/.claude/projects/-Users-alien-Projects-evo/")
                 (println "✓ Index rebuilt"))}

  ;; PROJECT-SPECIFIC (customize)
  dev {:doc "Start shadow-cljs dev server + watch"
       :task (shell "npx shadow-cljs watch :frontend")}

  install-hooks {:doc "Install git pre-commit hooks"
                 :task (shell "scripts/install-hooks.sh")}

  help {:doc "Show available tasks"
        :task (shell "echo 'See bb.edn for task definitions'")}}}
```

**What to customize:**
- `:dev` task - adjust shadow-cljs build target
- Add project-specific tasks (e.g., database migrations, asset processing)

### 3. `.clj-kondo/config.edn`

**100% templatable** (just adjust `:dependencies` if using custom namespaces):

```clojure
{:lint-as {clojure.test.check.generators/let clojure.core/let
           clojure.test.check.clojure-test/defspec clojure.test/deftest
           promesa.core/let clojure.core/let}

 :linters {:syntax :off
           :unresolved-namespace {:level :error}
           :unresolved-symbol {:level :error}
           :unused-namespace {:level :warning
                              :exclude [promesa.core
                                        clojure.edn
                                        clojure.string]}
           :unused-binding {:level :warning}
           :invalid-arity {:level :error}
           :type-mismatch {:level :error}
           :misplaced-docstring {:level :warning}
           :missing-docstring {:level :off}
           :deprecated-var {:level :warning}
           :redefined-var {:level :error}
           :refer-all {:level :warning}
           :consistent-alias {:level :warning
                              :aliases {medley.core m
                                        clojure.set set
                                        clojure.string str}}
           :format {:level :off}}

 ;; PROJECT-SPECIFIC: Module boundaries
 :dependencies {core.db #{core.db}
                core.ops #{core.ops}}

 :skip-lint ["test/fixtures"]}
```

**Customization:**
- `:dependencies` - Define module boundaries for your architecture
- `:lint-as` - Add custom macros

### 4. Git Hooks Infrastructure

**`scripts/install-hooks.sh`** - 100% templatable:
```bash
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_SRC="$SCRIPT_DIR/hooks"
HOOKS_DEST=".git/hooks"

echo "Installing git hooks..."
for hook in "$HOOKS_SRC"/*; do
    if [ -f "$hook" ]; then
        hook_name=$(basename "$hook")
        cp "$hook" "$HOOKS_DEST/$hook_name"
        chmod +x "$HOOKS_DEST/$hook_name"
        echo "✓ Installed $hook_name"
    fi
done
echo "✓ Git hooks installed successfully"
```

**Hook templates** (`scripts/hooks/pre-commit`, `pre-push`):
- Copy structure as-is
- Customize validation logic per project

### 5. REPL Development Helpers

**`dev/repl/init.cljc`** - Templatable pattern:

```clojure
(ns repl
  "REPL development utilities - templatable across projects"
  (:require [clojure.string :as str]))

;; UNIVERSAL HELPERS (copy as-is)
(defn connect! []
  (println "Connecting to shadow-cljs nREPL..."))

(defn init! []
  (println "Loading clojure-plus enhancements...")
  ;; Load project namespaces
  )

(defn go!
  "One-command startup"
  []
  (connect!)
  (init!)
  (println "✓ REPL ready"))

;; TEST RUNNERS (universal)
(defn rt!
  "Run all tests"
  []
  (println "Running tests..."))

(defn rq!
  "Reload and run specific test"
  [test-ns]
  (println (str "Running " test-ns "...")))

;; PROJECT-SPECIFIC: Add domain helpers
(defn sample-db! [fixture-key]
  ;; Load test data
  )
```

**Pattern:** Universal structure + project-specific helpers

### 6. Package.json Structure

**Template:**
```json
{
  "name": "project-name",
  "version": "0.0.1",
  "scripts": {
    "start": "npm run dev",
    "dev": "concurrently --kill-others-on-fail \"npm run watch:cljs\" \"npm run watch:css\"",
    "watch:cljs": "npx shadow-cljs watch :frontend",
    "watch:css": "tailwindcss -i input.css -o public/output.css --watch",
    "build": "npx shadow-cljs clean && npx shadow-cljs release :frontend && tailwindcss -i input.css -o public/output.css --minify"
  },
  "devDependencies": {
    "@playwright/test": "^1.56.0",
    "concurrently": "^9.2.1",
    "shadow-cljs": "^3.2.0"
  }
}
```

**Customization:**
- `name` field
- Build targets (`:frontend` → your build name)
- Add project-specific deps

### 7. Skills Infrastructure

**Directory structure (100% templatable):**
```
skills/
  <skill-name>/
    SKILL.md          # Progressive disclosure L1 (metadata)
    run.sh            # Main orchestration script
    data/             # Skill-specific data files
    examples/         # Usage examples
```

**`run.sh` template pattern:**
```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source .env for API keys
if [[ -f "${SCRIPT_DIR}/../../.env" ]]; then
    source "${SCRIPT_DIR}/../../.env"
fi

usage() {
    cat <<EOF
Skill Name - Description

USAGE:
    run.sh <command> [options]

COMMANDS:
    command1 <arg>   Description
    command2 <arg>   Description
    list             List available items
    help             Show this help
EOF
}

case "${1:-}" in
    command1) shift; do_command1 "$@" ;;
    command2) shift; do_command2 "$@" ;;
    list) list_items ;;
    *) usage; exit 1 ;;
esac
```

**`SKILL.md` frontmatter (universal):**
```markdown
---
name: Skill Display Name
description: Brief description with triggers. Requires: tools, env vars.
---

# Skill Name

## Prerequisites
...

## Quick Start
...

## When to Use
...
```

---

## II. PARTIALLY TEMPLATABLE (Template + Customization)

### 1. `shadow-cljs.edn`

**Template structure:**
```clojure
{:source-paths ["src" "test" "dev" "lab"]

 ;; SYNC WITH deps.edn (use scripts/check-version-sync.sh)
 :dependencies
 [[org.clojure/clojurescript "1.12.42"]
  ;; PROJECT-SPECIFIC: Mirror deps.edn here
  ]

 :dev-http {8080 "public"}
 :nrepl {:port 55449}

 :compiler-options
 {;; PROJECT-SPECIFIC: Add externs as needed
  :externs ["datascript/externs.js"]}

 :builds
 {;; CUSTOMIZE BUILD TARGETS
  :frontend
  {:target :browser
   :modules {:main {:init-fn your-ns.main/main}}}

  :test
  {:target :node-test
   :output-to "out/tests.js"
   :ns-regexp "-test$"}}}
```

**Always manual:**
- `:builds` - Define per-project targets (browser, node, etc.)
- `:dependencies` - Must sync with `deps.edn`
- `:compiler-options` - JS interop externs

**Automation:** `scripts/check-version-sync.sh` validates sync

### 2. `.mcp.json` - MCP Server Configuration

**Template pattern:**
```json
{
  "mcpServers": {
    "clojure-shadow-cljs": {
      "command": "clojure",
      "args": ["-M:mcp-shadow-dual"],
      "cwd": "."
    },
    "beads": {
      "command": "npx",
      "args": ["-y", "plugin:beads@beads-marketplace"]
    }
  }
}
```

**Project-specific:**
- Add domain-specific MCP servers
- Adjust ports if running multiple projects

### 3. Documentation Templates

**CLAUDE.md/AGENTS.md structure (templatable):**

```markdown
## The Start
[Project-specific overview generation instructions]

## Unified Toolchain
[Standard bb tasks, ck search, llmx patterns - COPY AS-IS]

## Skills
[Table of skills - UPDATE per project]

## Dev Tooling
[REPL quickstart - CUSTOMIZE helpers]

## Architecture
[PROJECT-SPECIFIC: Domain architecture]

## MCP Integration
[List active MCPs - CUSTOMIZE]

## Quality Gates
[Pre-commit hooks, linting - COPY AS-IS]
```

**Pattern:** 60% universal tooling docs, 40% domain-specific

---

## III. ALWAYS MANUAL (Per-Project)

### 1. Domain Dependencies

**Must research and add manually:**
- UI frameworks (Replicant, Reagent, Electric)
- State management (DataScript, Datalevin, re-frame)
- Domain libs (validation, serialization, etc.)

**Tool:** Use `neil` for discovery:
```bash
neil dep search malli
neil dep add :lib metosin/malli
```

### 2. Build Targets

**Shadow-cljs builds vary by:**
- Browser vs Node vs React Native
- Code splitting strategy
- Multiple entry points
- Shared vendor bundles

**Example variations:**
```clojure
;; Simple SPA
:app {:target :browser
      :modules {:main {:init-fn app.core/main}}}

;; Code splitting with vendor bundle
:app {:target :browser
      :modules {:vendor {:entries [cljs.core replicant.dom]}
                :main {:init-fn app.core/main
                       :depends-on #{:vendor}}}}

;; Multiple apps in monorepo
:admin {:target :browser ...}
:user {:target :browser ...}
```

### 3. Project-Specific Skills

**Domain skills require:**
- Custom data sources
- Business logic integration
- Project-specific workflows

**Example:** `skills/architect/` in `evo` is domain-specific (event sourcing)

### 4. Test Fixtures

**`test/fixtures.cljc`** - Always custom:
- Domain entities
- Sample data generators
- Test helpers for business logic

### 5. Module Boundaries

**clj-kondo `:dependencies`** - Define per architecture:
```clojure
:dependencies {core.db #{core.db}
               core.ops #{core.ops}
               core.interpret #{core.db core.ops core.schema}}
```

---

## IV. TEMPLATE CREATION WORKFLOW

### Option 1: Using `deps-new` (Recommended)

**Create custom template:**

```bash
# 1. Create template repo structure
mkdir -p my-template/root
cd my-template

# 2. Create template.edn (declarative)
cat > template.edn <<EOF
{:description "Clojure/Script project with evo tooling patterns"
 :year "{{now/year}}"
 :version "0.1.0"
 :transform
 [{:path "bb.edn" :retain true}
  {:path "deps.edn" :retain true}
  {:path ".clj-kondo" :retain true}
  {:path "scripts" :retain true}
  {:path "dev/repl" :retain true}
  {:path "skills" :retain true}
  {:path "src/{{top/file}}/{{main/file}}.clj" :retain true}]}
EOF

# 3. Populate root/ with template files
cp -r /path/to/evo/{bb.edn,deps.edn,.clj-kondo,scripts,dev,skills} root/

# 4. Create src/ placeholder
mkdir -p root/src/{{top/file}}/{{main/file}}.clj

# 5. Install as template
clojure -Tnew install :template my-org/my-template :version '"0.1.0"'

# 6. Use template
clojure -Tnew create :template my-org/my-template :name com.example/new-project
```

**Template variables:**
- `{{top/ns}}` → `com.example`
- `{{main/ns}}` → `com.example.new-project`
- `{{name}}` → `new-project`
- `{{now/year}}` → `2025`

### Option 2: Shell Script Template

**`create-project.sh`:**
```bash
#!/bin/bash
set -euo pipefail

PROJECT_NAME="$1"
PROJECT_DIR="${2:-.}"

echo "Creating Clojure project: $PROJECT_NAME"

# 1. Create structure
mkdir -p "$PROJECT_DIR"/{src,test,dev/repl,scripts/hooks,skills,.clj-kondo}

# 2. Copy template files
cp templates/bb.edn "$PROJECT_DIR/"
cp templates/deps.edn "$PROJECT_DIR/"
cp templates/.clj-kondo/config.edn "$PROJECT_DIR/.clj-kondo/"
cp -r templates/scripts/* "$PROJECT_DIR/scripts/"
cp templates/dev/repl/init.cljc "$PROJECT_DIR/dev/repl/"

# 3. Initialize with deps-new
cd "$PROJECT_DIR"
clojure -Tnew create :template app :name "$PROJECT_NAME"

# 4. Install tools
npm init -y
npm install --save-dev shadow-cljs concurrently

# 5. Setup
bb install-hooks
bb index

echo "✓ Project created: $PROJECT_DIR"
echo "Next: cd $PROJECT_DIR && npm run dev"
```

---

## V. AUTOMATION OPPORTUNITIES

### Always Automatable

1. **Dependency sync validation**
   - `scripts/check-version-sync.sh` validates `deps.edn` ↔ `shadow-cljs.edn`
   - Run in pre-commit hook

2. **Semantic search indexing**
   - `bb index` rebuilds embeddings
   - Run weekly or post-merge

3. **Cache cleanup**
   - `bb clean` removes stale build artifacts
   - Run on CI/CD or manually

4. **Quality gates**
   - `bb lint check test` - pre-commit
   - Fails fast on CI

### Project-Specific Automation

1. **Database migrations** (if applicable)
2. **Asset processing** (CSS, images)
3. **API client generation**
4. **Documentation generation** (auto-overview from `src/`)

---

## VI. DECISION MATRIX

| Component | Templatable? | Tool | Customization Level |
|-----------|--------------|------|---------------------|
| `deps.edn` aliases | ✅ Yes | Copy + neil | 10% - just add deps |
| `bb.edn` tasks | ✅ Yes | Copy as-is | 5% - add custom tasks |
| `.clj-kondo/config.edn` | ✅ Yes | Copy as-is | 10% - module boundaries |
| `shadow-cljs.edn` structure | ⚠️ Partial | Template | 40% - build targets |
| REPL helpers | ⚠️ Partial | Template | 30% - domain helpers |
| Git hooks | ✅ Yes | Copy as-is | 0% - universal |
| Skills infrastructure | ✅ Yes | Copy pattern | Per skill varies |
| `.mcp.json` | ⚠️ Partial | Template | 20% - add servers |
| Test fixtures | ❌ No | Manual | 100% - domain-specific |
| Domain dependencies | ❌ No | neil | 100% - research required |
| Build targets | ❌ No | Manual | 100% - architecture-driven |

**Legend:**
- ✅ Yes: Copy with minimal changes (<15%)
- ⚠️ Partial: Template + significant customization (15-50%)
- ❌ No: Always manual (>50%)

---

## VII. RECOMMENDED WORKFLOW

### New Project Checklist

**Phase 1: Bootstrap (5 min)**
```bash
# 1. Create from template (deps-new or script)
clojure -Tnew create :template my-org/evo-template :name com.example/my-app

# 2. Install Node deps
npm install

# 3. Setup git hooks
bb install-hooks
```

**Phase 2: Domain Configuration (30 min)**
```bash
# 4. Add domain dependencies
neil dep add :lib metosin/malli
neil dep add :lib datascript/datascript

# 5. Sync to shadow-cljs.edn (manual)
# Copy versions from deps.edn

# 6. Validate sync
bb check-deps-sync
```

**Phase 3: Build Configuration (15 min)**
```bash
# 7. Define shadow-cljs builds
# Edit shadow-cljs.edn :builds

# 8. Test compilation
bb check

# 9. Build semantic index
bb index
```

**Phase 4: Custom Skills (varies)**
```bash
# 10. Add project-specific skills
mkdir skills/my-skill
cp -r templates/skills/template/* skills/my-skill/
# Customize SKILL.md and run.sh
```

**Total setup time:** ~1 hour for basic project

---

## VIII. TEMPLATE REPOSITORY STRUCTURE

**Recommended layout for `evo-template` repo:**

```
evo-template/
├── template.edn                    # deps-new template config
├── root/                           # Template root
│   ├── bb.edn                      # ✅ Universal
│   ├── deps.edn                    # ✅ Template with placeholders
│   ├── .clj-kondo/
│   │   └── config.edn              # ✅ Universal
│   ├── scripts/
│   │   ├── hooks/
│   │   │   ├── pre-commit          # ✅ Universal
│   │   │   └── pre-push            # ✅ Universal
│   │   ├── install-hooks.sh        # ✅ Universal
│   │   └── check-version-sync.sh   # ✅ Universal
│   ├── dev/
│   │   └── repl/
│   │       └── init.cljc           # ⚠️ Template + customize
│   ├── skills/
│   │   └── README.md               # ✅ Universal docs
│   ├── src/{{top/file}}/{{main/file}}/
│   │   └── core.cljc               # ⚠️ Placeholder
│   ├── test/{{top/file}}/{{main/file}}/
│   │   └── core_test.cljc          # ⚠️ Placeholder
│   ├── package.json                # ⚠️ Template
│   ├── shadow-cljs.edn             # ⚠️ Template
│   ├── .gitignore                  # ✅ Universal
│   ├── CLAUDE.md                   # ⚠️ Template + customize
│   └── README.md                   # ⚠️ Placeholder
└── docs/
    └── CUSTOMIZATION.md            # Guide for post-creation
```

---

## IX. KEY INSIGHTS FROM ECOSYSTEM RESEARCH

### 1. deps-new is the Standard (2025)

**Why it matters:**
- Official tooling from Clojure core team
- Declarative templates (template.edn)
- Supports `:transform` rules for file generation
- Installed as Clojure tool (no global install)

**Migration path:** clj-new → deps-new (completed in 2023)

### 2. neil Simplifies Dependency Management

**Capabilities:**
- `neil dep add` - Add deps without editing EDN
- `neil dep upgrade` - Update to latest versions
- `neil dep search` - Find libraries
- `neil new` - Wraps deps-new

**Integration:** Works seamlessly with deps.edn

### 3. Babashka Tasks Replaces Makefiles

**Advantages over make:**
- Clojure syntax
- Cross-platform (Windows, macOS, Linux)
- Access to full babashka ecosystem
- Task dependencies with `run`
- Documentation with `:doc`

**Pattern:** Use `bb` for everything except npm-specific tasks

### 4. Shadow-cljs Conventions

**Standard patterns:**
- `:nrepl {:port 55449}` for REPL
- `:dev-http {8080 "public"}` for dev server
- `:node-test` target for tests
- `:browser` target for SPAs

**Sync requirement:** Deps must match `deps.edn`

### 5. clj-kondo Module Boundaries

**Best practice:**
- Define `:dependencies` in `.clj-kondo/config.edn`
- Enforce layered architecture
- Catch circular dependencies early

---

## X. FUTURE-PROOFING

### Keep Universal

1. **Quality gates** - lint, test, check always relevant
2. **REPL infrastructure** - connect, init, test runners
3. **Cache management** - clean, index patterns
4. **Git hooks** - validation before commit/push
5. **Skill scaffolding** - progressive disclosure pattern

### Expect to Change

1. **Build targets** - Each project has unique needs
2. **Dependencies** - Ecosystem evolves rapidly
3. **Domain skills** - Business logic is unique
4. **Module boundaries** - Architecture-specific
5. **Test strategies** - Varies by project type

### Version Pinning Strategy

**Pin in template:**
- Babashka tasks structure (stable API)
- clj-kondo linter rules (backward compatible)
- Git hook patterns (git stable)

**Don't pin:**
- Library versions (use `neil dep upgrade`)
- Shadow-cljs version (rapid iteration)
- Build configurations (project-specific)

---

## XI. CONCLUSION

**High-value templates (do once, reuse everywhere):**

1. `bb.edn` task definitions (95% reusable)
2. `.clj-kondo/config.edn` linter rules (90% reusable)
3. `scripts/` automation helpers (100% reusable)
4. Git hooks infrastructure (100% reusable)
5. REPL helper patterns (70% reusable)
6. Skill scaffolding structure (80% reusable)

**Always manual (accept the work):**

1. Build target definitions
2. Domain dependency selection
3. Test fixture creation
4. Module boundary definition
5. Project-specific skills

**ROI Analysis:**

- **Template creation effort:** ~4 hours (one-time)
- **New project setup time:**
  - Without template: ~4-6 hours
  - With template: ~1 hour
- **Savings per project:** 3-5 hours
- **Break-even:** 2 projects

**Recommendation:** Create `evo-template` using `deps-new` with above structure. Captures 80% of tooling setup, leaving 20% for domain customization.
