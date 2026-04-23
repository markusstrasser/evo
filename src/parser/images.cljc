(ns parser.images
  "Parser for markdown image syntax with optional attributes.

   Image syntax: ![alt text](path/to/image.png){width=400}

   Supports:
   - Relative paths: ![](../assets/image.png)
   - Absolute URLs: ![](https://example.com/image.png)
   - Alt text: ![A descriptive caption](path.png)
   - Width attribute: ![](path.png){width=400}
   - Balanced parens in the URL path (CommonMark): ![](…/Foo_(bar).png)

   The path scanner tracks paren depth so URLs like Wikipedia links with
   `(…)` segments survive round-trip. Whitespace inside the path still
   terminates the match (Markdown convention)."
  (:require [clojure.string :as str]))

(def ^:private width-suffix-re
  "Optional {width=N} attribute immediately after the closing paren."
  #"^\{width=(\d+)\}")

;; ── Parsing Helpers ──────────────────────────────────────────────────────────

(defn- parse-width
  "Parse width string to integer, returns nil if invalid."
  [width-str]
  (when width-str
    #?(:clj (try (Integer/parseInt width-str) (catch Exception _ nil))
       :cljs (let [n (js/parseInt width-str 10)]
               (when-not (js/isNaN n) n)))))

(defn- ws?
  "Whitespace inside a Markdown URL path terminates the match."
  [ch]
  (or (= ch \space) (= ch \tab) (= ch \newline) (= ch \return)))

(defn- find-balanced-close
  "Given the index of an opening `(`, scan forward tracking paren depth
   and return the index of the matching `)`. Returns nil if the path
   contains whitespace or never closes."
  [text open-idx]
  (let [n (count text)]
    (loop [i (inc open-idx)
           depth 1]
      (if (>= i n)
        nil
        (let [ch (nth text i)]
          (cond
            (ws? ch)       nil
            (= ch \()      (recur (inc i) (inc depth))
            (= ch \))      (if (= depth 1)
                             i
                             (recur (inc i) (dec depth)))
            :else          (recur (inc i) depth)))))))

(defn- find-image-at
  "If `![alt](path){width=N}?` starts at `pos`, return a map with
   :alt :path :width :start :end :full-match. Otherwise nil."
  [text pos]
  (let [n (count text)]
    (when (and (< (+ pos 2) n)
               (= \! (nth text pos))
               (= \[ (nth text (inc pos))))
      (when-let [alt-end (str/index-of text "]" (+ pos 2))]
        (let [paren-open (inc alt-end)]
          (when (and (< paren-open n) (= \( (nth text paren-open)))
            (when-let [paren-close (find-balanced-close text paren-open)]
              (let [alt (subs text (+ pos 2) alt-end)
                    path (subs text (inc paren-open) paren-close)
                    ;; Path must be non-empty
                    _ (when (str/blank? path) nil)
                    width-start (inc paren-close)
                    width-hit (when (< width-start n)
                                (re-find width-suffix-re (subs text width-start)))
                    width-str (when width-hit (second width-hit))
                    width-len (if width-hit (count (first width-hit)) 0)
                    end (+ paren-close 1 width-len)]
                (when-not (str/blank? path)
                  (cond-> {:alt alt
                           :path path
                           :start pos
                           :end end
                           :full-match (subs text pos end)}
                    width-str (assoc :width (parse-width width-str))))))))))))

(defn- scan-images
  "Walk the text left-to-right, returning every image match in order as
   maps with :start :end :alt :path :width :full-match."
  [text]
  (when (and text (string? text))
    (loop [pos 0
           acc (transient [])]
      (let [idx (when (< pos (count text)) (str/index-of text "![" pos))]
        (cond
          (nil? idx)
          (persistent! acc)

          :else
          (if-let [m (find-image-at text idx)]
            (recur (:end m) (conj! acc m))
            ;; Not actually an image at idx; advance past the `!` and retry.
            (recur (inc idx) acc)))))))

;; ── Public API ───────────────────────────────────────────────────────────────

(defn extract-images
  "Extract all image references from text.

   Returns seq of {:alt \"alt text\" :path \"image/path.png\" :width 400},
   or nil when there are no valid image matches. Width is omitted if
   not specified."
  [text]
  (when (and text (str/includes? text "!["))
    (let [hits (scan-images text)]
      (when (seq hits)
        (mapv (fn [{:keys [alt path width]}]
                (cond-> {:alt alt :path path}
                  width (assoc :width width)))
              hits)))))

(defn split-with-images
  "Split text into segments of plain text and image references.

   Returns vector of {:type :text/:image :value text :alt alt :path path :width N}.

   Example:
     (split-with-images \"Before ![cat](cat.png){width=200} after\")
     => [{:type :text :value \"Before \"}
         {:type :image :alt \"cat\" :path \"cat.png\" :width 200}
         {:type :text :value \" after\"}]"
  [text]
  (if (or (nil? text) (not (str/includes? text "![")))
    [{:type :text :value text}]
    (let [matches (scan-images text)
          n (count text)]
      (loop [pos 0
             remaining matches
             acc (transient [])]
        (if (empty? remaining)
          (persistent!
            (if (< pos n)
              (conj! acc {:type :text :value (subs text pos)})
              acc))
          (let [{:keys [start end alt path width]} (first remaining)
                acc' (cond-> acc
                       (< pos start)
                       (conj! {:type :text :value (subs text pos start)})
                       true
                       (conj! (cond-> {:type :image :alt alt :path path}
                                width (assoc :width width))))]
            (recur end (rest remaining) acc')))))))

(defn image?
  "Check if text contains any image references."
  [text]
  (and (string? text)
       (str/includes? text "![")
       (boolean (seq (scan-images text)))))

(defn path->filename
  "Extract filename from an image path.

   Example: \"../assets/cat_123_0.png\" => \"cat_123_0.png\""
  [path]
  (when path
    (last (str/split path #"/"))))

(defn asset-path?
  "Check if path is a relative asset path (../assets/ or ./assets/)."
  [path]
  (when path
    (or (str/starts-with? path "../assets/")
        (str/starts-with? path "./assets/")
        (str/starts-with? path "assets/"))))

;; ── Formatting Functions ─────────────────────────────────────────────────────

(defn format-image
  "Format image as markdown string.

   Args:
     path - Image path (required)
     alt - Alt text (optional, defaults to empty)
     width - Display width in pixels (optional)

   Examples:
     (format-image \"cat.png\" \"A cat\" nil)    => \"![A cat](cat.png)\"
     (format-image \"cat.png\" \"\" 400)         => \"![](cat.png){width=400}\""
  ([path] (format-image path "" nil))
  ([path alt] (format-image path alt nil))
  ([path alt width]
   (let [base (str "![" (or alt "") "](" path ")")]
     (if width
       (str base "{width=" width "}")
       base))))

;; ── AST adapter ──────────────────────────────────────────────────────────────

(defn- segment->ast-node
  [{:keys [type value alt path width]}]
  (case type
    :text  [:text {} (or value "")]
    :image [:image (cond-> {:path path :alt (or alt "")}
                     width (assoc :width width))
            []]))

(defn parse
  "Parse TEXT into a vector of AST nodes, extracting ![alt](path){width=N}.

   See `parser.ast` for the node shape."
  [text]
  (mapv segment->ast-node (split-with-images text)))

(defn update-image-width
  "Update or add width attribute to every image in text.

   If width is nil, removes the width attribute.

   Example:
     (update-image-width \"![cat](cat.png)\" 400)
     => \"![cat](cat.png){width=400}\"

     (update-image-width \"![cat](cat.png){width=200}\" 400)
     => \"![cat](cat.png){width=400}\"

     (update-image-width \"![cat](cat.png){width=200}\" nil)
     => \"![cat](cat.png)\""
  [text new-width]
  (let [matches (scan-images text)]
    (if (empty? matches)
      text
      (loop [pos 0
             remaining matches
             out []]
        (if (empty? remaining)
          (apply str (conj out (subs text pos)))
          (let [{:keys [start end alt path]} (first remaining)]
            (recur end
                   (rest remaining)
                   (conj out
                         (subs text pos start)
                         (format-image path alt new-width)))))))))
