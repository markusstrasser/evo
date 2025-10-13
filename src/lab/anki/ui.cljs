(ns lab.anki.ui
  "Anki clone UI using Replicant"
  (:require [clojure.string :as str]
            [lab.anki.core :as core]
            [lab.anki.fs :as fs]
            [promesa.core :as p]
            [replicant.dom :as r]))

;; State
(defonce !state (atom {:screen :setup
                       :state {:cards {} :meta {}}
                       :events []
                       :dir-handle nil
                       :show-answer? false}))

;; Helpers
(defn time-ago
  "Convert a date to a human-readable 'X ago' string"
  [date]
  (let [now (js/Date.)
        diff-ms (- (.getTime now) (.getTime date))
        diff-sec (/ diff-ms 1000)
        diff-min (/ diff-sec 60)
        diff-hour (/ diff-min 60)
        diff-day (/ diff-hour 24)]
    (cond
      (< diff-min 1) "just now"
      (< diff-min 2) "1 min ago"
      (< diff-hour 1) (str (js/Math.floor diff-min) " mins ago")
      (< diff-hour 2) "1 hour ago"
      (< diff-day 1) (str (js/Math.floor diff-hour) " hours ago")
      (< diff-day 2) "1 day ago"
      :else (str (js/Math.floor diff-day) " days ago"))))

(defn truncate-text
  "Truncate text to max-len with ellipsis"
  [text max-len]
  (if (> (count text) max-len)
    (str (subs text 0 max-len) "...")
    text))

(defn get-card-preview
  "Get preview text for a card"
  [card]
  (case (:type card)
    :qa (truncate-text (:question card) 50)
    :cloze (truncate-text (:template card) 50)
    :image-occlusion (str "Image: " (:alt-text card))
    :image-occlusion/item (str "Occlusion: " (truncate-text (:answer card) 40))
    "Unknown card type"))

;; Components
(defn setup-screen [{:keys [saved-handle]}]
  [:div.setup-screen
   [:h1 "Welcome to Local-First Anki"]
   [:p "To get started, select a folder to store your cards and review data."]
   (if saved-handle
     [:div
      [:button
       {:on {:click [::resume-saved]}}
       "Resume Last Session"]
      [:button
       {:on {:click [::select-folder]}}
       "Select Different Folder"]]
     [:button
      {:on {:click [::select-folder]}}
      "Select Folder"])])

(defn rating-buttons []
  [:div.rating-buttons
   (for [rating [:forgot :hard :good :easy]]
     ^{:key rating}
     [:button
      {:on {:click [::rate-card rating]}}
      (str/capitalize (name rating))])])

(defn draw-occlusion-mask!
  "Draw occlusion mask on canvas with noise background"
  [canvas card show-answer?]
  (js/console.log "draw-occlusion-mask! called with canvas:" canvas "show-answer?" show-answer?)
  (when canvas
    (let [ctx (.getContext canvas "2d")
          asset (:asset card)
          shape (:shape card)
          w (or (:width asset) 400)
          h (or (:height asset) 300)]

      (js/console.log "Drawing canvas:" w "x" h)

      ;; Set canvas size
      (set! (.-width canvas) w)
      (set! (.-height canvas) h)

      ;; Draw noise background
      (let [image-data (.createImageData ctx w h)
            data (.-data image-data)]
        (dotimes [i (/ (.-length data) 4)]
          (let [val (+ 100 (rand-int 100))
                idx (* i 4)]
            (aset data idx val) ; R
            (aset data (+ idx 1) val) ; G
            (aset data (+ idx 2) val) ; B
            (aset data (+ idx 3) 255))) ; A
        (.putImageData ctx image-data 0 0))

      (js/console.log "Noise background drawn")

      ;; Draw mask if not revealed
      (when-not show-answer?
        (let [x (* (:x shape) w)
              y (* (:y shape) h)
              rect-w (* (:w shape) w)
              rect-h (* (:h shape) h)]
          (js/console.log "Drawing green mask at" x y rect-w rect-h)
          (set! (.-fillStyle ctx) "rgba(0, 255, 0, 0.45)")
          (.fillRect ctx x y rect-w rect-h))))))

