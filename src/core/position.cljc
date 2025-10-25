(ns core.position
  "Anchor algebra for deterministic positioning in sibling lists.

   Anchors are the closed set of positioning specifications:
   - :first - beginning of sibling list (matches op schema)
   - :last - end of sibling list (matches op schema)
   - {:before id} - before the specified sibling
   - {:after id} - after the specified sibling
   - {:at-index i} - at the specified index

   Note: For compatibility, :at-start and :at-end are aliases for :first and :last.

   All anchor resolution is explicit and total - invalid anchors throw
   ex-info with machine-parsable :reason keys.")

(defn children
  "Get children vector for a parent from db. Returns empty vector if no children."
  [db parent-id]
  (get-in db [:children-by-parent parent-id] []))

(defn- throw-missing-target
  "Throw consistent error for missing sibling target."
  [anchor-type target-id parent-id kids]
  (throw (ex-info (str "Anchor " anchor-type " references unknown sibling")
                  {:reason ::missing-target
                   :anchor-type anchor-type
                   :target-id target-id
                   :parent-id parent-id
                   :available-siblings kids
                   :suggest {:replace-anchor :at-end}})))

(defn- throw-out-of-bounds
  "Throw consistent error for out-of-bounds index."
  [idx n parent-id]
  (throw (ex-info "Anchor index out of bounds"
                  {:reason ::oob
                   :idx idx
                   :n n
                   :parent-id parent-id
                   :suggest {:replace-anchor :at-end}})))

(defn- resolve-before
  "Resolve {:before id} anchor."
  [kids parent-id target-id]
  (let [i (.indexOf kids target-id)]
    (when (neg? i)
      (throw-missing-target :before target-id parent-id kids))
    {:idx i :normalized-anchor {:before target-id}}))

(defn- resolve-after
  "Resolve {:after id} anchor."
  [kids parent-id target-id]
  (let [i (.indexOf kids target-id)]
    (when (neg? i)
      (throw-missing-target :after target-id parent-id kids))
    {:idx (inc i) :normalized-anchor {:after target-id}}))

(defn- resolve-at-index
  "Resolve {:at-index i} anchor."
  [n parent-id i]
  (when-not (int? i)
    (throw (ex-info "Anchor :at-index must be integer"
                    {:reason ::bad-anchor
                     :anchor {:at-index i}
                     :index-value i})))
  (when (or (neg? i) (> i n))
    (throw-out-of-bounds i n parent-id))
  {:idx i :normalized-anchor {:at-index i}})

(defn- resolve-map-anchor
  "Resolve map-based anchor ({:before/:after/:at-index ...})."
  [kids n parent-id anchor]
  (cond
    (contains? anchor :before)
    (resolve-before kids parent-id (:before anchor))

    (contains? anchor :after)
    (resolve-after kids parent-id (:after anchor))

    (contains? anchor :at-index)
    (resolve-at-index n parent-id (:at-index anchor))

    :else
    (throw (ex-info "Invalid map anchor - must have :before, :after, or :at-index"
                    {:reason ::bad-anchor
                     :anchor anchor}))))

(defn ->index
  "Resolve Anchor within parent's children vector.

   Returns: {:idx i :normalized-anchor <Anchor>}
   Throws: ex-info with {:reason keyword ...} on invalid anchor

   Reasons:
   - ::missing-target - anchor references non-existent sibling
   - ::oob - :at-index is out of bounds
   - ::bad-anchor - anchor format is invalid"
  [db parent-id anchor]
  (let [kids (children db parent-id)
        n (count kids)]
    (cond
      (or (= anchor :first) (= anchor :at-start))
      {:idx 0 :normalized-anchor :first}

      (or (= anchor :last) (= anchor :at-end))
      {:idx n :normalized-anchor :last}

      (map? anchor)
      (resolve-map-anchor kids n parent-id anchor)

      (int? anchor)
      (do
        (when (or (neg? anchor) (> anchor n))
          (throw-out-of-bounds anchor n parent-id))
        {:idx anchor :normalized-anchor {:at-index anchor}})

      :else
      (throw (ex-info "Unknown anchor type"
                      {:reason ::bad-anchor
                       :anchor anchor
                       :expected "One of: :at-start, :at-end, {:before id}, {:after id}, {:at-index i}, or integer"})))))

(defn normalize-intent
  "Lifts {:into parent-id anchor?} to {:parent parent-id :anchor anchor'}.

   Supports two formats:
   - {:into parent-id :anchor anchor} => {:parent parent-id :anchor anchor}
   - {:into parent-id} => {:parent parent-id :anchor :at-end}

   If no :into key, returns intent unchanged."
  [intent]
  (if-let [into-val (:into intent)]
    (let [parent-id (if (map? into-val)
                      (:parent into-val)
                      into-val)
          anchor (or (:anchor intent) :at-end)]
      (-> intent
          (dissoc :into)
          (assoc :parent parent-id :anchor anchor)))
    intent))

(defn resolve-anchor
  "High-level helper: resolve anchor to concrete index.
   Returns just the index (integer), throwing on error.

   Use this for simple cases where you just need the index."
  [db parent-id anchor]
  (:idx (->index db parent-id anchor)))

(defn resolve-anchor-in-vec
  "Resolve anchor within an arbitrary vector (not from DB).

   Args:
     kids-vec - vector of IDs
     anchor - anchor specification

   Returns: index (integer)
   Throws: ex-info on invalid anchor

   This is useful when you need to resolve an anchor in a modified sibling list."
  [kids-vec anchor]
  (let [n (count kids-vec)]
    (cond
      (or (= anchor :first) (= anchor :at-start)) 0
      (or (= anchor :last) (= anchor :at-end)) n

      (int? anchor)
      (if (or (neg? anchor) (> anchor n))
        (throw (ex-info "Integer anchor out of bounds"
                       {:reason ::oob :idx anchor :n n}))
        anchor)

      (map? anchor)
      (cond
        (contains? anchor :before)
        (let [i (.indexOf kids-vec (:before anchor))]
          (when (neg? i)
            (throw (ex-info "Anchor :before not found in vector"
                           {:reason ::missing-target :target (:before anchor)})))
          i)

        (contains? anchor :after)
        (let [i (.indexOf kids-vec (:after anchor))]
          (when (neg? i)
            (throw (ex-info "Anchor :after not found in vector"
                           {:reason ::missing-target :target (:after anchor)})))
          (inc i))

        (contains? anchor :at-index)
        (let [i (:at-index anchor)]
          (when (or (neg? i) (> i n))
            (throw (ex-info "Anchor :at-index out of bounds"
                           {:reason ::oob :idx i :n n})))
          i)

        :else
        (throw (ex-info "Invalid map anchor"
                       {:reason ::bad-anchor :anchor anchor})))

      :else
      (throw (ex-info "Unknown anchor type"
                     {:reason ::bad-anchor :anchor anchor})))))
