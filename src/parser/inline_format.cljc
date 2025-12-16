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

(defn- find-next-marker
  "Find the next opening marker in text starting at pos.
   Returns {:marker m :type t :start-pos n} or nil."
  [text pos]
  (let [remaining (subs text pos)]
    (->> markers
         (keep (fn [[marker type]]
                 (let [idx (str/index-of remaining marker)]
                   (when idx
                     {:marker marker
                      :type type
                      :start-pos (+ pos idx)}))))
         (sort-by :start-pos)
         first)))

(defn- find-closing-marker
  "Find closing marker after open-pos.
   Returns end position (exclusive of marker) or nil."
  [text marker open-pos]
  (let [content-start (+ open-pos (count marker))
        remaining (subs text content-start)
        close-idx (str/index-of remaining marker)]
    (when (and close-idx (pos? close-idx)) ; Must have content between markers
      (+ content-start close-idx (count marker)))))

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
           result []]
      (if (>= pos (count text))
        (if (empty? result)
          [{:type :text :value text}]
          result)
        (if-let [{:keys [marker type start-pos]} (find-next-marker text pos)]
          (if-let [end-pos (find-closing-marker text marker start-pos)]
            ;; Found complete formatted section
            (let [content-start (+ start-pos (count marker))
                  content-end (- end-pos (count marker))
                  content (subs text content-start content-end)
                  ;; Add text before marker if any
                  before-text (when (> start-pos pos)
                                {:type :text :value (subs text pos start-pos)})
                  ;; Add formatted segment
                  formatted {:type type :value content}]
              (recur end-pos
                     (cond-> result
                       before-text (conj before-text)
                       true (conj formatted))))
            ;; No closing marker - treat as plain text up to next potential match
            (let [next-pos (+ start-pos (count marker))]
              (recur next-pos
                     (conj result {:type :text :value (subs text pos next-pos)}))))
          ;; No more markers - add remaining text
          (conj result {:type :text :value (subs text pos)}))))))

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
