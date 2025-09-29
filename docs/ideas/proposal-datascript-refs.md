# Proposal: Schema-Driven Graph Relationships

**Inspiration Source**: `/Users/alien/Projects/inspo-clones/datascript/src/datascript/db.cljc`

Datascript's power comes from its schema, which declaratively defines the shape and semantics of the data, including relationships. We can adopt this principle to make our kernel's `:refs` system more robust, expressive, and less error-prone.

---

### Before: Ad-Hoc, Imperative Relationships

Currently, relationships are managed imperatively. Constraints like uniqueness are checked in code, and there are no built-in semantics for reverse lookups or cascading deletes.

```clojure
;; **COMPLEXITY**: High.
;; The logic is scattered. Uniqueness is checked in `edge-ok?`. Cascading deletes
;; are a manual, multi-step process the user must implement. Reverse lookups
;; require a full, inefficient scan of the entire refs map.

;; 1. Adding a ref (no automatic reverse ref)
(apply-tx* db {:op :add-ref :rel :author/_books :src "author-1" :dst "book-1"})

;; 2. Manually finding what books an author wrote (inefficient scan)
(defn find-books-by-author [db author-id]
  (for [[rel m] (:refs db)
        [src dsts] m
        :when (= src author-id)
        :when (= rel :author/_books)
        dst dsts]
    dst))

;; 3. Manually implementing a cascading delete
(defn delete-author-and-books [db author-id]
  (let [book-ids (find-books-by-author db author-id)
        db'      (reduce (fn [d id] (apply-tx* d {:op :delete :id id})) db book-ids)]
    (apply-tx* db' {:op :delete :id author-id})))
```

### After: Declarative, Schema-Driven Relationships

By enhancing our `:edge-registry` to be a proper schema, we can centralize relationship logic and provide these features automatically.

```clojure
;; **COMPLEXITY**: Low.
;; The logic is centralized in the schema and kernel. The user declares the
;; relationship semantics once. Reverse lookups are automatic and efficient.
;; Cascading deletes are handled by the kernel's `prune*` operation, requiring
;; no extra user code.

;; 1. Declare relationship semantics in the schema
(def new-edge-schema
  {:author/_books {:db/cardinality :db.cardinality/many
                   :db/isComponent true}}) ;; Books are components of the author

;; 2. Adding a ref now automatically creates the reverse ref
(apply-tx* db {:op :add-ref :rel :author/_books :src "author-1" :dst "book-1"})
;; This automatically creates the `:_author/_books` back-reference.

;; 3. Finding an author's books is now a direct, efficient lookup
(get-in db [:refs :_author/_books "book-1"])
; => #{"author-1"}

;; 4. Deleting the author now automatically cascades to its component books
(apply-tx* db {:op :delete :id "author-1"})
;; The kernel's prune* op sees `:db/isComponent` and also deletes "book-1".
```

---
### Summary of Improvements

*   **Reduced Boilerplate**: Eliminates the need for manual reverse-lookup and cascading-delete logic.
*   **Increased Performance**: Direct lookup of reverse references is significantly faster than scanning.
*   **Improved Data Integrity**: Centralizes relationship rules in the schema, ensuring they are applied consistently and preventing orphaned component entities.
*   **Clarity**: The schema becomes a single source of truth for understanding how different entities relate to one another.