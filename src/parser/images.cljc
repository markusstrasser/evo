(ns parser.images
  "Parser for markdown image syntax with optional attributes.

   Image syntax: ![alt text](path/to/image.png){width=400}

   Supports:
   - Relative paths: ![](../assets/image.png)
   - Absolute URLs: ![](https://example.com/image.png)
   - Alt text: ![A descriptive caption](path.png)
   - Width attribute: ![](path.png){width=400}"
  (:require [clojure.string :as str]))

;; ── Image Regex ──────────────────────────────────────────────────────────────
;; Pattern: ![alt](path){width=N}
;; - ! marks it as an image (vs regular link)
;; - [alt] is optional alt text (can be empty)
;; - (path) is the image path/URL
;; - {width=N} is optional width attribute

(def image-pattern
  "Regex to match markdown image syntax: ![alt](path) with optional {width=N}"
  #"!\[([^\]]*)\]\(([^)]+)\)(?:\{width=(\d+)\})?")

(def width-attr-pattern
  "Regex to extract width from {width=N} suffix"
  #"\{width=(\d+)\}")

;; ── Parsing Functions ────────────────────────────────────────────────────────

(defn- parse-width
  "Parse width string to integer, returns nil if invalid."
  [width-str]
  (when width-str
    #?(:clj (try (Integer/parseInt width-str) (catch Exception _ nil))
       :cljs (let [n (js/parseInt width-str 10)]
               (when-not (js/isNaN n) n)))))

(defn extract-images
  "Extract all image references from text.

   Returns seq of {:alt \"alt text\" :path \"image/path.png\" :width 400}
   Width is nil if not specified."
  [text]
  (when (and text (str/includes? text "!["))
    (->> (re-seq image-pattern text)
         (map (fn [[_full alt path width-str]]
                (cond-> {:alt alt :path path}
                  width-str (assoc :width (parse-width width-str))))))))

(defn split-with-images
  "Split text into segments of plain text and image references.

   Returns vector of {:type :text/:image :value text :alt alt :path path :width N}

   Example:
     (split-with-images \"Before ![cat](cat.png){width=200} after\")
     => [{:type :text :value \"Before \"}
         {:type :image :alt \"cat\" :path \"cat.png\" :width 200}
         {:type :text :value \" after\"}]"
  [text]
  (if (or (nil? text) (not (str/includes? text "![")))
    ;; Fast path: no images possible
    [{:type :text :value text}]
    ;; Parse images
    (let [matches (re-seq image-pattern text)
          result (atom [])
          pos (atom 0)]
      (doseq [[full-match alt path width-str] matches]
        (let [match-start (str/index-of text full-match @pos)]
          ;; Add text before this match
          (when (> match-start @pos)
            (swap! result conj {:type :text
                                :value (subs text @pos match-start)}))
          ;; Add the image with optional width
          (swap! result conj (cond-> {:type :image
                                      :alt alt
                                      :path path}
                               width-str (assoc :width (parse-width width-str))))
          ;; Move position past this match
          (reset! pos (+ match-start (count full-match)))))
      ;; Add remaining text
      (when (< @pos (count text))
        (swap! result conj {:type :text
                            :value (subs text @pos)}))
      @result)))

(defn image?
  "Check if text contains any image references."
  [text]
  (and text
       (str/includes? text "![")
       (re-find image-pattern text)))

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

(defn update-image-width
  "Update or add width attribute to an image markdown string.

   If text contains an image, updates its width attribute.
   If width is nil, removes the width attribute.

   Example:
     (update-image-width \"![cat](cat.png)\" 400)
     => \"![cat](cat.png){width=400}\"

     (update-image-width \"![cat](cat.png){width=200}\" 400)
     => \"![cat](cat.png){width=400}\"

     (update-image-width \"![cat](cat.png){width=200}\" nil)
     => \"![cat](cat.png)\""
  [text new-width]
  (if-not (image? text)
    text
    (let [;; First, strip any existing width attribute
          base-text (str/replace text width-attr-pattern "")]
      (if new-width
        ;; Add new width after the closing paren of each image
        (str/replace base-text
                     #"(!\[[^\]]*\]\([^)]+\))"
                     (str "$1{width=" new-width "}"))
        ;; No width - return base text
        base-text))))
