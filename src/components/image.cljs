(ns components.image
  "Image component for rendering markdown images.
   
   Handles:
   - Local asset images (../assets/...) via blob URLs
   - External URLs (https://...) directly
   - Loading states and error fallbacks"
  (:require [parser.images :as images]
            [shell.storage :as storage]))

;; Cache of path -> blob URL for local assets.
;; Avoids re-fetching the same asset multiple times.
(defonce !url-cache (atom {}))

(defn- resolve-asset-url
  "Resolve an asset path to a displayable URL.
   
   - External URLs (http/https): return as-is
   - Local assets: fetch via storage and return blob URL"
  [path on-resolved]
  (cond
    ;; External URL - use directly
    (or (clojure.string/starts-with? path "http://")
        (clojure.string/starts-with? path "https://"))
    (on-resolved path)

    ;; Check cache first
    (get @!url-cache path)
    (on-resolved (get @!url-cache path))

    ;; Local asset - fetch via storage
    (images/asset-path? path)
    (let [filename (images/path->filename path)]
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

(defn Image
  "Render an inline image.
   
   Props:
   - path: Image path (relative or URL)
   - alt: Alt text for accessibility
   - on-intent: Intent handler for click events"
  [{:keys [path alt _on-intent]}]
  (let [;; Check if external URL
        external? (or (clojure.string/starts-with? (or path "") "http://")
                      (clojure.string/starts-with? (or path "") "https://"))
        ;; Check cache synchronously for local assets
        cached-url (when-not external? (get @!url-cache path))
        ;; Use cached URL if available
        src (cond
              external? path
              cached-url cached-url
              :else nil)]

    (if (or external? cached-url)
      ;; External or cached - render with src immediately
      [:img.inline-image
       {:replicant/key (str "img-" (hash path))
        :src src
        :alt (if (clojure.string/blank? alt) "Image" alt)
        :loading "lazy"
        :on {:load (fn [e]
                     (set! (.. e -target -style -display) ""))
             :error (fn [e]
                      (set! (.. e -target -style -display) "none"))}}]

      ;; Local asset not cached - fetch and find element by data attribute
      ;; on-render's el reference becomes stale after async, so we query DOM
      [:img.inline-image
       {:replicant/key (str "img-" (hash path))
        :alt (if (clojure.string/blank? alt) "Image" alt)
        :loading "lazy"
        :data-asset-path path
        :replicant/on-render
        (fn [_el _]
          ;; Start fetch - we'll find element by data attribute in callback
          (resolve-asset-url
           path
           (fn [url]
             (when url
               ;; Find element by data attribute (el reference is stale)
               (when-let [el (js/document.querySelector
                              (str "img[data-asset-path=\"" path "\"]"))]
                 (when (clojure.string/blank? (.-src el))
                   (set! (.-src el) url)))))))
        :on {:load (fn [e]
                     (set! (.. e -target -style -display) ""))
             :error (fn [e]
                      (set! (.. e -target -style -display) "none"))}}])))

(defn clear-url-cache!
  "Clear the blob URL cache. Call when folder changes."
  []
  ;; Revoke existing blob URLs to free memory
  (doseq [[_ url] @!url-cache]
    (when (clojure.string/starts-with? url "blob:")
      (js/URL.revokeObjectURL url)))
  (reset! !url-cache {}))
