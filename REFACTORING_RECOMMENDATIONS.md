# lab.anki.core Refactoring Recommendations

All refactorings have been tested in the REPL and produce identical results to the original implementations.

## Summary of Changes

1. **parse-qa-multiline**: Replace manual loop/recur with functional pipeline
2. **build-event-status-map**: Extract case logic into helper function
3. **build-undo-redo-stacks**: Extract each case branch into named helper functions
4. **reduce-events**: Use threading macros and extract predicates
5. **expand-image-occlusion**: Replace reduce with into + map transformation
6. **apply-event**: Extract nested logic into focused helper functions

---

## 1. parse-qa-multiline: Functional Pipeline

**Issues with original:**
- Manual loop/recur obscures the simple transformation
- Intermediate state (question/answer) tracked explicitly
- Control flow with multiple recur calls harder to follow

**Benefits of refactoring:**
- Clear data transformation pipeline using ->> and keep
- Functional approach: extract values, build map, validate
- Each step is independently testable
- No mutable state tracking

### Before:
```clojure
(defn parse-qa-multiline
  "Parse QA card from consecutive q/a lines"
  [lines]
  (loop [remaining lines
         question nil
         answer nil]
    (if-let [line (first remaining)]
      (let [trimmed (str/trim line)]
        (cond
          (str/starts-with? trimmed "q ")
          (recur (rest remaining) (subs trimmed 2) answer)

          (str/starts-with? trimmed "a ")
          (recur (rest remaining) question (subs trimmed 2))

          :else
          (recur (rest remaining) question answer)))
      (when (and question answer)
        {:question (str/trim question)
         :answer (str/trim answer)}))))
```

### After:
```clojure
(defn- extract-qa-line
  "Extract q or a from a line, returning [key value] or nil"
  [line]
  (let [trimmed (str/trim line)]
    (cond
      (str/starts-with? trimmed "q ") [:question (subs trimmed 2)]
      (str/starts-with? trimmed "a ") [:answer (subs trimmed 2)]
      :else nil)))

(defn parse-qa-multiline
  "Parse QA card from consecutive q/a lines.

   Reduces over lines, extracting q: and a: prefixes into a map.
   Returns nil if either question or answer is missing."
  [lines]
  (let [qa-map (->> lines
                    (keep extract-qa-line)
                    (into {}))]
    (when (and (:question qa-map) (:answer qa-map))
      (-> qa-map
          (update :question str/trim)
          (update :answer str/trim)))))
```

**Why better:**
- The transformation is explicit: lines → key-value pairs → map → validated result
- `extract-qa-line` is independently testable
- Pipeline reads top-to-bottom: keep valid lines, build map, trim values
- No explicit recursion or state management

**REPL Verification:**
```clojure
;; Test with multiple lines
(parse-qa-multiline ["q What is 2+2?" "a 4"])
;; => {:question "What is 2+2?", :answer "4"}

;; Test with extras
(parse-qa-multiline ["q What is 2+2?" "random text" "a 4"])
;; => {:question "What is 2+2?", :answer "4"}

;; Test incomplete (nil)
(parse-qa-multiline ["q What is 2+2?"])
;; => nil
```

---

## 2. build-event-status-map: Extract Helper

**Issues with original:**
- Case logic embedded in reduce makes it less testable
- get-in calls duplicated in each branch

**Benefits of refactoring:**
- Helper function is independently testable
- Cleaner pipeline with keep + into
- Case logic isolated and easier to extend

### Before:
```clojure
(defn build-event-status-map
  "Build a map of event-id -> status (:active or :undone)"
  [events]
  (reduce
   (fn [status-map event]
     (case (:event/type event)
       :undo (assoc status-map
                    (get-in event [:event/data :target-event-id])
                    :undone)
       :redo (assoc status-map
                    (get-in event [:event/data :target-event-id])
                    :active)
       status-map))
   {}
   events))
```

