(ns parser.block-refs
  "Parser for block references in text.

   Block references use the syntax ((block-id)) to embed/transclude
   other blocks inline, inspired by Logseq.")

(def block-ref-pattern
  "Regex pattern matching ((block-id)) references.
   Captures the block ID in group 1."
  #"\(\(([a-zA-Z0-9\-_]+)\)\)")

(defn extract-refs
  "Extract all block references from text.

   Returns sequence of [full-match block-id] tuples.

   Example:
     (extract-refs \"Hello ((a)) and ((b))\")
     => ([\"((a))\" \"a\"] [\"((b))\" \"b\"])"
  [text]
  (when text
    (mapv (fn [[full-match id]] [full-match id])
          (re-seq block-ref-pattern text))))

(defn parse-refs
  "Parse text and return set of referenced block IDs.

   Example:
     (parse-refs \"Hello ((a)) and ((b))\")
     => #{\"a\" \"b\"}"
  [text]
  (set (map second (extract-refs text))))

(defn split-with-refs
  "Split text into alternating plain text and block reference segments.

   Returns vector of {:type :text/:ref :value string/:id id} maps.

   Example:
     (split-with-refs \"Hello ((a)) world ((b)) end\")
     => [{:type :text :value \"Hello \"}
         {:type :ref :id \"a\"}
         {:type :text :value \" world \"}
         {:type :ref :id \"b\"}
         {:type :text :value \" end\"}]"
  [text]
  (when text
    (let [matches (extract-refs text)]
      (loop [remaining text
             matches-left matches
             result []
             offset 0]
        (if-let [[full-match block-id] (first matches-left)]
          (let [idx (.indexOf remaining full-match)
                before (subs remaining 0 idx)
                after (subs remaining (+ idx (count full-match)))]
            (recur after
                   (rest matches-left)
                   (cond-> result
                     (not (empty? before)) (conj {:type :text :value before})
                     true (conj {:type :ref :id block-id}))
                   (+ offset idx (count full-match))))
          ;; No more matches - add remaining text if any
          (cond-> result
            (not (empty? remaining)) (conj {:type :text :value remaining})))))))