(defn review-card [{:keys [card show-answer?]}]
  (let [{:keys [front back class-name]}
        (case (:type card)
          :qa {:front [:div.question
                       [:h2 "Question"]
                       [:p (:question card)]]
               :back [:div.answer
                      [:h2 "Answer"]
                      [:p (:answer card)]]
               :class-name "qa-card"}

          :cloze (let [template (:template card)
                       deletions (:deletions card)
                       deletion (first deletions)
                       display-text (if show-answer?
                                      template
                                      (str/replace template
                                                   (re-pattern (str "\\[" deletion "\\]"))
                                                   "[...]"))]
                   {:front [:div.cloze-text [:p display-text]]
                    :back nil
                    :class-name "cloze-card"})

          :image-occlusion {:front [:div.image-occlusion
                                    [:img {:src (:image-url card)
                                           :alt (:alt-text card)}]
                                    [:p "Regions: " (str/join ", " (:regions card))]]
                            :back [:div.answer [:p "Check the image!"]]
                            :class-name "image-occlusion-card"}

          :image-occlusion/item
          (let [canvas-id (str "canvas-" (:hash card) "-" (if show-answer? "revealed" "masked"))]
            ;; Schedule canvas drawing after DOM update
            (js/setTimeout
             (fn []
               (when-let [canvas (js/document.getElementById canvas-id)]
                 (draw-occlusion-mask! canvas card show-answer?)))
             0)
            {:front [:div.image-occlusion-item
                     [:h2 (:prompt card)]
                     [:canvas {:id canvas-id}]]
             :back [:div.answer [:p (:answer card)]]
             :class-name "image-occlusion-item-card"})

          ;; Default case for unknown card types
          {:front [:div.unknown-card
                   [:h2 "Unknown Card Type"]
                   [:p (str "Type: " (:type card))]
                   [:pre (pr-str card)]]
           :back nil
           :class-name "unknown-card"})]

    [:div.review-card {:class class-name}
     front
     (when show-answer? back)
     (if show-answer?
       (rating-buttons)
       [:button {:on {:click [::show-answer]}} "Show Answer"])]))

