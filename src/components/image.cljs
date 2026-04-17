(ns components.image
  "Image component for rendering images.

   Handles:
   - Local asset images (../assets/...) via blob URLs
   - External URLs (https://...) directly
   - Loading states and error fallbacks
   - Resize handles for block-level images
   - Lightbox view on click

   Used for:
   - Block-level images (:image block type)
   - Inline images in text (legacy, being phased out)"
  (:require [clojure.string :as str]
            [shell.storage :as storage]
            [components.lightbox :as lightbox]))

;; ── Resize State ─────────────────────────────────────────────────────────────
;; Track active resize operation

(defonce !resize-state (atom nil))

;; Cache of path -> blob URL for local assets.
;; Avoids re-fetching the same asset multiple times.
(defonce !url-cache (atom {}))

;; ── Path Helpers ─────────────────────────────────────────────────────────────

(defn asset-path?
  "Check if path is a relative asset path (../assets/ or ./assets/)."
  [path]
  (when path
    (or (str/starts-with? path "../assets/")
        (str/starts-with? path "./assets/")
        (str/starts-with? path "assets/"))))

(defn path->filename
  "Extract filename from an image path.
   Example: \"../assets/cat_123_0.png\" => \"cat_123_0.png\""
  [path]
  (when path
    (last (str/split path #"/"))))

(defn external-url?
  "Check if path is an external URL (http/https)."
  [path]
  (when path
    (or (str/starts-with? path "http://")
        (str/starts-with? path "https://"))))

;; ── URL Resolution ───────────────────────────────────────────────────────────

(defn- resolve-asset-url
  "Resolve an asset path to a displayable URL.

   - External URLs (http/https): return as-is
   - Local assets: fetch via storage and return blob URL"
  [path on-resolved]
  (cond
    ;; External URL - use directly
    (external-url? path)
    (on-resolved path)

    ;; Check cache first
    (get @!url-cache path)
    (on-resolved (get @!url-cache path))

    ;; Local asset - fetch via storage
    (asset-path? path)
    (let [filename (path->filename path)]
      (-> (storage/get-asset-url filename)
          (.then (fn [blob-url]
                   (when blob-url
                     (swap! !url-cache assoc path blob-url))
                   (on-resolved blob-url)))
          (.catch (fn [_err]
                    (on-resolved nil)))))

    ;; Unknown path format
    :else
    (on-resolved nil)))

;; ── Image Components ─────────────────────────────────────────────────────────

(defn Image
  "Render an image element.

   Props:
   - path: Image path (relative asset or URL)
   - alt: Alt text for accessibility
   - width: Display width in pixels (optional)
   - block-level?: true for full-width block images, false for inline (default)"
  [{:keys [path alt width block-level?]}]
  (let [external? (external-url? path)
        cached-url (when-not external? (get @!url-cache path))
        src (cond external? path, cached-url cached-url, :else nil)
        css-class (if block-level? "block-image" "inline-image")
        style (when width {:width (str width "px") :height "auto"})]

    (if (or external? cached-url)
      ;; External or cached - render with src immediately
      [:img {:class css-class
             :replicant/key (str "img-" (hash path))
             :src src
             :alt (if (str/blank? alt) "Image" alt)
             :style style
             :loading "lazy"
             :on {:load (fn [e]
                          (set! (.. e -target -style -display) ""))
                  :error (fn [e]
                           (set! (.. e -target -style -display) "none"))
                  :click (fn [e]
                           (.stopPropagation e)
                           (when (and src (not (str/blank? src)))
                             (lightbox/show! {:src src :alt alt})))}}]

      ;; Local asset not cached - fetch and find element by data attribute
      [:img {:class css-class
             :replicant/key (str "img-" (hash path))
             :alt (if (str/blank? alt) "Image" alt)
             :style style
             :loading "lazy"
             :data-asset-path path
             :replicant/on-render
             (fn [_el _]
               (resolve-asset-url
                path
                (fn [url]
                  (when url
                    (doseq [el (array-seq (js/document.querySelectorAll
                                           (str "img[data-asset-path=\"" path "\"]")))]
                      (when (str/blank? (.-src el))
                        (set! (.-src el) url)))))))
             :on {:load (fn [e]
                          (set! (.. e -target -style -display) ""))
                  :error (fn [e]
                           (set! (.. e -target -style -display) "none"))
                  :click (fn [e]
                           (.stopPropagation e)
                           (let [img-src (.-src (.-target e))]
                             (when (and img-src (not (str/blank? img-src)))
                               (lightbox/show! {:src img-src :alt alt}))))}}])))

;; ── Resize Handlers ──────────────────────────────────────────────────────────

;; Forward declarations for event handlers
(declare handle-resize-move handle-resize-end)

(defn start-resize!
  "Begin resize operation. Captures initial state."
  [e block-id current-width aspect-ratio on-intent]
  (.preventDefault e)
  (.stopPropagation e)
  (let [start-x (.-clientX e)]
    (reset! !resize-state
            {:block-id block-id
             :start-x start-x
             :start-width (or current-width 400)
             :aspect-ratio aspect-ratio
             :on-intent on-intent})
    ;; Add body class for cursor
    (.. js/document -body -classList (add "resizing-image"))
    ;; Add global listeners
    (js/document.addEventListener "mousemove" handle-resize-move)
    (js/document.addEventListener "mouseup" handle-resize-end)))

(defn- handle-resize-move
  "Handle mouse move during resize."
  [e]
  (when-let [{:keys [block-id start-x start-width]} @!resize-state]
    (let [delta (- (.-clientX e) start-x)
          new-width (max 100 (min 1200 (+ start-width delta)))]
      ;; Update element directly for smooth feedback
      (when-let [el (js/document.querySelector (str "[data-block-id=\"" block-id "\"] .block-image"))]
        (set! (.. el -style -width) (str new-width "px"))))))

(defn- handle-resize-end
  "Complete resize operation. Fire intent to persist."
  [_e]
  (when-let [{:keys [block-id start-width on-intent]} @!resize-state]
    (let [el (js/document.querySelector (str "[data-block-id=\"" block-id "\"] .block-image"))
          final-width (if el
                        (js/parseInt (.. el -style -width) 10)
                        start-width)]
      ;; Fire intent to persist new width
      (when (and on-intent (not= final-width start-width))
        (on-intent {:type :resize-image
                    :block-id block-id
                    :width final-width})))
    ;; Cleanup
    (reset! !resize-state nil)
    (.. js/document -body -classList (remove "resizing-image"))
    (js/document.removeEventListener "mousemove" handle-resize-move)
    (js/document.removeEventListener "mouseup" handle-resize-end)))

(defn clear-url-cache!
  "Clear the blob URL cache. Call when folder changes."
  []
  ;; Revoke existing blob URLs to free memory
  (doseq [[_ url] @!url-cache]
    (when (str/starts-with? url "blob:")
      (js/URL.revokeObjectURL url)))
  (reset! !url-cache {}))
