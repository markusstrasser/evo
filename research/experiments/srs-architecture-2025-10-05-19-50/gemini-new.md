This is a fascinating challenge. Extending a generic kernel with domain-specific logic for an SRS system is a great way to test its extensibility. Here is a design that addresses your requirements and architectural questions, following the existing patterns of the three-op kernel.

### High-Level Architecture

The SRS system will be implemented as a layer on top of the existing kernel. It will introduce new "intents" (the 4 SRS operations) that compile down to the kernel's core operations. SRS-specific data will be stored as new node types and in new derived indexes. Persistence will be handled by an append-only log of SRS transactions, with markdown files as the source of truth for card content.

Here's how the pieces fit together:

```
+--------------------------------+
|      Markdown Files (Content)  |
+--------------------------------+
             ^
             | (sync)
             v
+--------------------------------+
|         SRS Application        |
|  (UI, Intents, Business Logic) |
+--------------------------------+
             |
             v
+--------------------------------+
|      SRS Intent Compiler       |
| (compile-intent multimethod)   |
+--------------------------------+
             |
             v
+--------------------------------+       +-----------------------------+
|      Kernel Transaction        |------>|     Append-only Log         |
|         Pipeline               |       | (EDN Transactions)          |
| (normalize->validate->apply)   |       +-----------------------------+
+--------------------------------+
             |
             v
+--------------------------------+
|         Kernel State           |
| (:nodes, :children-by-parent)  |
+--------------------------------+
             |
             v
+--------------------------------+
|      Derived Indexes           |
| (core indexes + SRS indexes)   |
+--------------------------------+
```

---

### 1. Kernel DB Extension & Markdown Mapping

We'll introduce three new node types: `:deck`, `:card`, and `:review`.

*   **:deck**: Represents a collection of cards. Maps to a directory of markdown files.
*   **:card**: Represents a single card. Maps to a single markdown file.
*   **:review**: Represents a single review event for a card.

**Markdown Frontmatter to Kernel Node Props Mapping (Q1)**

A markdown file like `my-deck/card-1.md`:

```yaml
---
id: card-abc-123 # optional, can be derived from filename
type: :basic # for the plugin system
tags: [clojure, kernel]
---

# Front
What are the three core kernel operations?

# Back
create-node, place, update-node
```

...would be parsed and mapped to a `:card` node in the kernel's `:nodes` map:

```clojure
;; In the :nodes map
{:card-abc-123
 {:id :card-abc-123
  :type :kernel/node
  :props {:node-type :card
          :card-type :basic ;; For plugin dispatch
          :source-path "my-deck/card-1.md"
          :tags ["clojure" "kernel"]
          :front "<h1>Front</h1><p>What are the three core kernel operations?</p>"
          :back "<h1>Back</h1><p>create-node, place, update-node</p>"
          ;; SRS state will be added here by the scheduler
          :srs/due-date "2025-10-06T10:00:00Z"
          :srs/interval-days 1
          :srs/ease-factor 2.5}}}
```

The body of the markdown file is parsed into `:front` and `:back` props. The YAML frontmatter is mapped directly to props under the `:props` key.

### 2. Append-only Log Format (Q2)

The append-only log will store every SRS transaction as an EDN object, making it durable and easy to parse. Each entry will have a timestamp, the transaction data, and metadata.

**Log Entry Format:**

```clojure
{:tx-id #uuid "..."
 :timestamp "2025-10-05T20:00:00Z"
 :user-id :system ;; or a user id
 :intent [:srs/review-card {:card-id :card-abc-123, :grade :good}]
 :compiled-ops [[:kernel/update-node {:id :card-abc-123, :props {:srs/last-reviewed ...}}]
                [:kernel/create-node {:id :review-xyz-789, :props {:node-type :review, ...}}]
                [:kernel/place {:id :review-xyz-789, :parent :card-abc-123, :index 0}]]}
```

This captures the high-level intent *and* the low-level compiled operations, which is useful for debugging and migrations.

### 3. SRS Operations & Schemas (Q4)

Here are the four SRS intents and their Malli schemas.

```clojure
(def CardId :keyword)
(def DeckId :keyword)
(def ReviewGrade [:enum :again :hard :good :easy])

(def CreateCard
  [:map
   [:intent-type :srs/create-card]
   [:deck-id DeckId]
   [:card-id CardId]
   [:props [:map
            [:card-type :keyword]
            [:front :string]
            [:back :string]
            [:tags {:optional true} [:vector :string]]]]])

(def UpdateCard
  [:map
   [:intent-type :srs/update-card]
   [:card-id CardId]
   [:props [:map
            [:front {:optional true} :string]
            [:back {:optional true} :string]
            [:tags {:optional true} [:vector :string]]]]])

(def ReviewCard
  [:map
   [:intent-type :srs/review-card]
   [:card-id CardId]
   [:grade ReviewGrade]])

(def ScheduleCard
  [:map
   [:intent-type :srs/schedule-card]
   [:card-id CardId]])
```

