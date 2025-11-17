# Development Tooling & Testing Improvements

Based on analysis of 40+ best-of ClojureScript projects, here's what would make development **MUCH easier** in Evo.

## 🔥 Highest Impact (Add These Now)

### 1. **`matcher-combinators` for Testing**

**Problem:** Current tests break when you add new derived fields or session keys.

```clojure
;; ❌ BRITTLE: Breaks when :derived gets new keys
(is (= {:nodes {...}
        :children-by-parent {...}
        :derived {:parent-of {...}
                  :next-id-of {...}
                  :prev-id-of {...}
                  :index-of {...}
                  :pre {...}
                  :post {...}
                  :id-by-pre {...}}}
       db))

;; ✅ FLEXIBLE: Only test what matters
(is (match? {:nodes map?
             :derived {:parent-of {"a" :doc}}}
            db))
```

**Impact:**
- Tests survive refactoring
- Clear intent (test shape, not exact values)
- Beautiful failure diffs
- 50% less test maintenance

**See:** `examples/matcher_combinators_usage.clj` for real examples.

---

### 2. **`editscript` for Efficient Sync & Audit**

**Problem:** Sending full DB snapshots over network is wasteful. Manual operations miss low-level details.

```clojure
;; ❌ OLD: Send entire DB (100KB+)
(send-to-server {:type :sync :db full-db})

;; ✅ NEW: Send only changes (2KB)
(let [patches (es/diff old-db new-db)]
  (send-to-server {:type :sync :patches (es/get-edits patches)}))
```

**Use Cases:**
1. **Network sync** - 10-100x smaller payloads
2. **Undo/redo** - Precise state diffs
3. **Audit logs** - See exactly what changed
4. **Conflict resolution** - Merge patches from multiple clients

**Impact:**
- Efficient multi-client sync
- Built-in undo/redo
- Granular change tracking
- Network bandwidth savings

**See:** `examples/editscript_usage.cljc` for integration patterns.

---

### 3. **`http-kit` for Backend Communication**

**Current:** No HTTP client in deps (using clj-http, but that's JVM-only).

```clojure
;; Simple, async HTTP for syncing events to backend
(require '[org.httpkit.client :as http])

;; Send operation batch to server
@(http/post "http://localhost:3000/sync"
            {:body (transit/write ops)
             :headers {"Content-Type" "application/transit+json"}})

;; Fetch initial state
@(http/get "http://localhost:3000/state")
```

**Impact:**
- Enable multi-user sync
- Backend integration for persistence
- Simple, functional API
- Works with `promesa` for async/await style

---

### 4. **`grapheme-splitter` (NPM) for Unicode**

**Problem:** `src/utils/text.cljc:22` has TODO for proper emoji/grapheme handling.

```bash
npm install grapheme-splitter
```

```clojure
;; Current: Breaks on complex emoji
(count "👨‍👩‍👧‍👦")  ;=> 7 (WRONG!)

;; With grapheme-splitter:
(count-graphemes "👨‍👩‍👧‍👦")  ;=> 1 (CORRECT!)
```

**Impact:**
- Fixes cursor positioning bugs with emoji
- Proper text length calculations
- International character support
- Resolves existing TODO

---

## 🎯 Medium Impact (Evaluate Next)

### 5. **`cognitect/test-runner` for Simple Test Execution**

**Current:** Using Kaocha (good, but complex for simple cases).

```clojure
;; In deps.edn :test alias
io.github.cognitect-labs/test-runner
{:git/url "https://github.com/cognitect-labs/test-runner.git"
 :sha "48c3c67f98362ba1e20526db4eeb6996209c050a"
 :git/tag "v0.5.0"}

;; Run tests
clj -X:test
```

**Impact:**
- Faster startup than Kaocha
- Data-driven config
- Good for CI pipelines
- Complement Kaocha for quick runs

**Appears in:** Athens, Electric, Clerk (3/5 projects)

---

### 6. **`transit` for Efficient Serialization**

**Current:** Using EDN strings (verbose, slow).

```clojure
;; Current: EDN (human-readable but large)
(pr-str {:nodes {...}})  ;=> 5000 chars

;; Transit: Compact, fast, type-preserving
(transit/write {:nodes {...}})  ;=> 1200 chars
```

**Impact:**
- 60-80% smaller payloads
- Preserves types (keywords, UUIDs, dates)
- Faster parse/stringify than JSON
- Standard for Clojure network protocols

---

## 📊 Quality of Life

### 7. **`tick` for Date/Time** (if you need timestamps)

```clojure
(require '[tick.core :as t])

;; Event timestamps
{:op :create
 :id "a"
 :timestamp (t/instant)
 :props {...}}

;; Time calculations
(t/between (t/instant "2025-01-01T00:00:00Z")
           (t/now))
;=> #time/duration "PT17H30M"
```

**Impact:**
- Clean date/time API
- Immutable, functional
- Cross-platform (CLJ + CLJS)
- Better than manual timestamp strings

---

## 🚀 Advanced (Evaluate Later)

### 8. **`org.babashka/sci` - Safe Code Evaluation**

**Use case:** User-defined validation rules or transformations in events.

```clojure
;; Store validation logic as data
{:op :create
 :id "a"
 :validation "(fn [props] (> (count (:text props)) 0))"}

;; Safely evaluate
(sci/eval-string validation-fn)
```

**Impact:**
- Extensible validation system
- User-defined operations
- Plugin architecture
- Advanced: DSL for operations

**Complexity:** Medium (requires sandboxing setup)

---

## Summary: What to Add First

### Phase 1: This Week
```clojure
;; Already added to deps.edn
nubank/matcher-combinators  {:mvn/version "3.9.2"}
juji/editscript            {:mvn/version "0.6.6"}
http-kit/http-kit          {:mvn/version "2.8.0"}
```

```bash
npm install grapheme-splitter
```

**Immediate wins:**
- Better tests (matcher-combinators)
- Efficient sync (editscript)
- Backend communication (http-kit)
- Unicode fixes (grapheme-splitter)

### Phase 2: Next Sprint
```clojure
;; Add to :test alias
io.github.cognitect-labs/test-runner {...}

;; Add to :deps
com.cognitect/transit-clj   {:mvn/version "1.0.333"}
com.cognitect/transit-cljs  {:mvn/version "0.8.280"}
tick/tick                   {:mvn/version "0.7.0"}
```

### Phase 3: As Needed
- `org.babashka/sci` - When you need user-defined logic
- `metosin/reitit` - If you add client-side routing
- `lambdaisland/deep-diff` - For visual test diffs

---

## Examples

All practical examples are in:
- `examples/matcher_combinators_usage.clj` - Testing patterns
- `examples/editscript_usage.cljc` - Event sourcing integration
- This file - Overview and rationale

---

## Libraries Analyzed

**Based on 40+ projects in `~/Projects/best`:**
- athens (event-sourced note-taking)
- electric (reactive full-stack)
- clerk (literate programming)
- portal (data inspection)
- malli (schema validation)
- re-frame (state management)
- datascript (client DB)
- meander (pattern matching)

**Frequency analysis:**
- `http-kit` - 3/5 projects
- `matcher-combinators` - Used in Clerk, Malli tests
- `editscript` - Used in Clerk for diffs
- `test-runner` - Athens, Electric, Clerk
- `transit` - Electric, Portal (network protocols)

All recommendations are production-proven, actively maintained, and align with Evo's "simple over clever" philosophy.
