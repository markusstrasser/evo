(ns parser.page-refs
  "Parser for page references in text.

   Page references use the syntax [[page-name]] to link to other pages,
   inspired by Logseq."
  (:require [clojure.string :as str]))

(defn- char-code [ch]
  #?(:clj (int ch)
     :cljs (.charCodeAt (str ch) 0)))

(defn valid-name?
  "True when S is a valid page-ref name.

   Grammar is intentionally small: non-empty after trim; no brackets,
   newlines, or control characters. Unicode, punctuation, spaces, and slashes
   are valid."
  [s]
  (and (string? s)
       (not (str/blank? (str/trim s)))
       (every? (fn [ch]
                 (let [code (char-code ch)]
                   (and (not= ch \[)
                        (not= ch \])
                        (not= ch \newline)
                        (not= ch \return)
                        (>= code 32)
                        (not= code 127))))
               s)))

(defn normalize-name
  "Normalize page name to canonical form for lookups."
  [page-name]
  (when page-name
    (-> page-name
        str/trim
        str/lower-case)))

(defn format-ref
  "Return canonical [[page]] source for REF-NAME. Throws for invalid names."
  [ref-name]
  (let [trimmed (some-> ref-name str/trim)]
    (when-not (valid-name? trimmed)
      (throw (ex-info "Invalid page reference name"
                      {:name ref-name
                       :hint "Page reference names must be non-empty and cannot contain brackets, newlines, or control characters"})))
    (str "[[" trimmed "]]")))

(defn- match-at
  [text start]
  (when-let [end-open (str/index-of text "]]" (+ start 2))]
    (let [inner-start (+ start 2)
          inner-end end-open
          raw (subs text start (+ end-open 2))
          display (subs text inner-start inner-end)
          ref-name (str/trim display)]
      (when (valid-name? ref-name)
        {:type :page-ref
         :raw raw
         :display display
         :name ref-name
         :normalized (normalize-name ref-name)
         :start start
         :end (+ end-open 2)
         :inner-start inner-start
         :inner-end inner-end}))))

(defn- scan-refs
  [text]
  (when text
    (let [n (count text)]
      (loop [pos 0
             refs []]
        (if-let [start (and (< pos n) (str/index-of text "[[" pos))]
          (let [end-open (str/index-of text "]]" (+ start 2))
                next-open (str/index-of text "[[" (+ start 2))]
            (cond
              (nil? end-open)
              refs

              ;; Another opening before the close means the first opening is
              ;; either unclosed text or a nested ambiguous ref. If there is a
              ;; second close before the next ref starts, reject the whole
              ;; nested candidate; otherwise resume at the later opening so a
              ;; typo does not swallow a valid ref that follows it.
              (and next-open (< next-open end-open))
              (let [next-close-after (str/index-of text "]]" (+ end-open 2))
                    next-open-after (str/index-of text "[[" (+ end-open 2))]
                (if (and next-close-after
                         (or (nil? next-open-after)
                             (< next-close-after next-open-after)))
                  (recur (+ next-close-after 2) refs)
                  (recur next-open refs)))

              :else
              (if-let [match (match-at text start)]
                (recur (:end match) (conj refs match))
                (recur (+ end-open 2) refs))))
          refs)))))

(defn extract-refs
  "Extract all page references from text.

   Returns records with :raw, :name, :normalized, :start, and :end.

   Example:
     (extract-refs \"See [[Projects]] and [[Tasks]]\")
     => [{:raw \"[[Projects]]\" :name \"Projects\" ...} ...]"
  [text]
  (vec (or (scan-refs text) [])))

(defn parse-refs
  "Parse text and return set of referenced page names.

   Example:
     (parse-refs \"See [[Projects]] and [[Tasks]]\")
     => #{\"Projects\" \"Tasks\"}"
  [text]
  (set (map :name (extract-refs text))))

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
    (let [matches (extract-refs text)
          {:keys [result cursor]}
          (reduce
            (fn [{:keys [cursor result]} {:keys [start end] :as match}]
              (let [before (subs text cursor start)
                    ref-name (:name match)
                    result' (cond-> result
                              (seq before) (conj {:type :text :value before})
                              true (conj (assoc match :page ref-name)))]
                {:cursor end :result result'}))
            {:cursor 0 :result []}
            matches)]
      (cond-> result
        (< cursor (count text)) (conj {:type :text :value (subs text cursor)})))))

(def normalize-page-name normalize-name)

(defn ref-at
  "Return the page-ref record enclosing CURSOR-POS, or nil."
  [text cursor-pos]
  (some (fn [{:keys [start end inner-start inner-end] :as match}]
          (when (and (<= start cursor-pos end)
                     (<= inner-start cursor-pos inner-end))
            (assoc match :complete? true :page-name (:name match))))
        (extract-refs text)))

(defn replace-refs
  "Replace refs whose normalized name equals OLD-NAME with [[NEW-NAME]]."
  [text old-name new-name]
  (let [old-normalized (normalize-name old-name)
        replacement (format-ref new-name)]
    (apply str
           (map (fn [{:keys [type value raw normalized]}]
                  (cond
                    (= type :text) value
                    (= normalized old-normalized) replacement
                    :else raw))
                (or (split-with-refs text) [])))))

;; ── AST adapter ──────────────────────────────────────────────────────────────

(defn- segment->ast-node
  [{:keys [type value page]}]
  (case type
    :text     [:text {} (or value "")]
    :page-ref [:page-ref {:name page} [[:text {} page]]]))

(defn parse
  "Parse TEXT into a vector of AST nodes, extracting [[page-refs]].

   See `parser.ast` for the node shape."
  [text]
  (mapv segment->ast-node (or (split-with-refs text) [])))