### 4. Intent Compilation & Plugin System (Q3)

The core of the extension pattern is compiling high-level intents into core kernel operations. We'll use a multimethod for this, which also serves as our primary plugin extension point.

```clojure
(defmulti compile-intent
  "Compiles a high-level intent into a vector of core kernel operations."
  (fn [intent] (first intent)))

;; Default implementation for kernel ops (pass-through)
(defmethod compile-intent :default [intent]
  [intent])

;; SRS: create-card intent
(defmethod compile-intent :srs/create-card [[_ {:keys [deck-id card-id props]}]]
  [[:kernel/create-node {:id card-id
                         :props (merge props {:node-type :card})}]
   [:kernel/place {:id card-id
                   :parent deck-id
                   :index :last}]])

;; SRS: review-card intent
(defmethod compile-intent :srs/review-card [[_ {:keys [card-id grade] :as review}]]
  (let [current-card-state (get-in @db [:nodes card-id :props])
        new-srs-state (mock-scheduler/calculate-next-review current-card-state grade)
        review-id (random-uuid)]
    [[:kernel/update-node {:id card-id
                           :props new-srs-state}]
     [:kernel/create-node {:id review-id
                           :props {:node-type :review
                                   :card-id card-id
                                   :grade grade
                                   :timestamp (now)}}]
     [:kernel/place {:id review-id
                     :parent card-id
                     :index :last}]]))
```

**Card-type plugins** can extend this system by defining new card types and providing their own `compile-intent` methods for custom logic. For example, an `image-occlusion` plugin could have its own `:srs/create-image-occlusion-card` intent.

### 5. Image Occlusion Cards (Q8)

For image occlusion cards, the card's props would store the base image URL and a list of occlusion shapes (e.g., SVG paths or rectangles).

**Card props for image occlusion:**

```clojure
{:node-type :card
 :card-type :image-occlusion
 :image-url "/images/anatomy.png"
 :occlusions [{:id :occ1 :shape "M10,10 L50,10 L50,50 L10,50 Z"}
              {:id :occ2 :shape "M70,20 L100,20 L100,40 L70,40 Z"}]
 :question "Identify the highlighted bone."
 :answer "Femur"}
```

The frontend would be responsible for rendering the image and the SVG overlays. The plugin could also provide a custom editor for creating these cards.

### 6. Derived Indexes (Q6)

The SRS plugin will register new derived indexers to provide fast lookups for SRS-specific data.

*   **:srs/cards-due**: A map from `due-date -> [card-id]`. This is the most important index for fetching cards for review.
*   **:srs/review-history-by-card**: A map from `card-id -> [review-id]`.
*   **:srs/scheduling-metadata-by-card**: A map from `card-id -> {:interval-days, :ease-factor, ...}`.

These would be implemented as functions that are hooked into the kernel's `derive-indexes` pipeline.

```clojure
(defn derive-srs-indexes [db]
  (let [cards (filter #(= :card (:node-type (:props %))) (vals (:nodes db)))]
    {:srs/cards-due (group-by :srs/due-date (map :id cards))
     ;; ... other indexes
     }))
```

### 7. Undo/Redo from Append-only Log (Q5)

The append-only log makes undo/redo straightforward.

*   **Undo**: To undo an intent, we need to compute its inverse. We can define another multimethod, `invert-intent`, for this.
    *   The inverse of `:srs/create-card` is `:kernel/delete-node`.
    *   The inverse of `:srs/review-card` is more complex. It would involve finding the previous SRS state of the card and applying it via `:kernel/update-node`, and deleting the created `:review` node. We would fetch the previous state from the log itself.
*   **Redo**: To redo, we simply re-apply the intent from the log that was just undone.

This approach is robust because it doesn't require state snapshots.

### 8. Markdown Editing Reconciliation (Q7)

This is a classic "source of truth" problem. Here's a robust reconciliation strategy:

1.  **Markdown is the source of truth for card *content* (`:front`, `:back`, `:tags`, etc.).**
2.  **The append-only log is the source of truth for SRS *metadata* (`:srs/due-date`, review history, etc.).**

**Reconciliation Process:**

*   When a markdown file is changed (detected via file watching or a manual trigger), the system will:
    1.  Read and parse the markdown file.
    2.  Look up the corresponding card node in the kernel DB using the file path or an ID in the frontmatter.
    3.  Generate an `:srs/update-card` intent with the new content from the markdown file.
    4.  This intent will be compiled and applied, updating the card's content props *without* affecting its SRS metadata.
*   If a card is edited in the application, the application is responsible for writing the changes back to the corresponding markdown file.

This ensures that the user can freely edit their card content in markdown without losing their review history, and vice-versa.

This design provides a solid foundation for an Anki-like SRS system built on top of your existing kernel. It reuses the kernel's core concepts, provides clear extension points for plugins, and addresses the key challenges of data modeling, persistence, and reconciliation.
