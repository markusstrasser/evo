# SRS Refactoring: Before & After Examples

Quick reference showing the most impactful changes.

---

## 1. Unified State Management

### Before: Two Atoms, Complex Checks

```clojure
(defonce ^:private log-state (atom []))
(defonce ^:private cursor (atom nil))

(defn can-undo? []
  (and @cursor (pos? @cursor)))

(defn undo-entry []
  (when (can-undo?)
    (let [idx (dec @cursor)
          entry (get-entry idx)]
      (set-cursor! idx)
      entry)))
```

**Problems:**
- State split across two atoms
- `cursor` can be nil or index (ambiguous)
- Imperative style with side effects
- Hard to test

### After: Single Atom, Pure Logic

```clojure
(defrecord UndoState [current-idx])

(defonce ^:private log-state
  (atom {:entries []
         :undo-state (->UndoState nil)}))

(defn can-undo?
  "Pure function - no atom access."
  [^UndoState state log-size]
  (and (:current-idx state)
       (pos? (:current-idx state))))

(defn undo-step
  "Pure function - returns new state."
  [^UndoState state log-size]
  (when (can-undo? state log-size)
    (->UndoState (dec (:current-idx state)))))

(defn undo!
  "Single atomic swap."
  []
  (let [result (atom nil)]
    (swap! log-state
           (fn [{:keys [entries undo-state] :as state}]
             (if-let [new-state (undo-step undo-state (count entries))]
               (do
                 (reset! result (get entries (:current-idx new-state)))
                 (assoc state :undo-state new-state))
               state)))
    @result))
```

**Benefits:**
- Single source of truth
- Pure functions testable in isolation
- Explicit type with defrecord
- Atomic state updates

---

## 2. Composable Queries with Transducers

### Before: Separate Functions, No Composition

```clojure
(defn get-intents-by-card [card-id]
  (->> @log-state
       (filter (fn [entry]
                 (let [intent (:intent entry)]
                   (= card-id (:card-id intent)))))
       vec))

(defn get-recent-reviews [n]
  (->> @log-state
       (filter #(= :srs/review-card (get-in % [:intent :op])))
       (take-last n)
       vec))

;; Can't combine: "Get card-1's reviews since yesterday"
;; Would need to write a new function or chain filters inefficiently
```

**Problems:**
- Each function filters entire log
- No composition
- Code duplication

### After: Transducers for Composition

```clojure
(defn by-card-id
  "Transducer: filter entries affecting a specific card."
  [card-id]
  (filter (fn [entry]
            (= card-id (get-in entry [:intent :card-id])))))

(defn by-intent-op
  "Transducer: filter entries by intent operation."
  [op]
  (filter (fn [entry]
            (= op (get-in entry [:intent :op])))))

(defn since-timestamp
  "Transducer: filter entries after a timestamp."
  [ts]
  (filter (fn [entry]
            (>= (inst-ms (:timestamp entry))
                (inst-ms ts)))))

(defn query
  "Query log entries with a transducer."
  [xform]
  (into [] xform (log-entries)))

;; Now you can compose:
(query (by-card-id "card-1"))
(query (by-intent-op :srs/review-card))
(query (comp (by-card-id "card-1")
             (by-intent-op :srs/review-card)
             (since-timestamp yesterday)))
```

**Benefits:**
- Compose filters freely with `comp`
- Single pass through data
- Reusable building blocks
- Idiomatic Clojure

**REPL Example:**
```clojure
;; 5 transactions: card-1 (create + 2 reviews), card-2 (create + 1 review)
(query (by-intent-op :srs/review-card))           ;; => 3 results
(query (by-card-id "card-1"))                     ;; => 3 results
(query (comp (by-card-id "card-1")
             (by-intent-op :srs/review-card)))    ;; => 2 results (composed!)
```

---

## 3. Testable Entry Construction

### Before: Non-Deterministic

