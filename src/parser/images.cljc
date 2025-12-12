(ns parser.images
  "Parser for markdown image syntax.
   
   Image syntax: ![alt text](path/to/image.png)
   
   Supports:
   - Relative paths: ![](../assets/image.png)
   - Absolute URLs: ![](https://example.com/image.png)
   - Alt text: ![A descriptive caption](path.png)"
  (:require [clojure.string :as str]))

;; ── Image Regex ──────────────────────────────────────────────────────────────
;; Pattern: ![alt](path)
;; - ! marks it as an image (vs regular link)
;; - [alt] is optional alt text (can be empty)
;; - (path) is the image path/URL

(def image-pattern
  "Regex to match markdown image syntax: ![alt](path)"
  #"!\[([^\]]*)\]\(([^)]+)\)")

;; ── Parsing Functions ────────────────────────────────────────────────────────

(defn extract-images
  "Extract all image references from text.
   
   Returns seq of {:alt \"alt text\" :path \"image/path.png\"}"
  [text]
  (when (and text (str/includes? text "!["))
    (->> (re-seq image-pattern text)
         (map (fn [[_full alt path]]
                {:alt alt :path path})))))

(defn split-with-images
  "Split text into segments of plain text and image references.
   
   Returns vector of {:type :text/:image :value text :alt alt :path path}
   
   Example:
     (split-with-images \"Before ![cat](cat.png) after\")
     => [{:type :text :value \"Before \"}
         {:type :image :alt \"cat\" :path \"cat.png\"}
         {:type :text :value \" after\"}]"
  [text]
  (if (or (nil? text) (not (str/includes? text "![")))
    ;; Fast path: no images possible
    [{:type :text :value text}]
    ;; Parse images
    (let [matches (re-seq image-pattern text)
          result (atom [])
          pos (atom 0)]
      (doseq [[full-match alt path] matches]
        (let [match-start (str/index-of text full-match @pos)]
          ;; Add text before this match
          (when (> match-start @pos)
            (swap! result conj {:type :text
                                :value (subs text @pos match-start)}))
          ;; Add the image
          (swap! result conj {:type :image
                              :alt alt
                              :path path})
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
