(ns lab.anki.ui
  "Replicant UI components for Anki clone"
  (:require [clojure.string :as str]
            [lab.anki.core :as core]
            [lab.anki.fs :as fs]
            [promesa.core :as p]))

;; UI Components

(defn setup-screen
  "Initial setup screen - pick directory"
  [{:keys [on-directory-selected]}]
  [:div {:class "setup-screen"}
   [:h1 "Welcome to Local-First Anki"]
   [:p "To get started, select a folder to store your cards and review data."]
   [:button
    {:on {:click (fn [_e]
                   (p/let [dir-handle (fs/pick-directory)]
                     (fs/save-dir-handle dir-handle)
                     (on-directory-selected dir-handle)))}}
    "Select Folder"]])

(defn review-card-qa
  "Review UI for a QA card"
  [{:keys [card show-answer? on-show-answer on-rating]}]
  [:div {:class "review-card qa-card"}
   [:div {:class "question"}
    [:h2 "Question"]
    [:p (:question card)]]
   (if show-answer?
     [:div
      [:div {:class "answer"}
       [:h2 "Answer"]
       [:p (:answer card)]]
      [:div {:class "rating-buttons"}
       [:button {:on {:click #(on-rating :forgot)}} "Forgot"]
       [:button {:on {:click #(on-rating :hard)}} "Hard"]
       [:button {:on {:click #(on-rating :good)}} "Good"]
       [:button {:on {:click #(on-rating :easy)}} "Easy"]]]
     [:button {:on {:click #(on-show-answer)}} "Show Answer"])])

(defn review-card-cloze
  "Review UI for a cloze deletion card"
  [{:keys [card show-answer? on-show-answer on-rating current-deletion-idx]}]
  (let [template (:template card)
        deletions (:deletions card)
        deletion (nth deletions current-deletion-idx)
        ;; Replace [deletion] with either blank or answer
        display-text (if show-answer?
                       template
                       (str/replace template
                                    (re-pattern (str "\\[" deletion "\\]"))
                                    "[...]"))]
    [:div {:class "review-card cloze-card"}
     [:div {:class "cloze-text"}
      [:p display-text]]
     (if show-answer?
       [:div {:class "rating-buttons"}
        [:button {:on {:click #(on-rating :forgot)}} "Forgot"]
        [:button {:on {:click #(on-rating :hard)}} "Hard"]
        [:button {:on {:click #(on-rating :good)}} "Good"]
        [:button {:on {:click #(on-rating :easy)}} "Easy"]]
       [:button {:on {:click #(on-show-answer)}} "Show Answer"])]))

(defn review-screen
  "Main review screen"
  [{:keys [state dir-handle on-state-change]}]
  (let [due-card-hashes (core/due-cards state)
        current-hash (first due-card-hashes)
        remaining (count due-card-hashes)
        [show-answer? set-show-answer!] [(volatile! false) #(vreset! %1 %2)]]
    (if current-hash
      (let [card (core/card-with-meta state current-hash)
            card-type (:type card)]
        [:div {:class "review-screen"}
         [:div {:class "review-header"}
          [:p (str "Cards remaining: " remaining)]]
         [:div {:class "review-content"}
          (case card-type
            :qa [review-card-qa
                 {:card card
                  :show-answer? @show-answer?
                  :on-show-answer #(set-show-answer! show-answer? true)
                  :on-rating (fn [rating]
                               (p/let [ev (core/review-event current-hash rating)
                                       _res (fs/append-to-log dir-handle [ev])
                                       new-state (core/apply-event state ev)]
                                 (set-show-answer! show-answer? false)
                                 (on-state-change new-state)))}]
            :cloze [review-card-cloze
                    {:card card
                     :show-answer? @show-answer?
                     :on-show-answer #(set-show-answer! show-answer? true)
                     :current-deletion-idx 0
                     :on-rating (fn [rating]
                                  (p/let [ev (core/review-event current-hash rating)
                                          _res (fs/append-to-log dir-handle [ev])
                                          new-state (core/apply-event state ev)]
                                    (set-show-answer! show-answer? false)
                                    (on-state-change new-state)))}]
            [:div "Unknown card type"])]])
      [:div {:class "review-screen"}
       [:h2 "No cards due!"]
       [:p "Come back later for more reviews."]])))

(defn main-app
  "Main application component - just shows review screen
   Cards are created/edited directly in cards.md file"
  [{:keys [state dir-handle on-state-change]}]
  [:div {:class "anki-app"}
   [:nav
    [:h1 "Local-First Anki"]
    [:p "Edit cards.md in your folder to add/modify cards"]]
   [:main
    [review-screen {:state state
                    :dir-handle dir-handle
                    :on-state-change on-state-change}]]])

;; App initialization

(defn init-app!
  "Initialize the Anki application"
  []
  (p/let [saved? (fs/has-saved-dir?)
          handle (if saved?
                   (fs/pick-directory)
                   nil)
          evs (if handle
                (fs/load-log handle)
                [])
          init-state (core/reduce-events evs)]
    {:state init-state
     :dir-handle handle
     :screen (if handle :review :setup)}))

;; Main entry point

(defonce app-state (atom {:state {:cards {} :meta {}}
                          :dir-handle nil
                          :screen :setup}))

(defn load-and-sync-cards! [dir-handle]
  "Load cards.md, parse it, and create events for any new cards"
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