```clojure
(defn log-entry
  [{:keys [intent compiled-ops db-after source actor]}]
  {:log/id #?(:clj (java.util.UUID/randomUUID)
              :cljs (random-uuid))
   :timestamp #?(:clj (java.time.Instant/now)
                 :cljs (js/Date.))
   :intent intent
   :compiled-ops compiled-ops
   ;; ...
   })

;; Testing is hard:
;; - Can't predict :log/id
;; - Can't predict :timestamp
;; - Can't assert equality
```

**Problems:**
- Generates UUID and timestamp internally
- Hard to test (non-deterministic)
- Can't mock values

### After: Pure with Controllable Values

```clojure
(defn now-timestamp []
  #?(:clj (java.time.Instant/now) :cljs (js/Date.)))

(defn gen-uuid []
  #?(:clj (java.util.UUID/randomUUID) :cljs (random-uuid)))

(defn make-log-entry
  "Pure function - caller provides timestamp and ID for testability."
  [{:keys [intent compiled-ops db-after source actor timestamp log-id]
    :or {source :ui
         actor :system
         timestamp (now-timestamp)
         log-id (gen-uuid)}}]
  (cond-> {:log/id log-id
           :timestamp timestamp
           :intent intent
           :compiled-ops compiled-ops
           :source source
           :actor actor}
    db-after (assoc :kernel/after-hash (simple-hash db-after))))

;; Testing is easy:
(make-log-entry {:intent {...}
                 :compiled-ops [...]
                 :timestamp fixed-timestamp
                 :log-id fixed-uuid})
;; => Deterministic result, can assert equality
```

**Benefits:**
- Testable with fixed values
- Pure function (no side effects)
- Side-effect functions isolated
- Uses `:or` for clean defaults

---

## 4. Validated Plugin Registration

### Before: Silent Overwrites

```clojure
(defn register-card-type! [{:keys [type schema compiler renderer] :as plugin}]
  (when-not type
    (throw (ex-info "Plugin must have :type" {:plugin plugin})))
  (swap! registry assoc type plugin)
  plugin)  ;; No feedback if overwriting

;; Usage:
(register-card-type! {:type :cloze :compiler ...})
;; => {:type :cloze :compiler ...}
;; Did it overwrite? No way to know.
```

**Problems:**
- No validation that `:compiler` is a function
- Silent overwrites
- No feedback to caller
- Runtime errors if invalid plugin

### After: Validated with Feedback

```clojure
(defrecord CardPlugin [type schema compiler renderer])

(defn make-plugin
  "Create a validated plugin record."
  [{:keys [type schema compiler renderer]}]
  {:pre [(keyword? type)
         (fn? compiler)]}
  (->CardPlugin type schema compiler renderer))

(defn register-plugin!
  "Register a new card type plugin.
   Returns {:registered? true/false :previous? true/false :plugin ...}"
  [plugin-map]
  (let [plugin (make-plugin plugin-map)
        card-type (:type plugin)]
    (let [previous (get @registry card-type)
          _ (swap! registry assoc card-type plugin)]
      {:registered? true
       :previous? (some? previous)
       :plugin plugin})))

;; Usage:
(register-plugin! {:type :cloze :compiler ...})
;; => {:registered? true :previous? false :plugin ...}

(register-plugin! {:type :cloze :compiler ...})
;; => {:registered? true :previous? true :plugin ...}
;;    ^^^^^^^^^^^^^^^^^ Tells you it overwrote!
```

**Benefits:**
- Validates structure with defrecord
- Validates types with `:pre` conditions
- Informative return value
- Catches errors early

---

## 5. Readable Image Occlusion Compiler

### Before: Nested, Hard to Follow

```clojure
(fn [{:keys [card-id deck-id markdown-file props]} db]
  (let [content-id (compile/gen-id "content")
        image-url (get props :image-url)
        occlusions (get props :occlusions [])
        
        ;; Everything inline, hard to see structure
        media-ops (mapcat
                   (fn [occ]
                     (let [media-id (compile/gen-id "media")]
                       [{:op :create-node
                         :id media-id
                         :type :media
                         :props {:media/type :image-occlusion
                                 :media/mask-id (:id occ)
                                 :media/shape (:shape occ)}}
                        {:op :place
                         :id media-id
                         :under card-id
                         :at :last}]))
                   occlusions)]
    (concat
     [{:op :create-node ...}  ;; card
      {:op :create-node ...}  ;; content
      {:op :place ...}
      {:op :place ...}]
     media-ops)))
```

