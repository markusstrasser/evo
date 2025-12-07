(ns spec.tree-dsl
  "Tree DSL for human-readable spec scenarios.
   
   Converts between human-readable tree notation and canonical DB shape.
   
   DSL Format:
   [:doc                           ; Root (keyword)
    [:a \"Hello\"]                  ; Block: [id text]
    [:b \"World\" {:cursor 0}       ; Block with attrs
     [:c \"Child\"]]]               ; Nested children
   
   Attributes:
   - :cursor n     → Sets cursor position, implies editing this block
   - :selected?    → Block is in selection set
   - :anchor?      → Block is selection anchor  
   - :focus?       → Block is selection focus
   - :folded?      → Block is folded
   
   Example:
     (def dsl [:doc [:a \"Hello\"] [:b \"World\" {:cursor 0}]])
     (def {:keys [db session]} (dsl->state dsl))
     ;; db = canonical DB with nodes + children-by-parent
     ;; session = {:ui {:editing-block-id \"b\" :cursor-position 0}}
   
   Round-trip:
     (= dsl (state->dsl db session))  ; Should be true (modulo ordering)"
  (:require [kernel.db :as db]
            [kernel.constants :as const]))

;; ══════════════════════════════════════════════════════════════════════════════
;; DSL → State Conversion
;; ══════════════════════════════════════════════════════════════════════════════

(defn- parse-block-entry
  "Parse a block entry from the DSL.
   
   Format: [id text] or [id text attrs] or [id text attrs & children]
   
   Returns: {:id :text :attrs :children}"
  [entry]
  (let [[id-raw & rest] entry
        id (if (keyword? id-raw) (name id-raw) (str id-raw))
        [text-or-attrs & more] rest

        ;; Determine if second element is text or attrs
        [text attrs children]
        (cond
          ;; [id] - no text, no attrs
          (nil? text-or-attrs)
          ["" {} []]

          ;; [id "text" ...] - text first
          (string? text-or-attrs)
          (let [[maybe-attrs & kids] more]
            (if (map? maybe-attrs)
              [text-or-attrs maybe-attrs (vec kids)]
              [text-or-attrs {} (vec (cons maybe-attrs kids))]))

          ;; [id {:attrs}] - attrs only, no text
          (map? text-or-attrs)
          ["" text-or-attrs (vec more)]

          ;; [id [...children]] - no text, just children
          (vector? text-or-attrs)
          ["" {} (vec (cons text-or-attrs more))]

          :else
          [(str text-or-attrs) {} []])]

    {:id id
     :text (or text "")
     :attrs (or attrs {})
     :children (filterv vector? children)}))

(defn- collect-blocks
  "Recursively collect all blocks from DSL tree.
   
   Returns: {:nodes {...} :children-by-parent {...} :session-hints [...]}"
  [parent-id entries]
  (reduce
   (fn [acc entry]
     (let [{:keys [id text attrs children]} (parse-block-entry entry)

            ;; Build node
           node {:type :block :props {:text text}}

            ;; Collect session hints from attrs
           hints (cond-> []
                   (:cursor attrs) (conj {:type :cursor :id id :pos (:cursor attrs)})
                   (:selected? attrs) (conj {:type :selected :id id})
                   (:anchor? attrs) (conj {:type :anchor :id id})
                   (:focus? attrs) (conj {:type :focus :id id})
                   (:folded? attrs) (conj {:type :folded :id id}))

            ;; Recurse into children
           child-result (collect-blocks id children)]

       (-> acc
           (assoc-in [:nodes id] node)
           (update-in [:children-by-parent parent-id] (fnil conj []) id)
           (update :nodes merge (:nodes child-result))
           (update :children-by-parent merge (:children-by-parent child-result))
           (update :session-hints into hints)
           (update :session-hints into (:session-hints child-result)))))
   {:nodes {}
    :children-by-parent {}
    :session-hints []}
   entries))

(defn- hints->session
  "Convert session hints to canonical session shape."
  [hints]
  (let [cursor-hint (first (filter #(= :cursor (:type %)) hints))
        selected-ids (set (map :id (filter #(= :selected (:type %)) hints)))
        anchor-id (:id (first (filter #(= :anchor (:type %)) hints)))
        focus-id (:id (first (filter #(= :focus (:type %)) hints)))
        folded-ids (set (map :id (filter #(= :folded (:type %)) hints)))]

    (cond-> {}
      cursor-hint
      (assoc :ui {:editing-block-id (:id cursor-hint)
                  :cursor-position (:pos cursor-hint)})

      (seq selected-ids)
      (assoc-in [:selection :nodes] selected-ids)

      anchor-id
      (assoc-in [:selection :anchor] anchor-id)

      focus-id
      (assoc-in [:selection :focus] focus-id)

      (seq folded-ids)
      (assoc-in [:ui :folded] folded-ids))))

(defn dsl->state
  "Convert tree DSL to canonical DB and session state.
   
   Args:
   - tree: DSL tree starting with root keyword (e.g., :doc)
   
   Returns:
   {:db {:nodes {...} :children-by-parent {...} :roots [...] :derived {...}}
    :session {:ui {...} :selection {...}}}
   
   Example:
     (dsl->state [:doc [:a \"Hello\"] [:b \"World\" {:cursor 0}]])
     ;=> {:db {...} :session {:ui {:editing-block-id \"b\" :cursor-position 0}}}"
  [tree]
  (let [[root & children] tree
        root-kw (if (keyword? root) root (keyword root))
        {:keys [nodes children-by-parent session-hints]} (collect-blocks root-kw children)

        ;; Build canonical DB
        base-db (db/empty-db)
        db-with-nodes (-> base-db
                          (assoc :nodes nodes)
                          (assoc :children-by-parent children-by-parent))
        derived-db (db/derive-indexes db-with-nodes)

        ;; Build session
        session (hints->session session-hints)]

    {:db derived-db
     :session session}))

;; ══════════════════════════════════════════════════════════════════════════════
;; State → DSL Conversion (for visualization/debugging)
;; ══════════════════════════════════════════════════════════════════════════════

(defn- block->dsl-entry
  "Convert a block node to DSL entry format.
   
   Returns: [id text ?attrs ?children...]"
  [db session id]
  (let [node (get-in db [:nodes id])
        text (get-in node [:props :text] "")
        children (get-in db [:children-by-parent id] [])

        ;; Build attrs from session
        editing? (= id (get-in session [:ui :editing-block-id]))
        cursor-pos (when editing? (get-in session [:ui :cursor-position]))
        selected? (contains? (get-in session [:selection :nodes] #{}) id)
        anchor? (= id (get-in session [:selection :anchor]))
        focus? (= id (get-in session [:selection :focus]))
        folded? (contains? (get-in session [:ui :folded] #{}) id)

        attrs (cond-> {}
                cursor-pos (assoc :cursor cursor-pos)
                selected? (assoc :selected? true)
                anchor? (assoc :anchor? true)
                focus? (assoc :focus? true)
                folded? (assoc :folded? true))

        ;; Recurse into children
        child-entries (mapv #(block->dsl-entry db session %) children)

        ;; Build entry: [id text] or [id text attrs] or [id text attrs children...]
        entry (cond-> [(keyword id) text]
                (seq attrs) (conj attrs))]

    (if (seq child-entries)
      (vec (concat entry child-entries))
      entry)))

(defn state->dsl
  "Convert DB and session state back to tree DSL.
   
   Args:
   - db: Canonical DB
   - session: Session state (optional)
   - root: Root to export (default :doc)
   
   Returns: Tree DSL"
  ([db] (state->dsl db {} :doc))
  ([db session] (state->dsl db session :doc))
  ([db session root]
   (let [root-kw (if (keyword? root) root (keyword root))
         children (get-in db [:children-by-parent root-kw] [])
         child-entries (mapv #(block->dsl-entry db session %) children)]
     (vec (cons root-kw child-entries)))))

;; ══════════════════════════════════════════════════════════════════════════════
;; Tree Comparison (for test assertions)
;; ══════════════════════════════════════════════════════════════════════════════

(defn- wildcard-id?
  "Check if an ID is a wildcard (matches any block ID)."
  [id]
  (or (= id :_) (= id :new) (= id "_") (= id "new")))

(defn- block-matches?
  "Check if expected block matches actual block (supporting wildcards).
   
   Wildcards:
   - :_ or :new - Matches any block ID
   - {:cursor :_} - Matches any cursor position"
  [expected-entry actual-entry]
  (let [[exp-id exp-text & exp-rest] expected-entry
        [act-id act-text & act-rest] actual-entry

        ;; ID match (with wildcard support)
        id-ok? (or (wildcard-id? exp-id)
                   (= (name exp-id) (name act-id)))

        ;; Text match
        text-ok? (= exp-text act-text)

        ;; Extract attrs and children
        exp-attrs (first (filter map? exp-rest))
        act-attrs (first (filter map? act-rest))
        exp-children (filter vector? exp-rest)
        act-children (filter vector? act-rest)

        ;; Attrs match (with wildcard support for cursor)
        attrs-ok? (every? (fn [[k v]]
                            (if (= v :_)
                              (contains? act-attrs k)
                              (= v (get act-attrs k))))
                          (or exp-attrs {}))

        ;; Children match (recursively)
        children-ok? (and (= (count exp-children) (count act-children))
                          (every? true? (map block-matches? exp-children act-children)))]

    (and id-ok? text-ok? attrs-ok? children-ok?)))

(defn- normalize-tree
  "Normalize tree for comparison (remove empty attrs, sort consistently)."
  [tree]
  (if (vector? tree)
    (let [[root & entries] tree]
      (vec (cons root (mapv normalize-tree entries))))
    tree))

(defn tree-matches?
  "Check if two trees are equivalent (ignoring attr order, empty attrs).
   
   Supports wildcards in expected tree:
   - :_ or :new for block ID - matches any block ID
   - :_ for attr value - matches any value (just checks key exists)
   
   Returns: true if trees match"
  [expected actual]
  (let [[exp-root & exp-entries] expected
        [act-root & act-entries] actual]
    (and (= exp-root act-root)
         (= (count exp-entries) (count act-entries))
         (every? true? (map block-matches? exp-entries act-entries)))))

(defn tree-diff
  "Compute diff between expected and actual trees.
   
   Returns: Map describing differences or nil if equal."
  [expected actual]
  (let [norm-expected (normalize-tree expected)
        norm-actual (normalize-tree actual)]
    (when (not= norm-expected norm-actual)
      {:expected norm-expected
       :actual norm-actual
       ;; TODO: Use editscript for detailed diff
       :hint "Trees differ"})))

;; ══════════════════════════════════════════════════════════════════════════════
;; Utilities
;; ══════════════════════════════════════════════════════════════════════════════

(defn get-block-text
  "Get text of a block by ID from DSL-created DB."
  [db id]
  (get-in db [:nodes id :props :text]))

(defn get-cursor
  "Get cursor position from session."
  [session]
  {:block-id (get-in session [:ui :editing-block-id])
   :position (get-in session [:ui :cursor-position])})

(defn editing?
  "Check if a specific block is being edited."
  [session id]
  (= id (get-in session [:ui :editing-block-id])))

(comment
  ;; Example usage
  (def dsl
    [:doc
     [:a "Hello"]
     [:b "World" {:cursor 0}
      [:c "Child block"]]])

  (def {:keys [db session]} (dsl->state dsl))

  ;; Check structure
  (keys (:nodes db)) ;=> ("a" "b" "c")
  (:children-by-parent db) ;=> {:doc ["a" "b"], "b" ["c"]}

  ;; Check session
  session ;=> {:ui {:editing-block-id "b" :cursor-position 0}}

  ;; Round-trip
  (state->dsl db session)
  ;=> [:doc [:a "Hello"] [:b "World" {:cursor 0} [:c "Child block"]]]
  )
