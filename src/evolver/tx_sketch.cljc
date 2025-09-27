(ns evolver.tx-sketch)
;; --- tiny read helpers over your plain db map ----------------------

(defn index-of [v x]
  (loop [i 0] (cond (>= i (count v)) -1 (= (nth v i) x) i :else (recur (inc i)))))

(defn parent-of [db id]
  (or (get-in db [:derived :parent-of id])
      (some (fn [[p kids]] (when (some #{id} kids) p))
            (:children-by-parent db))))

(defn children-of [db id] (get-in db [:children-by-parent id] []))

(defn prev-sibling [db id]
  (when-let [p (parent-of db id)]
    (let [kids (children-of db p) i (index-of kids id)]
      (when (> i 0) (nth kids (dec i))))))

(defn next-sibling [db id]
  (when-let [p (parent-of db id)]
    (let [kids (children-of db p) i (index-of kids id)]
      (when (and (>= i 0) (< (inc i) (count kids))) (nth kids (inc i))))))

;; --- canonical core ops we target ---------------------------------
;; :patch {:id ... :updates m}
;; :mv    {:id ... :to parent-id :at [:index i]|[:before sib]|[:after sib]|[:first]|[:last]}
;; :reorder {:id ... :parent parent-id :to i}
;; :del   {:id ...}    ;; (children should be moved explicitly beforehand if needed)
;; :ins   {:id ... :under parent :at ... :type kw :props m}  ;; if you need creation

;; --- intent → tx compiler (pure) ----------------------------------

(defn compile-intent
  "Return a vector of core ops for one high-level intent, or [] if no-op."
  [db [k {:keys [node-id] :as args}]]
  (case k
    ;; Merge this block into its previous sibling: concat text, adopt children, delete self
    :merge-up
    (let [id node-id
          prev (prev-sibling db id)]
      (if-not prev
        []                                                  ;; no-op at top
        (let [txt (or (get-in db [:nodes id :text]) (get-in db [:nodes id :props :text]) "")
              ptx (or (get-in db [:nodes prev :text]) (get-in db [:nodes prev :props :text]) "")
              kids (children-of db id)]
          (into
            [[:patch {:id prev :updates (if (get-in db [:nodes prev :props])
                                          {:props {:text (str ptx txt)}}
                                          {:text (str ptx txt)})}]]
            (concat
              ;; move children under prev, in order
              (map (fn [c] [:mv {:id c :to prev :at [:last]}]) kids)
              ;; delete the now-empty node
              [[:del {:id id}]]))))

      ;; Indent under previous sibling = move under prev at end
      :indent
      (let [id node-id
            prev (prev-sibling db id)]
        (if prev [[:mv {:id id :to prev :at [:last]}]] []))

      ;; Outdent = move to grandparent after parent
      :outdent
      (let [id node-id
            p (parent-of db id)
            gp (when p (parent-of db p))
            idx (when p (index-of (children-of db p) p))]   ;; idx of parent in grandparent
        (if (and gp p)
          [[:mv {:id id :to gp :at [:index (inc (index-of (children-of db gp) p))]}]]
          []))

      ;; Move up/down = reorder within parent
      :move-up
      (let [id node-id
            p (parent-of db id)
            kids (children-of db p)
            i (index-of kids id)]
        (if (pos? i) [[:reorder {:id id :parent p :to (dec i)}]] []))

      :move-down
      (let [id node-id
            p (parent-of db id)
            kids (children-of db p)
            i (index-of kids id)]
        (if (and (>= i 0) (< (inc i) (count kids)))
          [[:reorder {:id id :parent p :to (inc i)}]]
          []))

      ;; unknown intent
      [])))

(defn compile-intents
  "Map many intents to a single flat tx."
  [db intents]
  (vec (mapcat #(compile-intent db %) intents)))