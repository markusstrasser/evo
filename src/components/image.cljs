(ns components.image
  "Image component for rendering images.

   Handles:
   - Local asset images (../assets/...) via blob URLs
   - External URLs (https://...) directly
   - Loading states and error fallbacks

   Used for:
   - Block-level images (:image block type)
   - Inline images in text (legacy, being phased out)"
  (:require [clojure.string :as str]
            [shell.storage :as storage]))

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
   - block-level?: true for full-width block images, false for inline (default)"
  [{:keys [path alt block-level?]}]
  (let [external? (external-url? path)
        cached-url (when-not external? (get @!url-cache path))
        src (cond external? path, cached-url cached-url, :else nil)
        css-class (if block-level? "block-image" "inline-image")]

    (if (or external? cached-url)
      ;; External or cached - render with src immediately
      [:img {:class css-class
             :replicant/key (str "img-" (hash path))
             :src src
             :alt (if (str/blank? alt) "Image" alt)
             :loading "lazy"
             :on {:load (fn [e]
                          (set! (.. e -target -style -display) ""))
                  :error (fn [e]
                           (set! (.. e -target -style -display) "none"))}}]

      ;; Local asset not cached - fetch and find element by data attribute
      [:img {:class css-class
             :replicant/key (str "img-" (hash path))
             :alt (if (str/blank? alt) "Image" alt)
             :loading "lazy"
             :data-asset-path path
             :replicant/on-render
             (fn [_el _]
               (resolve-asset-url
                path
                (fn [url]
                  (when url
                    (when-let [el (js/document.querySelector
                                   (str "img[data-asset-path=\"" path "\"]"))]
                      (when (str/blank? (.-src el))
                        (set! (.-src el) url)))))))
             :on {:load (fn [e]
                          (set! (.. e -target -style -display) ""))
                  :error (fn [e]
                           (set! (.. e -target -style -display) "none"))}}])))

(defn ImageBlock
  "Render content for an :image block type.

   Props:
   - path: Image path from block props
   - alt: Alt text from block props
   - is-focused: Whether block has focus"
  [{:keys [path alt is-focused]}]
  [:div.image-block-content
   {:class (when is-focused "focused")}
   (Image {:path path :alt alt :block-level? true})])

(defn clear-url-cache!
  "Clear the blob URL cache. Call when folder changes."
  []
  ;; Revoke existing blob URLs to free memory
  (doseq [[_ url] @!url-cache]
    (when (str/starts-with? url "blob:")
      (js/URL.revokeObjectURL url)))
  (reset! !url-cache {}))
