(ns utils.storage-reconcile
  "Pure core of page-file reconciliation: the folder is a projection of
   the db, so deletions are part of the projection — not one-shot effects.

   Safety boundary: only files the app itself loaded-or-wrote (the
   'managed' set, owned by shell.storage) are ever deletion candidates.
   A file the user dropped into the folder mid-session is unmanaged and
   untouchable until a reload turns it into a page."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defn sanitize-filename
  "Convert page title to safe filename stem."
  [title]
  (-> (str/lower-case (or title ""))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn page-filename
  "Markdown filename for a page title."
  [title]
  (str (sanitize-filename title) ".md"))

(defn expected-filenames
  "Set of filenames the db projects: one per :page node (including
   trashed pages — they serialize with a trashed-at:: header and must
   keep their files)."
  [db]
  (into #{}
        (comp (filter (fn [[_id node]] (= :page (:type node))))
              (map (fn [[_id node]]
                     (page-filename (get-in node [:props :title])))))
        (:nodes db)))

(defn stray-files
  "Managed files whose page no longer exists in the db — safe to delete.
   Never returns unmanaged files; never returns a file any current page
   still projects to (two titles sanitizing to one filename keep it)."
  [managed db]
  (set/difference (set managed) (expected-filenames db)))
