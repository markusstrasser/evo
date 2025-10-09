(ns lab.anki.ui
  "Anki clone UI - vanilla DOM rendering"
  (:require [clojure.string :as str]
            [lab.anki.core :as core]
            [lab.anki.fs :as fs]
            [promesa.core :as p]))

;; NOTE: All requires are used in the DOM rendering/event handling functions below

;; State management

(defonce app-state (atom {:state {:cards {} :meta {}}
                          :dir-handle nil
                          :screen :setup
                          :undo-stack []}))

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
             :screen :review
             :undo-stack [])
      (js/console.log "Loaded state with" (count (:cards state)) "total cards"))))

(defn rating-buttons-html []
  (str "<div class='rating-buttons'>"
       "<button id='btn-forgot'>Forgot</button>"
       "<button id='btn-hard'>Hard</button>"
       "<button id='btn-good'>Good</button>"
       "<button id='btn-easy'>Easy</button>"
       "</div>"))

(defn card-content-html [card show-answer?]
  (case (:type card)
    :qa (str "<div class='question'><h2>Question</h2><p>" (:question card) "</p></div>"
             (when show-answer?
               (str "<div class='answer'><h2>Answer</h2><p>" (:answer card) "</p></div>")))
    :cloze (str "<div class='cloze-text'><p>" (:template card) "</p></div>")
    :image-occlusion (str "<div class='image-occlusion'>"
                          "<img src='" (:image-url card) "' alt='" (:alt-text card) "' />"
                          (when show-answer?
                            (str "<div class='regions'><p>Regions: " (str/join ", " (:regions card)) "</p></div>"))
                          "</div>")
    ""))

(defn handle-undo! []
  (when-let [last-review (peek (:undo-stack @app-state))]
    (js/console.log "Undoing last review:" last-review)
    (let [dir-handle (:dir-handle @app-state)]
      (p/let [;; Remove last event from log and reload state without it
              events (fs/load-log dir-handle)
              ;; Remove the last review event (the one we just added)
              events-without-last (vec (butlast events))
              new-state (core/reduce-events events-without-last)
              ;; Rewrite the log without the last event
              _ (fs/write-edn-file dir-handle "log.edn" events-without-last)]
        (swap! app-state assoc
               :state new-state
               :show-answer? false)
        (swap! app-state update :undo-stack pop)))))

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
                       show-answer? (get @app-state :show-answer? false)
                       can-undo? (seq (:undo-stack @app-state))]
                   (str "<div class='review-screen'>"
                        "<div class='review-header'>"
                        "<p>Total cards: " card-count " | Due: " (count due) "</p>"
                        "<div class='undo-redo-buttons'>"
                        "<button id='btn-undo' " (when-not can-undo? "disabled") ">Undo</button>"
                        "</div>"
                        "</div>"
                        (if (empty? due)
                          "<h2>No cards due!</h2><p>Create cards.md in your selected folder to add cards.</p>"
                          (let [hash (first due)
                                card (core/card-with-meta (:state @app-state) hash)]
                            (str "<div class='review-card'>"
                                 (card-content-html card show-answer?)
                                 (if show-answer?
                                   (rating-buttons-html)
                                   "<button id='btn-show-answer'>Show Answer</button>")
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

       ;; Undo button
       (when-let [btn (.getElementById js/document "btn-undo")]
         (js/console.log "Attaching undo handler")
         (.addEventListener btn "click"
                           (fn [_e]
                             (handle-undo!))))

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
                                   ;; Save to undo stack before applying rating
                                   (swap! app-state update :undo-stack conj {:hash current-hash :rating rating})
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
