(ns plugins.backlinks-index
  "Backlinks derived index plugin.

   Computes an index of page references across all blocks, enabling O(1)
   lookup of backlinks instead of O(n) scan on every render.

   Structure:
   {:backlinks-by-page {\"page-name-lower\" [{:block-id \"...\"
                                              :block-text \"...\"
                                              :page-id \"...\"
                                              :page-title \"...\"}]}}

   The index is keyed by lowercase page name for case-insensitive lookup.
   Each entry contains all blocks that reference that page, excluding:
   - Blocks in trash (no valid page ancestor)
   - Self-references (blocks on the target page itself)"
  (:require [kernel.derived-registry :as registry]
            [parser.page-refs :as page-refs]))

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- page-title
  "Get the title of a page node."
  [db page-id]
  (get-in db [:nodes page-id :props :title]))

(defn- find-source-page
  "Find the page that contains a block by walking up the parent chain.
   Returns nil if block is in trash or orphaned (no page ancestor)."
  [db block-id]
  (let [parent-of (get-in db [:derived :parent-of])
        all-nodes (:nodes db)]
    (loop [id block-id]
      (let [parent (get parent-of id)]
        (cond
          ;; Reached doc root or trash - return nil
          (or (nil? parent)
              (= parent :doc)
              (= parent :trash))
          nil

          ;; Found a page - return it
          (= :page (get-in all-nodes [parent :type]))
          parent

          ;; Keep traversing up
          :else
          (recur parent))))))

;; ── Index Computation ─────────────────────────────────────────────────────────

(defn compute-backlinks-index
  "Compute the backlinks index for the entire database.

   Returns:
   {:backlinks-by-page {\"page-name-lower\" [{:block-id :block-text :page-id :page-title}]}}

   This is called automatically after every transaction via the plugin system."
  [db]
  (let [all-nodes (:nodes db)

        ;; Collect all backlink entries
        backlink-entries
        (->> all-nodes
             (filter (fn [[_id node]] (= (:type node) :block)))
             (mapcat (fn [[block-id node]]
                       (let [text (get-in node [:props :text] "")
                             refs (page-refs/extract-refs text)]
                         (when (seq refs)
                           (when-let [source-page-id (find-source-page db block-id)]
                             (let [source-title (page-title db source-page-id)]
                               ;; Create one entry per referenced page
                               (map (fn [{:keys [normalized]}]
                                      {:target-page-lower normalized
                                       :source-page-lower (page-refs/normalize-name (or source-title ""))
                                       :entry {:block-id block-id
                                               :block-text text
                                               :page-id source-page-id
                                               :page-title source-title}})
                                    refs)))))))
             ;; Filter out self-references
             (remove (fn [{:keys [target-page-lower source-page-lower]}]
                       (= target-page-lower source-page-lower))))

        ;; Group by target page
        grouped (group-by :target-page-lower backlink-entries)

        ;; Extract just the entry maps
        index (reduce-kv (fn [acc page-lower entries]
                           (assoc acc page-lower (mapv :entry entries)))
                         {}
                         grouped)]

    {:backlinks-by-page index}))

;; ── Registration ──────────────────────────────────────────────────────────────

(registry/register-derived!
  :backlinks
  {:initial compute-backlinks-index
   ;; :apply-tx not implemented — kernel falls back to :initial on every
   ;; transaction. See kernel.derived-registry docstring for the
   ;; incremental contract when the time comes.
   })

;; ══════════════════════════════════════════════════════════════════════════════
;; Query API
;; ══════════════════════════════════════════════════════════════════════════════

(defn get-backlinks
  "Get backlinks for a page from the derived index.

   Args:
     db: Database with derived indexes
     target-title: Title of the page (case-insensitive)

   Returns:
     Vector of {:block-id :block-text :page-id :page-title} maps,
     or empty vector if no backlinks."
  [db target-title]
  (when target-title
    (let [page-lower (page-refs/normalize-name target-title)]
      (get-in db [:derived :backlinks-by-page page-lower] []))))

;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for ensuring plugin is loaded." true)
