(ns kernel.product-state
  "Validator for the current DB x session product state.

   This is intentionally a validator over the current session map, not a new
   storage model. It names impossible interaction states before any future
   migration to explicit interaction/visibility/overlay axes."
  (:require [kernel.query :as q]
            [kernel.state-machine :as sm]))

(defn- issue [kind data]
  (assoc data :issue kind))

(defn- node-exists? [db id]
  (contains? (:nodes db) id))

(defn- in-trash? [db id]
  (loop [current id]
    (cond
      (= :trash current) true
      (or (nil? current) (keyword? current)) false
      :else (recur (q/parent-of db current)))))

(defn- visible-selectable? [db session id]
  (and (q/selectable-block? db id)
       (boolean (some #(= id %) (q/selectable-visible-blocks db session)))))

(defn- buffer-map [session]
  (or (:buffer session) {}))

(defn validate
  "Return a vector of product-state issues for DB and current session shape.
   Empty vector means the current product state is coherent."
  [db session]
  (let [editing-id (q/editing-block-id session)
        selected (q/selection session)
        focus-id (q/focus session)
        anchor-id (q/anchor session)
        state (sm/current-state session)
        buffer (buffer-map session)
        cursor-pos (q/cursor-position session)
        folded (q/folded-set session)]
    (vec
     (concat
      (when (and editing-id (seq selected))
        [(issue :editing-and-selection-coexist
                {:editing-block-id editing-id :selection selected})])

      (when (and (= :focused state) (or editing-id (seq selected) (nil? focus-id)))
        [(issue :invalid-focused-state
                {:editing-block-id editing-id :selection selected :focus focus-id})])

      (when (and editing-id (not (node-exists? db editing-id)))
        [(issue :editing-node-missing {:id editing-id})])

      (when (and editing-id (in-trash? db editing-id))
        [(issue :editing-node-in-trash {:id editing-id})])

      (when (and editing-id (not (visible-selectable? db session editing-id)))
        [(issue :editing-node-not-visible-selectable {:id editing-id})])

      (when (seq selected)
        (concat
         (when-not (contains? selected focus-id)
           [(issue :selection-focus-not-selected {:focus focus-id :selection selected})])
         (when-not (contains? selected anchor-id)
           [(issue :selection-anchor-not-selected {:anchor anchor-id :selection selected})])
         (for [id selected
               :when (not (visible-selectable? db session id))]
           (issue :selected-node-not-visible-selectable {:id id}))))

      (when (and focus-id
                 (not (seq selected))
                 (not (visible-selectable? db session focus-id)))
        [(issue :focus-node-not-visible-selectable {:id focus-id})])

      (when (seq buffer)
        (concat
         (when-not editing-id
           [(issue :buffer-without-editing {:buffer-ids (set (keys buffer))})])
         (when (and editing-id (not= #{editing-id} (set (keys buffer))))
           [(issue :buffer-owner-mismatch
                   {:editing-block-id editing-id :buffer-ids (set (keys buffer))})])))

      (when (and editing-id (integer? cursor-pos))
        (let [live-text (or (get buffer editing-id)
                            (q/block-text db editing-id))]
          (when-not (<= 0 cursor-pos (count live-text))
            [(issue :cursor-offset-out-of-bounds
                    {:editing-block-id editing-id
                     :cursor-position cursor-pos
                     :text-length (count live-text)})])))

      (for [id folded
            :when (or (not (node-exists? db id))
                      (empty? (q/children db id)))]
        (issue :invalid-folded-leaf {:id id}))))))

(defn valid?
  [db session]
  (empty? (validate db session)))

(defn assert-valid!
  [db session]
  (let [issues (validate db session)]
    (when (seq issues)
      (throw (ex-info "Invalid product state" {:issues issues})))
    true))
