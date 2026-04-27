(ns kernel.product-state
  "Validator for the current DB x session product state.

   This is intentionally a validator over the current session map, not a new
   storage model. It names impossible interaction states before any future
   migration to explicit interaction/visibility/overlay axes.

   Phase model (review finding #19):

   The validator distinguishes :hard issues (logical contradictions that
   cannot be resolved by normalization — e.g. editing+selection coexisting,
   cursor out of live-text bounds, buffer without an editing owner) from
   :cleanup issues (identity drift that the next normalization pass is
   expected to fix — e.g. focus on a node that was just deleted, fold-set
   entry that lost its children).

   Callers choose phase:

   - :steady (default) — everything must hold. Use for post-normalization
     guardrails, FR fixtures, and assertions that fire after the kernel has
     finished reacting to an intent.
   - :transient — only :hard issues are reported. Use mid-flight, before the
     follow-up normalization step has run, to catch genuine contradictions
     without false-flagging the cleanup window."
  (:require [kernel.query :as q]
            [kernel.state-machine :as sm]))

(def ^:private cleanup-issue-kinds
  "Issues that normalization is expected to resolve and therefore must not
   trip a :transient-phase check. Adding to this set is a deliberate decision
   that the listed condition can legitimately appear between an intent
   landing and its follow-up cleanup."
  #{:editing-node-missing
    :editing-node-not-visible-selectable
    :selected-node-not-visible-selectable
    :focus-node-not-visible-selectable
    :invalid-folded-leaf})

(defn- severity [kind]
  (if (contains? cleanup-issue-kinds kind) :cleanup :hard))

(defn- issue [kind data]
  (assoc data :issue kind :severity (severity kind)))

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
   Empty vector means the current product state is coherent.

   Opts:
     :phase  :steady (default) — return all issues
             :transient        — return only :hard issues; cleanup-class
                                 issues (focus on missing node, fold-set
                                 entries that lost children, etc.) are
                                 suppressed because the next normalization
                                 pass is expected to resolve them."
  ([db session] (validate db session {}))
  ([db session {:keys [phase] :or {phase :steady}}]
   (let [editing-id (q/editing-block-id session)
         selected (q/selection session)
         focus-id (q/focus session)
         anchor-id (q/anchor session)
         state (sm/current-state session)
         buffer (buffer-map session)
         cursor-pos (q/cursor-position session)
         folded (q/folded-set session)
         all-issues
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
             (issue :invalid-folded-leaf {:id id}))))]
     (case phase
       :transient (filterv #(= :hard (:severity %)) all-issues)
       :steady all-issues
       (throw (ex-info "Unknown product-state phase"
                       {:phase phase :allowed #{:steady :transient}}))))))

(defn valid?
  ([db session] (valid? db session {}))
  ([db session opts] (empty? (validate db session opts))))

(defn assert-valid!
  ([db session] (assert-valid! db session {}))
  ([db session opts]
   (let [issues (validate db session opts)]
     (when (seq issues)
       (throw (ex-info "Invalid product state" {:issues issues
                                                :phase (:phase opts :steady)})))
     true)))