(defn review-history
  "Display recent review events"
  [{:keys [events state]}]
  (let [review-events (->> events
                           (filter #(= :review (:event/type %)))
                           (take-last 10)
                           reverse)]
    (when (seq review-events)
      [:div.review-history
       [:h3 "Recent Reviews"]
       [:div.history-list
        (for [event review-events]
          (let [card-hash (get-in event [:event/data :card-hash])
                rating (get-in event [:event/data :rating])
                timestamp (:event/timestamp event)
                card (get-in state [:cards card-hash])]
            ^{:key (str card-hash "-" (.getTime timestamp))}
            [:div.history-item
             [:div.history-card
              [:span.card-preview (if card (get-card-preview card) "Unknown card")]
              [:span.rating {:class (str "rating-" (name rating))}
               (str/capitalize (name rating))]]
             [:span.history-time (time-ago timestamp)]]))]])))

(defn review-screen [{:keys [state events show-answer?]}]
  (let [due-hashes (core/due-cards state)
        current-hash (first due-hashes)
        remaining (count due-hashes)]
    (if current-hash
      (let [card (core/card-with-meta state current-hash)]
        [:div.review-screen
         [:div.review-header
          [:p (str "Cards remaining: " remaining)]]
         [:div.review-content
          (review-card {:card card :show-answer? show-answer?})]])
      [:div.review-screen
       [:h2 "No cards due!"]
       [:p "Come back later for more reviews."]

       (review-history {:events events :state state})])))

(defn main-app [{:keys [screen state events show-answer? saved-handle]}]
  (let [undo-stack (:undo-stack state)
        redo-stack (:redo-stack state)
        can-undo? (seq undo-stack)
        can-redo? (seq redo-stack)]
    [:div.anki-app
     [:nav
      [:h1 "Local-First Anki"]
      [:p "Edit cards.md in your folder to add/modify cards"]
      [:div.undo-redo-buttons
       [:button {:disabled (not can-undo?)
                 :on {:click [::undo]}}
        "Undo"]
       [:button {:disabled (not can-redo?)
                 :on {:click [::redo]}}
        "Redo"]]]
     [:main
      (case screen
        :setup (setup-screen {:saved-handle saved-handle})
        :review (review-screen {:state state :events events :show-answer? show-answer?})
        [:div "Unknown screen"])]]))

;; Forward declarations
(declare render!)

;; Event Handlers

(defn load-and-sync-cards!
  "Load cards.md, parse it, and create events for any new cards"
  [dir-handle]
  (p/let [markdown (fs/load-cards dir-handle)
          events (fs/load-log dir-handle)]
    (let [parsed-cards (keep core/parse-card (str/split markdown #"\n\n+"))
          _ (js/console.log "Parsed" (count parsed-cards) "cards from markdown")
          current-state (core/reduce-events events)
          existing-hashes (set (keys (:cards current-state)))
          new-cards (remove #(contains? existing-hashes (core/card-hash %)) parsed-cards)
          _ (js/console.log "Found" (count new-cards) "new cards")
          new-events (mapv #(core/card-created-event (core/card-hash %) %) new-cards)]

      (when (seq new-events)
        (js/console.log "Saving" (count new-events) "new card events")
        (p/do! (fs/append-to-log dir-handle new-events)))

      (core/reduce-events (concat events new-events)))))

;; Removed: create-test-occlusion-card - test code no longer needed

(defn handle-event [_replicant-data [action & args]]
  (case action
    ::select-folder
    (p/let [handle (fs/pick-directory)]
      (js/console.log "Folder selected:" handle)
      (fs/save-dir-handle handle)
      (p/let [state (load-and-sync-cards! handle)
              events (fs/load-log handle)]
        (swap! !state assoc
               :dir-handle handle
               :state state
               :events events
               :saved-handle handle
               :screen :review)
        (js/console.log "Loaded state with" (count (:cards state)) "total cards")))

    ::resume-saved
    (let [{:keys [saved-handle]} @!state]
      (when saved-handle
        (js/console.log "Resuming saved session...")
        (p/catch
         (p/let [permission (.requestPermission saved-handle #js {:mode "readwrite"})]
           (if (= permission "granted")
             (p/let [state (load-and-sync-cards! saved-handle)
                     events (fs/load-log saved-handle)]
               (swap! !state assoc
                      :dir-handle saved-handle
                      :state state
                      :events events
                      :screen :review)
               (js/console.log "Restored session with" (count (:cards state)) "cards"))
             (js/console.warn "Permission denied, please select folder again")))
         (fn [e]
           (js/console.error "Failed to resume session:" (.-message e))))))

    ::show-answer
    (swap! !state assoc :show-answer? true)

    ::rate-card
    (let [{:keys [state events dir-handle]} @!state
          rating (first args)
          due (core/due-cards state)
          current-hash (first due)]
      (when (and current-hash dir-handle)
        (js/console.log "Rating card" rating)
        (p/let [event (core/review-event current-hash rating)
                _ (fs/append-to-log dir-handle [event])
                new-state (core/apply-event state event)
                new-events (conj events event)]
          (swap! !state assoc
                 :state new-state
                 :events new-events
                 :show-answer? false)
          (js/console.log "Review complete, remaining:" (count (core/due-cards new-state))))))

    ::undo
    (let [{:keys [state dir-handle events]} @!state
          undo-stack (:undo-stack state)]
      (when (and (seq undo-stack) dir-handle)
        (let [target-event-id (last undo-stack)]
          (js/console.log "Undoing event" target-event-id)
          (p/let [event (core/undo-event target-event-id)
                  _ (fs/append-to-log dir-handle [event])
                  new-events (conj events event)
                  new-state (core/reduce-events new-events)]
            (swap! !state assoc
                   :state new-state
                   :events new-events
                   :show-answer? false)
            (js/console.log "Undo complete, undo stack:" (count (:undo-stack new-state)))))))

    ::redo
    (let [{:keys [state dir-handle events]} @!state
          redo-stack (:redo-stack state)]
      (when (and (seq redo-stack) dir-handle)
        (let [target-event-id (last redo-stack)]
          (js/console.log "Redoing event" target-event-id)
          (p/let [event (core/redo-event target-event-id)
                  _ (fs/append-to-log dir-handle [event])
                  new-events (conj events event)
                  new-state (core/reduce-events new-events)]
            (swap! !state assoc
                   :state new-state
                   :events new-events
                   :show-answer? false)
            (js/console.log "Redo complete, redo stack:" (count (:redo-stack new-state)))))))

    (js/console.warn "Unknown action:" action)))

;; Rendering

(defn render! []
  (r/render (js/document.getElementById "root")
            (main-app @!state)))

;; Initialization

(defn ^:export main []
  (js/console.log "Anki app starting with Replicant...")
  (r/set-dispatch! handle-event)
  (render!)
  (add-watch !state :render (fn [_ _ _ _] (render!)))

  ;; Try to restore saved directory handle (without requesting permission)
  (p/let [saved-handle (fs/load-dir-handle)]
    (when saved-handle
      (js/console.log "Found saved directory handle, will restore on user interaction")
      (swap! !state assoc :saved-handle saved-handle))))
