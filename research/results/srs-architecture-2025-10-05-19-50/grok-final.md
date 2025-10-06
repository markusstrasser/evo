Below is a comprehensive design for a minimal Anki-like Spaced Repetition System (SRS) that extends the existing THREE-OP KERNEL ARCHITECTURE. The design adheres strictly to the kernel's principles: it reuses the core operations (`create-node`, `place`, `update-node`) for all structural changes, compiles high-level SRS "intents" into these ops via the transaction pipeline (normalize → validate → apply → derive), and maintains separation of concerns. The kernel's canonical state (`{:nodes, :children-by-parent, :roots}`) is extended minimally with new node types and derived indexes, but the core kernel remains unmodified.

The SRS layer acts as an extension: it introduces high-level intents (e.g., `create-card`) that compile to kernel ops, similar to how the kernel's extension pattern handles things like `:indent/:outdent`. Persistence is handled via an append-only log (separate from the kernel's in-memory DB), which records SRS-specific events and can be replayed for undo/redo or reconciliation with markdown files. Card-type plugins extend via multimethods without touching the core.

I'll first outline the kernel DB extensions, SRS operation schemas, append-only log format, plugin extension points, and code patterns. Then, I'll address the 8 architecture questions directly, weaving in the required examples.

### Kernel DB Extension
We extend the kernel's `:nodes` map (where keys are node IDs and values are maps like `{:id, :type, :props, ...}`) with new node types. These are just conventions in `:type`—no kernel code changes needed. Cards are structured as trees (e.g., a deck node parents card nodes, which may parent review nodes).

- `:deck`: A container for cards (e.g., `{:id "deck-1" :type :deck :props {:name "Biology" :markdown-file "decks/biology.md"}}`).
- `:card`: Represents a flashcard (e.g., `{:id "card-1" :type :card :props {:front "What is DNA?" :back "Deoxyribonucleic acid" :card-type :basic :due-date "2023-10-01" :markdown-file "cards/dna.md"}}`).
- `:review`: Child of a `:card`, logs a single review event (e.g., `{:id "review-1" :type :review :props {:timestamp "2023-09-01" :ease 2.5 :interval 3 :outcome :again}}`).