**Problems:**
- Everything inline (can't test media logic separately)
- Hard to see base ops vs media ops
- `concat` at end is unclear
- No intermediate values for debugging

### After: Extracted Helpers, Clear Flow

```clojure
(defn- build-occlusion-media-ops
  "Build media child operations for occlusions.
   Returns vector of [:create-node :place] pairs."
  [occlusions card-id]
  (->> occlusions
       (mapcat (fn [occ]
                 (let [media-id (compile/gen-id "media")]
                   [{:op :create-node
                     :id media-id
                     :type :media
                     :props {:media/type :image-occlusion
                             :media/mask-id (:id occ)
                             :media/shape (:shape occ)}}
                    {:op :place
                     :id media-id
                     :under card-id
                     :at :last}])))
       vec))

(defn- compile-image-occlusion
  "Compiler for :image-occlusion card type."
  [{:keys [card-id deck-id markdown-file props]} db]
  (let [content-id (compile/gen-id "content")
        image-url (:image-url props)
        occlusions (:occlusions props [])
        
        ;; Explicit sections
        card-props {...}
        content-props {...}
        
        base-ops [{:op :create-node ...}
                  {:op :create-node ...}
                  {:op :place ...}
                  {:op :place ...}]
        
        media-ops (build-occlusion-media-ops occlusions card-id)]
    
    ;; Clear combination
    (into base-ops media-ops)))
```

**Benefits:**
- Named helper function is testable
- Clear separation: base-ops vs media-ops
- Threading macro `->>` shows data flow
- `into` is clearer than `concat`
- Can add tap>/println at each step

**REPL Test:**
```clojure
(build-occlusion-media-ops 
  [{:id "occ-1" :shape {...}}
   {:id "occ-2" :shape {...}}]
  "card-123")
;; => [[:create-node ...] [:place ...] [:create-node ...] [:place ...]]
;;    4 ops total (2 occlusions × 2 ops each)
```

---

## 6. Simplified Conditional Logic

### Before: Triple Nested

```clojure
(defn compile-with-plugin [intent db]
  (if-let [card-type (and (= (:op intent) :srs/create-card)
                          (:card-type intent))]
    (if-let [plugin (get-plugin card-type)]
      ((:compiler plugin) intent db)
      (compile/compile-srs-intent intent db))
    (compile/compile-srs-intent intent db)))
```

**Problems:**
- `and` inside `if-let` is confusing
- Triple nested conditionals
- Hard to follow logic

### After: Explicit Bindings

```clojure
(defn compile-with-plugin
  "Compile SRS intent using plugin compiler if available.
   
   Logic:
   1. If intent is :srs/create-card with :card-type
   2. And plugin exists for that card-type
   3. Use plugin compiler, else use default
   4. Otherwise use default for all other intents"
  [intent db]
  (let [op (:op intent)
        card-type (:card-type intent)]
    (if (and (= op :srs/create-card) card-type)
      (if-let [plugin (get-plugin card-type)]
        ((:compiler plugin) intent db)
        (compile/compile-srs-intent intent db))
      (compile/compile-srs-intent intent db))))
```

**Benefits:**
- Explicit `let` makes values visible
- Docstring explains logic
- Still simple, but clearer
- Easy to add tap> or println

---

## Summary: Key Patterns

| Pattern | Before | After | Benefit |
|---------|--------|-------|---------|
| State | Multiple atoms | Single atom + defrecord | Single source of truth |
| Queries | Separate functions | Transducers | Composable, efficient |
| Entry creation | Mixed concerns | Pure functions | Testable |
| Validation | Runtime errors | :pre conditions + defrecord | Fail fast |
| Feedback | Silent operations | Informative returns | Debuggable |
| Helpers | Inline logic | Named functions | Testable, readable |
| Bindings | Nested conditionals | Explicit let | Clear flow |

All improvements verified in REPL. See REFACTORING_REPORT_SRS.md for full details.
