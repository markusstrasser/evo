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
(defn setup-screen []
  [:div.setup-screen
   [:h1 "Welcome to Local-First Anki"]
   [:p "To get started, select a folder to store your cards and review data."]
   [:button
    {:on {:click [::select-folder]}}
    "Select Folder"]])

(defn rating-buttons []
  [:div.rating-buttons
   (for [rating [:forgot :hard :good :easy]]
     ^{:key rating}
     [:button
      {:on {:click [::rate-card rating]}}
      (str/capitalize (name rating))])])

(defn draw-occlusion-mask!
  "Draw occlusion mask on canvas"
  [canvas card show-answer?]
  (when canvas
    (let [ctx (.getContext canvas "2d")
          img (js/Image.)
          asset (:asset card)
          shape (:shape card)]
      (set! (.-onerror img)
            (fn [e]
              (js/console.error "Failed to load image:" (:url asset) e)))
      (set! (.-onload img)
            (fn []
              (let [w (.-width img)
                    h (.-height img)]
                ;; Set canvas size to match image
                (set! (.-width canvas) w)
                (set! (.-height canvas) h)

                ;; Draw image
                (.drawImage ctx img 0 0)

                ;; Draw mask if not revealed
                (when-not show-answer?
                  (let [x (* (:x shape) w)
                        y (* (:y shape) h)
                        rect-w (* (:w shape) w)
                        rect-h (* (:h shape) h)]
                    (set! (.-fillStyle ctx) "rgba(0, 255, 0, 0.45)")
                    (.fillRect ctx x y rect-w rect-h))))))
      (set! (.-src img) (:url asset)))))

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
          {:front [:div.image-occlusion-item
                   [:h2 (:prompt card)]
                   [:canvas {:ref (fn [el] (draw-occlusion-mask! el card show-answer?))}]]
           :back [:div.answer [:p (:answer card)]]
           :class-name "image-occlusion-item-card"})]

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
       [:button {:on {:click [::create-test-occlusion]}} "Create Test Image Occlusion"]
       (review-history {:events events :state state})])))

(defn main-app [{:keys [screen state events show-answer?]}]
  [:div.anki-app
   [:nav
    [:h1 "Local-First Anki"]
    [:p "Edit cards.md in your folder to add/modify cards"]]
   [:main
    (case screen
      :setup (setup-screen)
      :review (review-screen {:state state :events events :show-answer? show-answer?})
      [:div "Unknown screen"])]])

;; Forward declarations
(declare render!)

;; Event Handlers

(defn load-and-sync-cards!
  "Load cards.md, parse it, and create events for any new cards"
  [dir-handle]
  (p/let [markdown (fs/load-cards dir-handle)
          events (fs/load-log dir-handle)]
    (let [lines (str/split-lines markdown)
          parsed-cards (keep core/parse-card lines)
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

(defn create-test-occlusion-card
  "Create a test image occlusion card"
  []
  (let [card {:type :image-occlusion
              :asset {:url "/test-images/test-regions.png"
                      :width 400
                      :height 300}
              :prompt "What is this region?"
              :occlusions [{:oid (random-uuid)
                            :shape {:kind :rect
                                    :normalized? true
                                    :x 0.125
                                    :y 0.167
                                    :w 0.25
                                    :h 0.267}
                            :answer "Region A"}
                           {:oid (random-uuid)
                            :shape {:kind :rect
                                    :normalized? true
                                    :x 0.5
                                    :y 0.167
                                    :w 0.25
                                    :h 0.267}
                            :answer "Region B"}
                           {:oid (random-uuid)
                            :shape {:kind :rect
                                    :normalized? true
                                    :x 0.125
                                    :y 0.6
                                    :w 0.25
                                    :h 0.267}
                            :answer "Region C"}
                           {:oid (random-uuid)
                            :shape {:kind :rect
                                    :normalized? true
                                    :x 0.5
                                    :y 0.6
                                    :w 0.25
                                    :h 0.267}
                            :answer "Region D"}]}
        h (core/card-hash card)]
    (core/card-created-event h card)))

(defn handle-event [_replicant-data [action & args]]
  (case action
    ::select-folder
    (p/let [handle (fs/pick-directory)]
      (js/console.log "Folder selected:" handle)
      (fs/save-dir-handle handle)
      (p/let [events (fs/load-log handle)
              state (core/reduce-events events)]
        (swap! !state assoc
               :dir-handle handle
               :state state
               :events events
               :screen :review)
        (js/console.log "Loaded state with" (count (:cards state)) "total cards")))

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

    ::create-test-occlusion
    (let [{:keys [state events dir-handle]} @!state
          event (create-test-occlusion-card)]
      (js/console.log "Creating test occlusion card")
      (if dir-handle
        (p/let [_ (fs/append-to-log dir-handle [event])
                new-state (core/apply-event state event)
                new-events (conj events event)]
          (swap! !state assoc
                 :state new-state
                 :events new-events)
          (js/console.log "Test card created, total cards:" (count (:cards new-state))))
        ;; No dir handle, just add to memory
        (let [new-state (core/apply-event state event)
              new-events (conj events event)]
          (swap! !state assoc
                 :state new-state
                 :events new-events)
          (js/console.log "Test card created (memory only), total cards:" (count (:cards new-state))))))

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

  ;; Try to restore saved directory handle
  (p/let [saved-handle (fs/load-dir-handle)]
    (when saved-handle
      (js/console.log "Found saved directory handle, requesting permission...")
      (p/catch
        (p/let [permission (.requestPermission saved-handle #js {:mode "readwrite"})]
          (when (= permission "granted")
            (js/console.log "Permission granted, loading cards...")
            (p/let [events (fs/load-log saved-handle)
                    state (core/reduce-events events)]
              (swap! !state assoc
                     :dir-handle saved-handle
                     :state state
                     :events events
                     :screen :review)
              (js/console.log "Restored session with" (count (:cards state)) "cards"))))
        (fn [e]
          (js/console.warn "Could not restore saved directory:" (.-message e)))))))
