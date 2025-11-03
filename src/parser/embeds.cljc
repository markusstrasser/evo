(ns parser.embeds
  "Parser for block embeds in text.

   Block embeds use the syntax {{embed ((block-id))}} to render
   the full block tree (including children), inspired by Logseq.")

(def embed-pattern
  "Regex pattern matching {{embed ((block-id))}} references.
   Captures the block ID in group 1."
  #"\{\{embed\s+\(\(([a-zA-Z0-9\-_]+)\)\)\}\}")

(defn extract-embeds
  "Extract all block embeds from text.

   Returns sequence of [full-match block-id] tuples.

   Example:
     (extract-embeds \"Hello {{embed ((a))}} and {{embed ((b))}}\")
     => ([\"{{embed ((a))}}\" \"a\"] [\"{{embed ((b))}}\" \"b\"])"
  [text]
  (when text
    (mapv (fn [[full-match id]] [full-match id])
          (re-seq embed-pattern text))))

(defn parse-embeds
  "Parse text and return set of embedded block IDs.

   Example:
     (parse-embeds \"Hello {{embed ((a))}} and {{embed ((b))}}\")
     => #{\"a\" \"b\"}"
  [text]
  (set (map second (extract-embeds text))))

(defn split-with-embeds
  "Split text into alternating plain text and block embed segments.

   Returns vector of {:type :text/:embed :value string/:id id} maps.

   Example:
     (split-with-embeds \"Hello {{embed ((a))}} world\")
     => [{:type :text :value \"Hello \"}
         {:type :embed :id \"a\"}
         {:type :text :value \" world\"}]"
  [text]
  (when text
    (let [matches (extract-embeds text)]
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
                     true (conj {:type :embed :id block-id}))
                   (+ offset idx (count full-match))))
          ;; No more matches - add remaining text if any
          (cond-> result
            (not (empty? remaining)) (conj {:type :text :value remaining})))))))