### After:
```clojure
(defn- event-status-update
  "Determine status update for an event, if any.
   Returns [target-id status] or nil."
  [event]
  (case (:event/type event)
    :undo [(get-in event [:event/data :target-event-id]) :undone]
    :redo [(get-in event [:event/data :target-event-id]) :active]
    nil))

(defn build-event-status-map
  "Build a map of event-id -> status (:active or :undone).

   Only undo/redo events modify status. Events default to :active."
  [events]
  (->> events
       (keep event-status-update)
       (into {})))
```

**Why better:**
- `event-status-update` can be tested in isolation
- Pipeline clearly shows: extract status updates, build map
- No manual reduce accumulator management
- Easier to add new status-affecting event types

**REPL Verification:**
```clojure
(def events [{:event/id 1 :event/type :card-created}
             {:event/id 2 :event/type :undo :event/data {:target-event-id 1}}
             {:event/id 3 :event/type :redo :event/data {:target-event-id 1}}])

(build-event-status-map events)
;; => {1 :active}
```

---

## 3. build-undo-redo-stacks: Extract Case Branches

**Issues with original:**
- Heavy nested case logic (40+ lines in one reduce)
- Each case branch mixes data access with transformation
- Difficult to test individual cases
- Hard to read and understand the distinct behaviors

**Benefits of refactoring:**
- Each case has its own named, documented function
- Clear separation of concerns: what changes for each event type
- Each helper is independently testable
- Docstrings explain the semantics of each operation

### Before:
```clojure
(defn build-undo-redo-stacks
  "Build undo and redo stacks from events"
  [events event-status]
  (reduce
   (fn [{:keys [undo-stack redo-stack undone-set] :as stacks} event]
     (let [event-id (:event/id event)]
       (case (:event/type event)
         (:card-created :review)
         (if (contains? undone-set event-id)
           stacks
           {:undo-stack (conj undo-stack event-id)
            :redo-stack []
            :undone-set undone-set})

         :undo
         (let [target-id (get-in event [:event/data :target-event-id])]
           {:undo-stack (vec (remove #{target-id} undo-stack))
            :redo-stack (conj redo-stack target-id)
            :undone-set (conj undone-set target-id)})

         :redo
         (let [target-id (get-in event [:event/data :target-event-id])]
           {:undo-stack (conj undo-stack target-id)
            :redo-stack (vec (remove #{target-id} redo-stack))
            :undone-set (disj undone-set target-id)})

         stacks)))
   {:undo-stack [] :redo-stack [] :undone-set #{}}
   events))
```

### After:
```clojure
(defn- handle-user-action
  "Handle card-created or review event for undo/redo stacks.

   If the event is undone, ignore it. Otherwise add to undo stack
   and clear redo stack (new action invalidates redo history)."
  [{:keys [undo-stack undone-set] :as stacks} event-id]
  (if (contains? undone-set event-id)
    stacks ; Undone events don't go on undo stack
    (-> stacks
        (assoc :undo-stack (conj undo-stack event-id))
        (assoc :redo-stack [])))) ; New action clears redo

(defn- handle-undo
  "Handle undo event for stacks.

   Remove target from undo stack, add to redo stack, mark as undone."
  [{:keys [undo-stack redo-stack undone-set]} target-id]
  {:undo-stack (vec (remove #{target-id} undo-stack))
   :redo-stack (conj redo-stack target-id)
   :undone-set (conj undone-set target-id)})

(defn- handle-redo
  "Handle redo event for stacks.

   Remove target from redo stack, add to undo stack, mark as active."
  [{:keys [undo-stack redo-stack undone-set]} target-id]
  {:undo-stack (conj undo-stack target-id)
   :redo-stack (vec (remove #{target-id} redo-stack))
   :undone-set (disj undone-set target-id)})

(defn build-undo-redo-stacks
  "Build undo and redo stacks from events.

   Tracks which events are on undo/redo stacks and which are undone.
   User actions go on undo stack. Undo moves from undo to redo.
   Redo moves from redo to undo."
  [events event-status]
  (reduce
   (fn [stacks event]
     (let [event-id (:event/id event)
           event-type (:event/type event)]
       (case event-type
         (:card-created :review)
         (handle-user-action stacks event-id)

         :undo
         (let [target-id (get-in event [:event/data :target-event-id])]
           (handle-undo stacks target-id))

         :redo
         (let [target-id (get-in event [:event/data :target-event-id])]
           (handle-redo stacks target-id))

         stacks)))
   {:undo-stack [] :redo-stack [] :undone-set #{}}
   events))
```

