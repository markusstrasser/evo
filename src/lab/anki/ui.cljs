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
                       :dir-handle nil
                       :show-answer? false}))

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
                            :class-name "image-occlusion-card"})]

    [:div.review-card {:class class-name}
     front
     (when show-answer? back)
     (if show-answer?
       (rating-buttons)
       [:button {:on {:click [::show-answer]}} "Show Answer"])]))

(defn review-screen [{:keys [state show-answer?]}]
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
       [:p "Come back later for more reviews."]])))

(defn main-app [{:keys [screen state show-answer?]}]
  [:div.anki-app
   [:nav
    [:h1 "Local-First Anki"]
    [:p "Edit cards.md in your folder to add/modify cards"]]
   [:main
    (case screen
      :setup (setup-screen)
      :review (review-screen {:state state :show-answer? show-answer?})
      [:div "Unknown screen"])]])

;; Forward declarations
(declare render!)

;; Event Handlers

(defn load-and-sync-cards!
  "Load cards.md, parse it, and create events for any new cards"
  [dir-handle]
  (p/let [markdown (fs/load-cards dir-handle)
          lines (str/split-lines markdown)
          parsed-cards (keep core/parse-card lines)
          _ (js/console.log "Parsed" (count parsed-cards) "cards from markdown")

          events (fs/load-log dir-handle)
          current-state (core/reduce-events events)
          existing-hashes (set (keys (:cards current-state)))

          new-cards (remove #(contains? existing-hashes (core/card-hash %)) parsed-cards)
          _ (js/console.log "Found" (count new-cards) "new cards")

          new-events (mapv #(core/card-created-event (core/card-hash %) %) new-cards)]

    (when (seq new-events)
      (js/console.log "Saving" (count new-events) "new card events")
      (p/do! (fs/append-to-log dir-handle new-events)))

    (core/reduce-events (concat events new-events))))

(defn handle-event [_replicant-data [action & args]]
  (case action
    ::select-folder
    (p/let [handle (fs/pick-directory)]
      (js/console.log "Folder selected:" handle)
      (fs/save-dir-handle handle)
      (p/let [state (load-and-sync-cards! handle)]
        (swap! !state assoc
               :dir-handle handle
               :state state
               :screen :review)
        (js/console.log "Loaded state with" (count (:cards state)) "total cards")
        (render!)))

    ::show-answer
    (do
      (swap! !state assoc :show-answer? true)
      (render!))

    ::rate-card
    (let [rating (first args)
          state (:state @!state)
          dir-handle (:dir-handle @!state)
          due (core/due-cards state)
          current-hash (first due)]
      (when (and current-hash dir-handle)
        (js/console.log "Rating card" rating)
        (p/let [event (core/review-event current-hash rating)
                _ (fs/append-to-log dir-handle [event])
                new-state (core/apply-event state event)]
          (swap! !state assoc
                 :state new-state
                 :show-answer? false)
          (js/console.log "Review complete, remaining:" (count (core/due-cards new-state)))
          (render!))))

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
            (p/let [state (load-and-sync-cards! saved-handle)]
              (swap! !state assoc
                     :dir-handle saved-handle
                     :state state
                     :screen :review)
              (js/console.log "Restored session with" (count (:cards state)) "cards"))))
        (fn [e]
          (js/console.warn "Could not restore saved directory:" (.-message e)))))))
