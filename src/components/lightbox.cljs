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
  (vs/swap-view-state! assoc :lightbox {:src src :alt alt})
  ;; Add body class to prevent scrolling
  (.. js/document -body -classList (add "lightbox-open")))

(defn hide!
  "Close lightbox."
  []
  (vs/swap-view-state! dissoc :lightbox)
  (.. js/document -body -classList (remove "lightbox-open")))

(defn visible?
  "Check if lightbox is currently open."
  []
  (some? (:lightbox (vs/get-view-state))))

(defn current-image
  "Get current lightbox image data."
  []
  (:lightbox (vs/get-view-state)))

;; ── Key Handler ──────────────────────────────────────────────────────────────

(defn handle-keydown
  "Handle keydown for lightbox. Returns true if handled."
  [e]
  (when (visible?)
    (case (.-key e)
      "Escape" (do (hide!) true)
      false)))

;; ── Component ────────────────────────────────────────────────────────────────

(defn Lightbox
  "Render lightbox overlay if active.
   Should be rendered at root level of app."
  []
  (when-let [{:keys [src alt]} (current-image)]
    [:div.lightbox-overlay
     {:on {:click (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (hide!)))}}
     [:div.lightbox-content
      [:img.lightbox-image
       {:src src
        :alt (or alt "Image")}]
      [:button.lightbox-close
       {:on {:click (fn [_] (hide!))}}
       "×"]]]))