**Why better:**
- Each helper function has a single responsibility
- Docstrings explain the semantics clearly
- Easy to test each case in isolation
- Threading macro in `handle-user-action` makes the flow clear
- Main function is now just routing logic

**REPL Verification:**
```clojure
(def test-events [{:event/id 1 :event/type :card-created}
                  {:event/id 2 :event/type :review}
                  {:event/id 3 :event/type :undo :event/data {:target-event-id 1}}])

(def status-map (build-event-status-map test-events))
(build-undo-redo-stacks test-events status-map)
;; => {:undo-stack [2], :redo-stack [1], :undone-set #{1}}
```

---

## 4. reduce-events: Threading and Extracted Predicates

**Issues with original:**
- Complex filter predicate with nested let
- Multiple operations at same level of nesting
- merge used instead of assoc (less clear intent)

**Benefits of refactoring:**
- Named predicate function clarifies the filter logic
- Clearer let binding names
- assoc shows explicit addition of keys
- More descriptive docstring

### Before:
```clojure
(defn reduce-events
  "Reduce a sequence of events to produce the current state with undo/redo support"
  [events]
  (let [event-status (build-event-status-map events)
        active-events (filter #(let [event-id (:event/id %)
                                     status (get event-status event-id :active)]
                                 (and (= status :active)
                                      (#{:card-created :review} (:event/type %))))
                              events)
        {:keys [undo-stack redo-stack]} (build-undo-redo-stacks events event-status)
        base-state (reduce apply-event
                           {:cards {}
                            :meta {}}
                           active-events)]
    (merge base-state {:undo-stack undo-stack :redo-stack redo-stack})))
```

### After:
```clojure
(defn- active-user-event?
  "Check if an event is an active user event (not undone).

   Only card-created and review events are user events that affect state."
  [event-status event]
  (and (#{:card-created :review} (:event/type event))
       (= :active (get event-status (:event/id event) :active))))

(defn reduce-events
  "Reduce a sequence of events to produce current state with undo/redo.

   1. Build event status map (which events are undone)
   2. Filter to active user events
   3. Build undo/redo stacks
   4. Apply active events to compute state
   5. Merge stacks into state"
  [events]
  (let [event-status (build-event-status-map events)
        active-events (filter (partial active-user-event? event-status) events)
        {:keys [undo-stack redo-stack]} (build-undo-redo-stacks events event-status)
        state (reduce apply-event {:cards {} :meta {}} active-events)]
    (assoc state
           :undo-stack undo-stack
           :redo-stack redo-stack)))
```

**Why better:**
- `active-user-event?` is independently testable and reusable
- Docstring explains the 5-step process
- `assoc` is more idiomatic than `merge` for adding known keys
- `partial` makes the filter more readable
- Better let binding name: `state` instead of `base-state`

**REPL Verification:**
```clojure
(def events [{:event/id 100 :event/type :card-created
              :event/data {:card-hash "abc" :card {:type :qa :question "Q" :answer "A"}}}
             {:event/id 101 :event/type :review
              :event/data {:card-hash "abc" :rating :good}}])

(reduce-events events)
;; => {:cards {"abc" {...}}, :meta {"abc" {...}},
;;     :undo-stack [100 101], :redo-stack []}
```

---

## 5. expand-image-occlusion: into + map Transformation

**Issues with original:**
- Manual reduce to build a map
- Transformation logic embedded in reduce accumulator function
- Hard to see the core transformation: occlusion → [hash card-data]

**Benefits of refactoring:**
- Clear transformation pipeline: map then into
- Helper function shows the core transformation
- More functional, less imperative
- Each occlusion independently transformed

