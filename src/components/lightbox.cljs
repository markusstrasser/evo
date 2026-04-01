(ns components.lightbox
  "Lightbox component for fullscreen image viewing.

   Simple modal overlay that shows image at full resolution.
   Closes on click outside, Escape key, or close button."
  (:require [shell.view-state :as vs]))

;; ── Lightbox State ───────────────────────────────────────────────────────────
;; Stored in view-state for global access

(defn show!
  "Open lightbox with given image."
  [{:keys [src alt]}]
  (vs/show-lightbox! {:src src :alt alt}))

(defn hide!
  "Close lightbox."
  []
  (vs/hide-lightbox!))

(defn visible?
  "Check if lightbox is currently open."
  []
  (vs/lightbox-visible?))

(defn current-image
  "Get current lightbox image data."
  []
  (vs/lightbox))

;; ── Key Handler ──────────────────────────────────────────────────────────────

(defn handle-keydown
  "Handle keydown for lightbox. Returns true if handled."
  [e]
  (when (visible?)
    (if (= "Escape" (.-key e))
      (do (hide!) true)
      true)))

;; ── Component ────────────────────────────────────────────────────────────────

(defn Lightbox
  "Render lightbox overlay if active.
   Should be rendered at root level of app."
  []
  (when-let [{:keys [src alt]} (current-image)]
    [:div.lightbox-overlay
     {:replicant/key "lightbox-overlay"
      :replicant/on-mount (fn [_]
                            (.. js/document -body -classList (add "lightbox-open")))
      :replicant/on-unmount (fn [_]
                              (.. js/document -body -classList (remove "lightbox-open")))
      :on {:click (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (hide!)))}}
     [:div.lightbox-content
      [:img.lightbox-image
       {:src src
        :alt (or alt "Image")}]
      [:button.lightbox-close
       {:on {:click (fn [_] (hide!))}}
       "×"]]]))
