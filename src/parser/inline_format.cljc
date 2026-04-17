(ns parser.inline-format
  "Parser for inline text formatting (bold, italic, highlight, strikethrough, math).

   Logseq-style markdown:
   - **bold** or __bold__ → :bold
   - *italic* or _italic_ → :italic
   - ==highlight== → :highlight
   - ~~strikethrough~~ → :strikethrough
   - $$block math$$ → :math-block
   - $inline math$ → :math-inline

   Returns segments with {:type :text/:bold/:italic/:highlight/:strikethrough/:math-block/:math-inline
                         :value \"content\"}"
  (:require [clojure.string :as str]))

;; Marker definitions - order matters (longer markers first to avoid partial matches)
(def ^:private markers
  [["$$" :math-block]
   ["**" :bold]
   ["__" :bold]
   ["==" :highlight]
   ["~~" :strikethrough]
   ["$" :math-inline]
   ["*" :italic]
   ["_" :italic]])

;; Single-char markers need word-boundary guards so intraword runs like
;; `cljs_core_key` or `cljs$core$key` don't become italic/math. Multi-char
;; markers (`**`, `__`, `==`, `~~`, `$$`) are uncommon in normal text so
;; they stay greedy.
(def ^:private word-boundary-markers #{"_" "*" "$"})

;; Unicode-aware word class: any letter (\p{L}) or digit (\p{N}).
;; CLJS needs the /u flag to enable Unicode property escapes, so the
;; CLJS branch builds the regex via js/RegExp. The JVM flavour handles
;; \p{L}/\p{N} as Unicode properties natively in Pattern.
(def ^:private word-char-re
  #?(:clj  #"\p{L}|\p{N}"
     :cljs (js/RegExp. "\\p{L}|\\p{N}" "u")))

(defn- word-char?
  "True for any Unicode letter or digit. Used to guard single-char markers
   against intraword false positives (cljs_core_key, привет_мир_тест,
   変数_名前). `ch` may be a java.lang.Character or a 1-char string."
  [ch]
  (when ch
    (let [s (str ch)]
      (and (= 1 (count s))
           (boolean (re-matches word-char-re s))))))

(defn- whitespace?
  "Portable whitespace check for a 1-char string or Character."
  [ch]
  (when ch
    (let [s (str ch)]
      (and (= 1 (count s))
           (boolean (re-matches #"\s" s))))))

(defn- find-marker-at
  "Check if any marker starts at pos. Returns [marker type] or nil."
  [text pos]
  (when (< pos (count text))
    (some (fn [[marker type]]
            (let [end-pos (+ pos (count marker))]
              (when (and (<= end-pos (count text))
                         (= marker (subs text pos end-pos)))
                [marker type])))
          markers)))

(defn- find-closing
  "Find closing marker. Returns position after closing marker, or nil."
  [text marker start-pos]
  (let [content-start (+ start-pos (count marker))
        close-idx (str/index-of text marker content-start)]
    (when (and close-idx (< content-start close-idx))  ; Must have content
      (+ close-idx (count marker)))))

(defn- scan-next-marker
  "Scan forward to find next marker. Returns {:marker m :type t :pos p} or nil."
  [text start-pos]
  (loop [pos start-pos]
    (when (< pos (count text))
      (if-let [[marker type] (find-marker-at text pos)]
        {:marker marker :type type :pos pos}
        (recur (inc pos))))))

(defn- add-text-if-nonempty
  "Add text segment if range is non-empty."
  [segments text start end]
  (if (< start end)
    (conj segments {:type :text :value (subs text start end)})
    segments))

(defn- outer-boundary-ok?
  "Single-char markers (`_`, `*`, `$`) require non-word (or absent) chars
   immediately OUTSIDE the marker span. Keeps identifiers like
   `cljs_core_key`, `cljs$core$key`, or `x*y*z` literal."
  [text pos marker close-pos]
  (if-not (contains? word-boundary-markers marker)
    true
    (let [before (when (pos? pos) (nth text (dec pos)))
          after (when (< close-pos (count text)) (nth text close-pos))]
      (and (not (word-char? before))
           (not (word-char? after))))))

(defn- inner-boundary-ok?
  "CommonMark flanking rule for ALL markers: the opener must not be
   followed by whitespace, and the closer must not be preceded by
   whitespace. Keeps spaced operators like `x = a * b * c` literal and
   rejects malformed spans like `** foo **`."
  [text pos marker close-pos]
  (let [mlen (count marker)
        first-inside (+ pos mlen)
        last-inside (- close-pos mlen 1)]
    (and (< first-inside (count text))
         (<= 0 last-inside)
         (not (whitespace? (nth text first-inside)))
         (not (whitespace? (nth text last-inside))))))

(defn- try-complete-format
  "Try to complete a format region. Returns {:end-pos n :segment {...}} or nil."
  [text pos marker type]
  (when-let [close-pos (find-closing text marker pos)]
    (when (and (outer-boundary-ok? text pos marker close-pos)
               (inner-boundary-ok? text pos marker close-pos))
      (let [content-start (+ pos (count marker))
            content-end (- close-pos (count marker))
            content (subs text content-start content-end)]
        {:end-pos close-pos
         :segment {:type type :value content}}))))

(defn split-with-formatting
  "Split text into segments with inline formatting.

   Returns vector of {:type :text/:bold/:italic/:highlight/:strikethrough
                      :value \"content\"}

   Example:
   (split-with-formatting \"hello **world** test\")
   => [{:type :text :value \"hello \"}
       {:type :bold :value \"world\"}
       {:type :text :value \" test\"}]"
  [text]
  (if (or (nil? text) (str/blank? text))
    [{:type :text :value (or text "")}]
    (loop [pos 0
           segments []]
      (if (>= pos (count text))
        ;; End of text - return segments or default to plain text
        (if (empty? segments)
          [{:type :text :value text}]
          segments)
        ;; Look for next marker
        (if-let [{mtype :type mmarker :marker mpos :pos} (scan-next-marker text pos)]
          ;; Try to complete the format region
          (if-let [{:keys [end-pos segment]} (try-complete-format text mpos mmarker mtype)]
            ;; Complete region - add any text before marker, then formatted segment
            (recur end-pos
                   (-> segments
                       (add-text-if-nonempty text pos mpos)
                       (conj segment)))
            ;; Unclosed marker - treat as text and continue after it
            (let [next-pos (+ mpos (count mmarker))]
              (recur next-pos
                     (add-text-if-nonempty segments text pos next-pos))))
          ;; No more markers - add remaining text
          (add-text-if-nonempty segments text pos (count text)))))))

(defn has-formatting?
  "Quick check if text contains any inline formatting markers."
  [text]
  (and (string? text)
       (or (str/includes? text "$$")
           (str/includes? text "**")
           (str/includes? text "__")
           (str/includes? text "==")
           (str/includes? text "~~")
           ;; Single markers - check for pairs
           (re-find #"\$[^$]+\$" text)
           (re-find #"\*[^*]+\*" text)
           (re-find #"_[^_]+_" text))))

(defn has-math?
  "True iff parsing `text` produces at least one math segment.

   Authoritative source for the view layer's MathJax gating — prefer
   this over raw regex checks so that code-like inputs (e.g.
   `cljs$core$key`, `price$100$total`) do not trigger spurious
   typeset passes after the word-boundary guards reject them."
  [text]
  (and (string? text)
       (boolean (some #(or (= :math-inline (:type %))
                           (= :math-block (:type %)))
                      (split-with-formatting text)))))
