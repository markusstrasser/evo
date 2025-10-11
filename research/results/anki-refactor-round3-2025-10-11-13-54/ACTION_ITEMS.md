# Action Items: Apply 8 Clear Wins

## Priority 1: Correctness Fixes

### 1. Remove redundant render! calls (ui.cljs)

**Lines to change**: 135, 140, 157

**Current**:
```clojure
(swap! !state assoc ...)
(render!))  ;; REMOVE THIS - watch on line 173 already renders
```

**After**:
```clojure
(swap! !state assoc ...))  ;; No explicit render! needed
```

**Why**: Every state change currently renders 2x (explicit + watch). The watch is sufficient.

**Files modified**: ui.cljs (-3 lines)

---

### 2. Fix IndexedDB write completion (fs.cljs)

**Requires**: IDB helper (see #4)

**Lines**: 170-179

**Current**:
```clojure
(defn save-dir-handle [handle]
  (p/let [db (open-db)
          tx (.transaction db #js [store-name] "readwrite")
          store (.objectStore tx store-name)
          _ (.put store handle "dir-handle")]  ;; NOT WAITING
    (js/console.log "Directory handle saved")
    true))
```

**After**:
```clojure
(defn save-dir-handle [handle]
  (p/let [db (open-db)
          tx (.transaction db #js [store-name] "readwrite")
          store (.objectStore tx store-name)
          _ (idb-request->promise (.put store handle "dir-handle"))]  ;; NOW WAITING
    (js/console.log "Directory handle saved")
    true))
```

**Why**: Currently logs "saved" before actually saved. Race condition.

**Files modified**: fs.cljs (no LOC change once helper exists)

---

### 3. Fix flatten bug (fs.cljs)

**Lines**: 119-122

**Current**:
```clojure
(p/resolved (->> results
                 flatten  ;; BUG: tears apart maps
                 (remove nil?)
                 vec))
```

**After**:
```clojure
(p/resolved (->> results
                 (keep identity)  ;; More idiomatic than (remove nil?)
                 (into [] cat)))  ;; Correct: flatten one level only
```

**Why**: `flatten` recursively flattens, breaking nested maps. `(into [] cat)` flattens one level correctly.

**Files modified**: fs.cljs (neutral LOC)

---

## Priority 2: Code Quality

### 4. Extract idb-request->promise helper (fs.cljs)

**Add before line 145** (before open-db):

```clojure
(defn- idb-request->promise
  "Convert IndexedDB request to promise"
  [request]
  (p/create (fn [resolve reject]
              (set! (.-onsuccess request) #(resolve (.. % -target -result)))
              (set! (.-onerror request)   #(reject  (.. % -target -error))))))
```

**Then refactor open-db (lines 145-168)**:

**Current**:
```clojure
(defn- open-db []
  (p/create
   (fn [resolve reject]
     (let [request (.open js/indexedDB db-name db-version)]
       (set! (.-onupgradeneeded request) ...)
       (set! (.-onsuccess request)
             (fn [e] (resolve (-> e .-target .-result))))
       (set! (.-onerror request)
             (fn [e] (reject (-> e .-target .-error))))))))
```

**After**:
```clojure
(defn- open-db []
  (let [request (.open js/indexedDB db-name db-version)]
    (set! (.-onupgradeneeded request)
          (fn [e]
            (js/console.log "Upgrading IndexedDB...")
            (let [db (-> e .-target .-result)]
              (when-not (.contains (.-objectStoreNames db) store-name)
                (js/console.log "Creating object store:" store-name)
                (.createObjectStore db store-name)))))
    (idb-request->promise request)))
```

**Also refactor load-dir-handle (lines 189-203)**:

**Current**:
```clojure
(p/create
 (fn [resolve reject]
   (let [request (.get store "dir-handle")]
     (set! (.-onsuccess request)
           (fn [e]
             (let [result (-> e .-target .-result)]
               (if result
                 (resolve result)
                 (resolve nil)))))
     (set! (.-onerror request)
           (fn [e] (resolve nil))))))
```

**After**:
```clojure
(p/recover (idb-request->promise (.get store "dir-handle"))
           (constantly nil))
```

**Why**: Eliminates ~18 LOC of duplication. Centralizes IDB request handling.

**Files modified**: fs.cljs (-12 LOC)

---

### 5. Use medley/filter-vals in due-cards (core.cljc)

**First, add medley to ns** (line 3):

```clojure
(ns lab.anki.core
  "Core Anki clone data structures and operations"
  (:require [clojure.string :as str]
            [medley.core :as m]))
```

**Then refactor due-cards (lines 169-177)**:

**Current**:
```clojure
(defn due-cards [state]
  (let [now (now-ms)]
    (->> (:meta state)
         (filter (fn [[_hash card-meta]]
                   (<= (.getTime (:due-at card-meta)) now)))
         (map first)
         vec)))
```

**After**:
```clojure
(defn due-cards [state]
  (let [now (now-ms)]
    (-> (:meta state)
        (m/filter-vals #(<= (.getTime (:due-at %)) now))
        keys
        vec)))
```

**Why**: More idiomatic. `filter-vals` directly expresses intent. `keys` clearer than `(map first)`.

**Files modified**: core.cljc (-1 LOC)

---

### 6. Tighten destructuring in ::rate-card (ui.cljs)

**Lines**: 142-147

**Current**:
```clojure
(let [rating (first args)
      state (:state @!state)
      dir-handle (:dir-handle @!state)
      due (core/due-cards state)
      current-hash (first due)]
  ...)
```

**After**:
```clojure
(let [{:keys [state dir-handle]} @!state
      rating (first args)
      due (core/due-cards state)
      current-hash (first due)]
  ...)
```

**Why**: Destructure once instead of multiple lookups. More idiomatic.

**Files modified**: ui.cljs (neutral LOC)

---

### 7. Separate async from pure in load-and-sync-cards! (ui.cljs)

**Lines**: 100-121

**Current**:
```clojure
(p/let [markdown (fs/load-cards dir-handle)
        lines (str/split-lines markdown)          ;; PURE
        parsed-cards (keep core/parse-card lines) ;; PURE
        events (fs/load-log dir-handle)
        current-state (core/reduce-events events) ;; PURE
        existing-hashes (set (keys (:cards current-state))) ;; PURE
        new-cards (remove #(contains? existing-hashes (core/card-hash %)) parsed-cards) ;; PURE
        new-events (mapv ... new-cards)] ;; PURE
  ...)
```

**After**:
```clojure
(p/let [markdown (fs/load-cards dir-handle)
        events   (fs/load-log dir-handle)]
  (let [lines (str/split-lines markdown)
        parsed-cards (keep core/parse-card lines)
        current-state (core/reduce-events events)
        existing-hashes (set (keys (:cards current-state)))
        new-cards (remove #(contains? existing-hashes (core/card-hash %)) parsed-cards)
        new-events (mapv #(core/card-created-event (core/card-hash %) %) new-cards)]
    ...))
```

**Why**: Makes 2 I/O operations explicit. Separates async from pure transformations.

**Files modified**: ui.cljs (+2 LOC for readability)

---

### 8. Remove unused :log [] from reduce-events (core.cljc)

**Lines**: 158-165

**Current**:
```clojure
(defn reduce-events [events]
  (reduce apply-event
          {:cards {}
           :meta {}
           :log []}  ;; UNUSED - REMOVE
          events))
```

**After**:
```clojure
(defn reduce-events [events]
  (reduce apply-event
          {:cards {}
           :meta {}}
          events))
```

**Why**: Dead code. `:log` is never used anywhere.

**Files modified**: core.cljc (-1 LOC)

---

## Summary

**Files to modify**: 3 (core.cljc, fs.cljs, ui.cljs)

**Changes**:
- core.cljc: 3 changes (-2 LOC)
- fs.cljs: 3 changes (-12 LOC)
- ui.cljs: 3 changes (+2 LOC)

**Net impact**: -12 LOC (579 → 567)

**Time estimate**: ~15 minutes

**Testing**: Run `npm test` after each change to verify no regressions.

---

## Implementation Order

1. ✅ Remove :log [] (30 sec, no dependencies)
2. ✅ Remove render! calls (1 min, no dependencies)
3. ✅ Extract IDB helper (5 min, needed for #4)
4. ✅ Fix IDB write completion (1 min, depends on #3)
5. ✅ Fix flatten bug (2 min, no dependencies)
6. ✅ Add medley + filter-vals (3 min, no dependencies)
7. ✅ Tighten destructuring (2 min, no dependencies)
8. ✅ Separate async/pure (3 min, no dependencies)

**Total**: ~17 minutes
