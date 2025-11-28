(ns parser.page-refs
  "Parser for page references in text.

   Page references use the syntax [[page-name]] to link to other pages,
   inspired by Logseq."
  (:require [clojure.string :as str]))

(def page-ref-pattern
  "Regex pattern matching [[page-name]] references.
   Captures the page name in group 1.
   Allows alphanumeric, spaces, hyphens, underscores, forward slashes (for namespaces)."
  #"\[\[([a-zA-Z0-9\s\-_/]+)\]\]")

(defn extract-refs
  "Extract all page references from text.

   Returns sequence of [full-match page-name] tuples.

   Example:
     (extract-refs \"See [[Projects]] and [[Tasks]]\")
     => ([\"[[Projects]]\" \"Projects\"] [\"[[Tasks]]\" \"Tasks\"])"
  [text]
  (when text
    (mapv (fn [[full-match page-name]] [full-match page-name])
          (re-seq page-ref-pattern text))))

(defn parse-refs
  "Parse text and return set of referenced page names.

   Example:
     (parse-refs \"See [[Projects]] and [[Tasks]]\")
     => #{\"Projects\" \"Tasks\"}"
  [text]
  (set (map second (extract-refs text))))

(defn split-with-refs
  "Split text into alternating plain text and page reference segments.

   Returns vector of {:type :text/:page-ref :value string/:page string} maps.

   Example:
     (split-with-refs \"See [[Projects]] and [[Tasks]] now\")
     => [{:type :text :value \"See \"}
         {:type :page-ref :page \"Projects\"}
         {:type :text :value \" and \"}
         {:type :page-ref :page \"Tasks\"}
         {:type :text :value \" now\"}]"
  [text]
  (when text
    (let [matches (extract-refs text)]
      (loop [remaining text
             matches-left matches
             result []
             offset 0]
        (if-let [[full-match page-name] (first matches-left)]
          (let [idx (.indexOf remaining full-match)
                before (subs remaining 0 idx)
                after (subs remaining (+ idx (count full-match)))]
            (recur after
                   (rest matches-left)
                   (cond-> result
                     (not (empty? before)) (conj {:type :text :value before})
                     true (conj {:type :page-ref :page page-name}))
                   (+ offset idx (count full-match))))
          ;; No more matches - add remaining text if any
          (cond-> result
            (not (empty? remaining)) (conj {:type :text :value remaining})))))))

(defn normalize-page-name
  "Normalize page name to canonical form for lookups.

   - Trims whitespace
   - Converts to lowercase for case-insensitive matching

   Example:
     (normalize-page-name \"  My Projects  \")
     => \"my projects\""
  [page-name]
  (when page-name
    (-> page-name
        str/trim
        str/lower-case)))