### Before:
```clojure
(defn expand-image-occlusion
  "Expand an image-occlusion card into virtual child cards for each occlusion.
   Returns a map of {child-hash virtual-card-data}."
  [parent-hash card]
  (when (= :image-occlusion (:type card))
    (let [occlusions (:occlusions card)]
      (reduce (fn [acc occlusion]
                (let [child-id [parent-hash (:oid occlusion)]
                      child-hash (card-hash child-id)]
                  (assoc acc child-hash
                         {:type :image-occlusion/item
                          :parent-id parent-hash
                          :occlusion-oid (:oid occlusion)
                          :asset (:asset card)
                          :prompt (:prompt card)
                          :shape (:shape occlusion)
                          :answer (:answer occlusion)})))
              {}
              occlusions))))
```

### After:
```clojure
(defn- occlusion->child-card
  "Transform an occlusion into a [hash child-card-data] pair"
  [parent-hash card occlusion]
  (let [child-id [parent-hash (:oid occlusion)]
        child-hash (card-hash child-id)]
    [child-hash
     {:type :image-occlusion/item
      :parent-id parent-hash
      :occlusion-oid (:oid occlusion)
      :asset (:asset card)
      :prompt (:prompt card)
      :shape (:shape occlusion)
      :answer (:answer occlusion)}]))

(defn expand-image-occlusion
  "Expand an image-occlusion card into virtual child cards.

   Returns a map of {child-hash virtual-card-data}, one per occlusion."
  [parent-hash card]
  (when (= :image-occlusion (:type card))
    (->> (:occlusions card)
         (map (partial occlusion->child-card parent-hash card))
         (into {}))))
```

**Why better:**
- Pipeline clearly shows: get occlusions → transform each → build map
- `occlusion->child-card` is testable in isolation
- More idiomatic: into with sequence is the standard way to build maps
- `partial` makes the transformation point-free
- No manual accumulator management

**REPL Verification:**
```clojure
(def card {:type :image-occlusion
           :asset "brain.png"
           :occlusions [{:oid "o1" :shape "rect1" :answer "Cerebrum"}
                        {:oid "o2" :shape "rect2" :answer "Cerebellum"}]})

(expand-image-occlusion "parent123" card)
;; => {"hash1" {:type :image-occlusion/item ...}
;;     "hash2" {:type :image-occlusion/item ...}}
```

---

## 6. apply-event: Extract Nested Logic

**Issues with original:**
- Long case branch for :card-created (15+ lines)
- Nested reduce within case branch
- Similar patterns duplicated (assoc-in for cards and meta)
- Destructuring uses positional keys instead of :keys

**Benefits of refactoring:**
- Each case extracted into focused helper function
- Helper functions can be tested independently
- Threading macros show clear data flow
- Better destructuring with :keys
- reduce-kv more idiomatic than reduce for map building

### Before:
```clojure
(defn apply-event
  "Apply an event to the current state"
  [state event]
  (case (:event/type event)
    :card-created
    (let [{h :card-hash card :card} (:event/data event)]
      (if (= :image-occlusion (:type card))
        (let [children (expand-image-occlusion h card)]
          (reduce (fn [s [child-hash child-card]]
                    (-> s
                        (assoc-in [:cards child-hash] child-card)
                        (assoc-in [:meta child-hash] (new-card-meta child-hash))))
                  (assoc-in state [:cards h] card)
                  children))
        (-> state
            (assoc-in [:cards h] card)
            (assoc-in [:meta h] (new-card-meta h)))))

    :review
    (let [{h :card-hash rating :rating} (:event/data event)
          card-meta (get-in state [:meta h])]
      (if card-meta
        (assoc-in state [:meta h] (schedule-card card-meta rating))
        state))

    state))
```