Derived indexes (added via kernel's `derive-indexes` hooks) include SRS-specific ones like `:due-cards` (sorted by due-date) and `:review-history-by-card` (map of card-id to sorted review-ids).

The kernel's existing derived indexes (e.g., `parent-of`, `prev/next-id-of`) are reused for navigation (e.g., traversing decks/cards/reviews).

### SRS Operation Schemas (Malli Format)
SRS operations are high-level intents that compile to kernel ops. Schemas use Malli for validation in the transaction pipeline. They match the kernel's pattern: intents are maps with `:op` and params, normalized/validated before compiling.

```clojure
(require '[malli.core :as m])

(def srs-schemas
  {:create-card (m/schema [:map
                           [:op [:= :create-card]]
                           [:deck-id :string]  ; Parent deck
                           [:markdown-file :string]  ; Source file
                           [:props [:map
                                    [:front :string]
                                    [:back :string]
                                    [:card-type keyword?]]]]),  ; e.g., :basic, :cloze (plugin-extensible)

   :update-card (m/schema [:map
                           [:op [:= :update-card]]
                           [:card-id :string]
                           [:props [:map-of keyword? any?]]]),  ; Partial update, e.g. {:front "New front"}

   :review-card (m/schema [:map
                           [:op [:= :review-card]]
                           [:card-id :string]
                           [:outcome [:enum :again :hard :good :easy]]  ; Affects scheduling
                           [:ease {:optional true} :double]]),  ; Optional for advanced scheduling

   :schedule-card (m/schema [:map
                             [:op [:= :schedule-card]]
                             [:card-id :string]
                             [:interval :int]  ; Days, from mock scheduler
                             [:due-date :string]])  ; ISO date
   })
```

These schemas are registered in the kernel's validation step. Undo/redo isn't a schema but a log replay mechanism (see Q5).

### Append-Only Log Entry Format (EDN Examples)
The log is a separate persistence layer (e.g., a file like `srs-log.edn` or a database table), append-only for all SRS state changes. Each entry is an EDN map representing a transaction: the high-level intent, timestamp, and resulting kernel ops (for auditability). It's separate from the kernel DB to allow reconciliation with markdown files.

Example log entries (appended sequentially):

```edn
;; Entry 1: Create a card
{:timestamp "2023-09-01T12:00:00Z"
 :intent {:op :create-card :deck-id "deck-1" :markdown-file "cards/dna.md" :props {:front "What is DNA?" :back "Deoxyribonucleic acid" :card-type :basic}}
 :compiled-ops [{:op :create-node :id "card-1" :type :card :props {...}}  ; Full props
                {:op :place :id "card-1" :parent-id "deck-1" :position :last}]}

;; Entry 2: Review a card
{:timestamp "2023-09-02T12:00:00Z"
 :intent {:op :review-card :card-id "card-1" :outcome :good :ease 2.5}
 :compiled-ops [{:op :create-node :id "review-1" :type :review :props {:timestamp "2023-09-02" :outcome :good :ease 2.5}}
                {:op :place :id "review-1" :parent-id "card-1" :position :last}
                {:op :update-node :id "card-1" :props {:last-reviewed "2023-09-02"}}]}  ; Partial update

;; Entry 3: Schedule (mock)
{:timestamp "2023-09-03T12:00:00Z"
 :intent {:op :schedule-card :card-id "card-1" :interval 3 :due-date "2023-09-06"}
 :compiled-ops [{:op :update-node :id "card-1" :props {:interval 3 :due-date "2023-09-06"}}]}
```

Logs are replayed to reconstruct state (e.g., on app start) or for undo/redo.

### Plugin Extension Points
Plugins extend via multimethods, following the kernel's "compile-intent" pattern. No core modifications—plugins register handlers for card-types.

- `compile-srs-intent`: Multimethod (dispatched on `:card-type` in props) to compile SRS intents to kernel ops.
- `render-card`: Multimethod for UI rendering (e.g., generate HTML/SVG for image-occlusion).
- `schedule-card`: Multimethod for custom scheduling logic per card-type (defaults to mock).

Extension points are hooks in the transaction pipeline (e.g., `derive-indexes` for custom indexes like `:occlusion-masks-by-card`).

### Code Patterns (Concrete Clojure Examples)
Here's how intents compile to kernel ops, reusing the kernel's pipeline.

```clojure
(ns srs.core
  (:require [kernel.core :as kernel]  ; Assume kernel namespace
            [clojure.edn :as edn]
            [malli.core :as m]))

;; Multimethod for compiling SRS intents (extension point)
(defmulti compile-srs-intent (fn [intent _db] (:op intent)))

(defmethod compile-srs-intent :create-card
  [{:keys [deck-id markdown-file props]} db]
  (let [card-id (kernel/gen-id db)]
    [{:op :create-node :id card-id :type :card :props (assoc props :markdown-file markdown-file)}
     {:op :place :id card-id :parent-id deck-id :position :last}]))

(defmethod compile-srs-intent :update-card
  [{:keys [card-id props]} db]
  [{:op :update-node :id card-id :props props}])  ; Kernel handles merging

(defmethod compile-srs-intent :review-card
  [{:keys [card-id outcome ease]} db]
  (let [review-id (kernel/gen-id db)
        review-props {:timestamp (str (java.time.Instant/now)) :outcome outcome :ease (or ease 2.5)}]
    [{:op :create-node :id review-id :type :review :props review-props}
     {:op :place :id review-id :parent-id card-id :position :last}
     {:op :update-node :id card-id :props {:last-reviewed (:timestamp review-props)}}]))

(defmethod compile-srs-intent :schedule-card
  [{:keys [card-id interval due-date]} db]
  [{:op :update-node :id card-id :props {:interval interval :due-date due-date}}])

;; Transaction handler (integrates with kernel pipeline)
(defn process-srs-tx [intent db log-file]
  (when-not (m/validate (get srs-schemas (:op intent)) intent)
    (throw (ex-info "Invalid SRS intent" {:intent intent})))
  (let [compiled-ops (compile-srs-intent intent db)  ; Compile to kernel ops
        new-db (kernel/apply-txs compiled-ops db)   ; Kernel applies/derives
        log-entry {:timestamp (str (java.time.Instant/now)) :intent intent :compiled-ops compiled-ops}]
    (spit log-file (pr-str log-entry) :append true)  ; Append to log
    new-db))

;; Mock scheduler (plugin-overridable)
(defmulti mock-schedule (fn [card-id db outcome] (:card-type (kernel/get-node db card-id))))
(defmethod mock-schedule :default [_ db outcome]
  (let [intervals {:again 1 :hard 3 :good 7 :easy 14}]
    (get intervals outcome 1)))

;; Example plugin for :cloze card-type
(defmethod compile-srs-intent :create-card  ; Override for :cloze (dispatches on :card-type if needed)
  [intent db]  ; Custom compilation logic here, e.g., create child nodes for clozes
  ...)

;; Undo/redo example (see Q5)
(defn undo-last [log-file db]
  (let [logs (edn/read-string (slurp log-file))  ; Read all
        last-entry (last logs)
        inverse-ops (map kernel/invert-op (:compiled-ops last-entry))]  ; Assume kernel has invert-op
    (kernel/apply-txs inverse-ops db)))  ; Replay inverse
```

This pattern reuses kernel ops (e.g., `create-node` for new cards/reviews, `update-node` for props like due-date, `place` for hierarchy).

### Architecture Questions Answered
1. **How should markdown frontmatter map to kernel nodes? (YAML → :props shape)**  
   Markdown files are the source of truth for card content. Frontmatter (YAML) maps directly to `:props` in kernel nodes. E.g., a file `cards/dna.md` with YAML `{front: "What is DNA?", back: "Deoxyribonucleic acid", card-type: basic}` becomes `{:props {:front "What is DNA?" :back "Deoxyribonucleic acid" :card-type :basic :markdown-file "cards/dna.md"}}`. On load, parse YAML to props and use `create-node`/`place`. Edits to markdown trigger `update-card` intents, which compile to `update-node`.

2. **What's the append-only log format for SRS events? (EDN transactions?)**  
   Yes, EDN maps as shown above. Each entry includes `:timestamp`, `:intent` (high-level SRS op), and `:compiled-ops` (kernel ops). This allows auditing, replay, and separation from kernel DB. SRS events (e.g., reviews) are logged here, not in markdown.

3. **How do card-type plugins extend without modifying core?**  
   Via multimethods like `compile-srs-intent` (dispatched on `:card-type`), `render-card`, and `mock-schedule`. Plugins register new methods (e.g., for `:cloze`, add cloze-specific child nodes during compilation). No core changes—extensions hook into the pipeline.

4. **What are the exact 4 SRS operations and their schemas? (Malli format)**  
   As shown in the "SRS Operation Schemas" section above. They are `:create-card`, `:update-card`, `:review-card`, `:schedule-card`. Each is a map intent validated by Malli before compilation.

5. **How does undo/redo work with append-only log?**  
   Replay the log inversely. For undo, read the last entry, invert its `:compiled-ops` (e.g., `:create-node` → delete, `:update-node` → restore old props using kernel's derived indexes like `prev-id-of`), and apply via kernel's `apply-txs`. Redo appends a new entry replaying forward. Logs are truncated on undo (but kept for history). This works because the log is append-only but replayable.

6. **What derived indexes are needed? (due-date, review-history, scheduling-metadata)**  
   Add via kernel's `derive-indexes` hooks:  
   - `:due-cards`: Sorted map of `{due-date [card-ids]}` (derived from `:props :due-date`).  
   - `:review-history-by-card`: `{card-id [sorted-review-ids]}` (using kernel's `children-by-parent`).  
   - `:scheduling-metadata-by-card`: `{card-id {:avg-ease ..., :last-interval ...}}` (computed from review children). These are recomputed in the `derive` pipeline step.

7. **How does markdown editing reconcile with log-based state?**  
   Markdown is source of truth for content; log for events (reviews/scheduling). On edit (detected via file watcher), parse changes and issue `update-card` intent, which logs and updates kernel DB. On app start, replay log onto an initial state loaded from markdown files. Conflicts (e.g., external edit) are resolved by prioritizing log timestamps (latest wins, merge props).

8. **How do image-occlusion cards work? (SVG overlays? Separate nodes?)**  
   For `:card-type :image-occlusion`, plugins create child nodes for occlusions (e.g., `create-node` for each mask as `:occlusion` type with `:props {:svg "<rect x=10 y=10 />" :image-url "img.png"}`). Rendering multimethod generates SVG overlays. Compilation adds `:place` ops for children. Scheduling treats it as a single card but plugins can customize (e.g., per-occlusion reviews).
