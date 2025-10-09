(ns lab.anki.ui
  "Anki clone UI - vanilla DOM rendering"
  (:require [clojure.string :as str]
            [lab.anki.core :as core]
            [lab.anki.fs :as fs]
            [promesa.core :as p]))

;; NOTE: All requires are used in the DOM rendering/event handling functions below

;; State management

;; Main entry point

(defonce app-state (atom {:state {:cards {} :meta {}}
                          :dir-handle nil
                          :screen :setup}))

(defn load-and-sync-cards!
  "Load cards.md, parse it, and create events for any new cards"
  [dir-handle]
  (p/let [markdown (fs/load-cards dir-handle)
          lines (str/split-lines markdown)
          parsed-cards (keep core/parse-card lines)
          _ (js/console.log "Parsed" (count parsed-cards) "cards from markdown")

          ;; Load existing events and get current state
          events (fs/load-log dir-handle)
          current-state (core/reduce-events events)
          existing-hashes (set (keys (:cards current-state)))

          ;; Find new cards
          new-cards (remove (fn [card]
                              (contains? existing-hashes (core/card-hash card)))
                            parsed-cards)
          _ (js/console.log "Found" (count new-cards) "new cards")

          ;; Create events for new cards
          new-events (mapv (fn [card]
                             (let [hash (core/card-hash card)]
                               (core/card-created-event hash card)))
                           new-cards)]

    (if (seq new-events)
      (p/do!
        (js/console.log "Saving" (count new-events) "new card events")
        (fs/append-to-log dir-handle new-events))
      (js/console.log "No new cards to save"))

    ;; Return updated state
    (core/reduce-events (concat events new-events))))

(defn handle-select-folder! []
  (js/console.log "Selecting folder...")
  (p/let [handle (fs/pick-directory)]
    (js/console.log "Folder selected:" handle)
    (fs/save-dir-handle handle)
    (p/let [state (load-and-sync-cards! handle)]
      (swap! app-state assoc
             :dir-handle handle
             :state state
             :screen :review)
      (js/console.log "Loaded state with" (count (:cards state)) "total cards"))))

(defn render! []
  (let [root (.getElementById js/document "root")]
    (set! (.-innerHTML root)
          (str "<div class='anki-app'>"
               "<nav>"
               "<h1>Local-First Anki</h1>"
               "<p>Edit cards.md in your folder to add/modify cards</p>"
               "</nav>"
               "<main>"
               (if (= :setup (:screen @app-state))
                 (str "<div class='setup-screen'>"
                      "<h1>Welcome to Local-First Anki</h1>"
                      "<p>To get started, select a folder to store your cards and review data.</p>"
                      "<button id='select-folder-btn'>Select Folder</button>"
                      "</div>")
                 (let [due (core/due-cards (:state @app-state))
                       card-count (count (:cards (:state @app-state)))
                       show-answer? (get @app-state :show-answer? false)]
                   (str "<div class='review-screen'>"
                        "<div class='review-header'>"
                        "<p>Total cards: " card-count " | Due: " (count due) "</p>"
                        "</div>"
                        (if (empty? due)
                          "<h2>No cards due!</h2><p>Create cards.md in your selected folder to add cards.</p>"
                          (let [hash (first due)
                                card (core/card-with-meta (:state @app-state) hash)]
                            (str "<div class='review-card'>"
                                 (if (= :qa (:type card))
                                   (str "<div class='question'><h2>Question</h2><p>" (:question card) "</p></div>"
                                        (if show-answer?
                                          (str "<div class='answer'><h2>Answer</h2><p>" (:answer card) "</p></div>"
                                               "<div class='rating-buttons'>"
                                               "<button id='btn-forgot'>Forgot</button>"
                                               "<button id='btn-hard'>Hard</button>"
                                               "<button id='btn-good'>Good</button>"
                                               "<button id='btn-easy'>Easy</button>"
                                               "</div>")
                                          "<button id='btn-show-answer'>Show Answer</button>"))
                                   (str "<div class='cloze-text'><p>" (:template card) "</p></div>"
                                        (if show-answer?
                                          (str "<div class='rating-buttons'>"
                                               "<button id='btn-forgot'>Forgot</button>"
                                               "<button id='btn-hard'>Hard</button>"
                                               "<button id='btn-good'>Good</button>"
                                               "<button id='btn-easy'>Easy</button>"
                                               "</div>")
                                          "<button id='btn-show-answer'>Show Answer</button>")))
                                 "</div>")))
                        "</div>")))
               "</main>"
               "</div>"))
    ;; Wire up event handlers after rendering (using setTimeout to ensure DOM is ready)
    (js/setTimeout
     (fn []
       ;; Setup screen button
       (when-let [btn (.getElementById js/document "select-folder-btn")]
         (js/console.log "Attaching select-folder handler")
         (.addEventListener btn "click" handle-select-folder!))

       ;; Show answer button
       (when-let [btn (.getElementById js/document "btn-show-answer")]
         (js/console.log "Attaching show-answer handler")
         (.addEventListener btn "click"
                           (fn [_e]
                             (swap! app-state assoc :show-answer? true))))

       ;; Rating buttons
       (doseq [[id rating] [["btn-forgot" :forgot]
                            ["btn-hard" :hard]
                            ["btn-good" :good]
                            ["btn-easy" :easy]]]
         (when-let [btn (.getElementById js/document id)]
           (js/console.log "Attaching rating handler:" rating)
           (.addEventListener btn "click"
                             (fn [_e]
                               (let [due (core/due-cards (:state @app-state))
                                     current-hash (first due)
                                     dir-handle (:dir-handle @app-state)]
                                 (when (and current-hash dir-handle)
                                   (js/console.log "Rating card" rating)
                                   (p/let [event (core/review-event current-hash rating)
                                           _ (fs/append-to-log dir-handle [event])
                                           events (fs/load-log dir-handle)
                                           new-state (core/reduce-events events)]
                                     (swap! app-state assoc
                                            :state new-state
                                            :show-answer? false)
                                     (js/console.log "Review complete, remaining:" (count (core/due-cards new-state)))))))))))
     10)))

(defn ^:export main []
  (js/console.log "Anki app starting...")
  (render!)
  ;; Re-render on state changes
  (add-watch app-state :render (fn [_ _ _ _] (render!))))