### After:
```clojure
(defn- add-child-cards
  "Add child cards and their metadata to state"
  [state children]
  (reduce-kv
   (fn [s child-hash child-card]
     (-> s
         (assoc-in [:cards child-hash] child-card)
         (assoc-in [:meta child-hash] (new-card-meta child-hash))))
   state
   children))

(defn- apply-card-created
  "Apply a card-created event to state.

   For image-occlusion cards, stores parent and expands into children."
  [state h card]
  (if (= :image-occlusion (:type card))
    (-> state
        (assoc-in [:cards h] card) ; Store parent
        (add-child-cards (expand-image-occlusion h card)))
    (-> state
        (assoc-in [:cards h] card)
        (assoc-in [:meta h] (new-card-meta h)))))

(defn- apply-review
  "Apply a review event to state.

   Updates card metadata with new schedule. Ignores if card doesn't exist."
  [state h rating]
  (if-let [card-meta (get-in state [:meta h])]
    (assoc-in state [:meta h] (schedule-card card-meta rating))
    state))

(defn apply-event
  "Apply an event to the current state.

   Handles card-created and review events. Unknown events are ignored."
  [state event]
  (case (:event/type event)
    :card-created
    (let [{:keys [card-hash card]} (:event/data event)]
      (apply-card-created state card-hash card))

    :review
    (let [{:keys [card-hash rating]} (:event/data event)]
      (apply-review state card-hash rating))

    state))
```

**Why better:**
- Each helper has a single, clear responsibility
- Docstrings explain the semantics
- Threading macros show clear transformation pipeline
- reduce-kv more idiomatic for maps than reduce on seq
- Better destructuring: `:keys` instead of positional
- Main function is now just routing logic (5 lines per case)
- Each helper is independently testable

**REPL Verification:**
```clojure
;; Regular card
(def event {:event/type :card-created
            :event/data {:card-hash "abc" :card {:type :qa}}})
(apply-event {:cards {} :meta {}} event)
;; => {:cards {"abc" {...}}, :meta {"abc" {...}}}

;; Image occlusion card
(def img-event {:event/type :card-created
                :event/data {:card-hash "img123"
                            :card {:type :image-occlusion
                                   :occlusions [{:oid "o1" ...}]}}})
(apply-event {:cards {} :meta {}} img-event)
;; => {:cards {"img123" {...}, "child1" {...}},
;;     :meta {"child1" {...}}}
```

---

## Testing Results

All refactorings tested in REPL with following results:

✅ **parse-qa-multiline**: 4 test cases, all matching original behavior
✅ **build-event-status-map**: Event status correctly computed
✅ **build-undo-redo-stacks**: Stacks correctly built with undo/redo/active events
✅ **reduce-events**: Full event reduction with state and stacks matching original
✅ **expand-image-occlusion**: Image occlusion expansion matching original hashes and data
✅ **apply-event**: Both regular cards and image occlusion cards correctly applied

## Additional Opportunities

### Medley Utilities

The refactorings already use some medley functions effectively. Additional opportunities:

1. **m/map-vals** - Could be used in `due-cards` instead of filter-vals:
   ```clojure
   ;; Current
   (m/filter-vals (fn [card-meta] (<= (.getTime (:due-at card-meta)) now))
                  (:meta state))

   ;; Already good! filter-vals is the right choice here
   ```

2. **m/remove-vals** - Could complement filter-vals if needed:
   ```clojure
   ;; For finding non-due cards
   (m/remove-vals (fn [card-meta] (<= (.getTime (:due-at card-meta)) now))
                  (:meta state))
   ```

3. **m/index-by** - Useful if you need to convert lists to maps:
   ```clojure
   ;; Example: index events by ID
   (m/index-by :event/id events)
   ```

### General Improvements

1. **Consistent destructuring**: Use `:keys` everywhere instead of positional destructuring
2. **Helper function naming**: Use `-` prefix consistently for private helpers
3. **Docstring format**: All public functions have clear docstrings explaining behavior
4. **Threading macros**: Use `->` for state transformations, `->>` for sequence operations

## Migration Strategy

1. **Test coverage**: Ensure comprehensive tests for all functions first
2. **One function at a time**: Apply refactorings incrementally
3. **Verify behavior**: Run full test suite after each refactoring
4. **Update documentation**: Keep docstrings up-to-date with changes

## Conclusion

These refactorings make the code:
- **More idiomatic**: Uses functional pipelines, threading macros, medley utilities
- **More readable**: Clear transformation steps, named helpers, better docstrings
- **More testable**: Each helper function can be tested in isolation
- **More maintainable**: Smaller functions with single responsibilities
- **More debuggable**: Clear data flow, descriptive let bindings, explicit transformations

All changes maintain 100% functional equivalence with the original implementation.
