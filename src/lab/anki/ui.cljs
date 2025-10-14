(ns lab.anki.ui
  "Anki clone UI using Replicant"
  (:require [clojure.string :as str]
            [clojure.walk]
            [lab.anki.core :as core]
            [lab.anki.fs :as fs]
            [lab.anki.occlusion-creator :as creator]
            [lab.anki.occlusion-creator-ui :as creator-ui]
            [promesa.core :as p]
            [replicant.dom :as r]))

;; State
(defonce !state (atom {:screen :setup
                       :state {:cards {} :meta {}}
                       :events []
                       :dir-handle nil
                       :show-answer? false
                       :current-card-hash nil
                       :decks [] ; Available decks from files
                       :selected-deck nil ; nil = all decks, string = specific deck
                       :show-stats? false ; Stats modal visibility
                       :show-add-card? false ; Card creation panel visibility
                       :new-card-text "" ; Text input for new card
                       :new-card-file "Default.md" ; Selected .md file
                       :available-files [] ; List of .md files in directory
                       :show-command-palette? false ; Command palette visibility
                       :command-search "" ; Command palette search text
                       :show-history? false ; Event history panel visibility
                       }))

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

(defn math-text
  "Render text with MathJax support. Wraps content in a div with unique ID
   and triggers MathJax typesetting after render."
  [content]
  (let [id (str "math-" (random-uuid))]
    ;; Schedule MathJax typesetting after DOM update
    (js/setTimeout
     (fn []
       (when (and js/window.MathJax js/window.MathJax.typesetPromise)
         (js/window.MathJax.typesetPromise
          (clj->js [(js/document.getElementById id)]))))
     80)
    [:div.math {:id id} content]))

;; Components
(defn setup-screen [{:keys [saved-handle]}]
  [:div.setup-screen
   [:h1 "Welcome to Local-First Anki"]
   [:p "To get started, select a folder to store your cards and review data."]
   [:button
    {:on {:click [::select-folder]}}
    (if saved-handle "Select Folder" "Select Folder")]])

(defn rating-buttons []
  [:div.rating-buttons
   (for [[idx rating] (map-indexed vector [:forgot :hard :good :easy])]
     ^{:key rating}
     [:button
      {:on {:click [::rate-card rating]}}
      (str (str/capitalize (name rating)) " (" (inc idx) ")")])])

(defn- determine-occlusion-style
  "Determines the visual style for an occlusion based on review mode and state."
  [occ current-oid mode show-answer?]
  (let [is-current? (= (:oid occ) current-oid)]
    (case [mode is-current? show-answer?]
      ;; In "Hide All" mode: all rects visible, current has focus color, others green
      [:hide-all-guess-one true false] :focused ; Current rect - orange/yellow to show focus
      [:hide-all-guess-one false false] :hidden ; Other rects - green occluded
      [:hide-all-guess-one true true] :revealed ; Current rect - border + answer text
      [:hide-all-guess-one false true] :hidden ; Other rects - STAY green occluded

      ;; In "Hide One" mode: hide current, show others as context
      [:hide-one-guess-one true false] :hidden ; Current rect - green occluded
      [:hide-one-guess-one false false] :context ; Other rects - blue context borders
      [:hide-one-guess-one true true] :revealed ; Current rect - border + answer text
      [:hide-one-guess-one false true] :invisible ; Other rects - disappear

      :invisible))) ; Default to invisible ; Default to invisible

(defn- draw-styled-occlusion!
  "Draws a single occlusion on the canvas according to a specified style."
  [ctx w h occ style]
  (let [shape (:shape occ)
        x (* (:x shape) w)
        y (* (:y shape) h)
        rect-w (* (:w shape) w)
        rect-h (* (:h shape) h)]
    (case style
      :focused
      ;; Focused rect (before answer) - orange/yellow to show which one is being tested
      (do (set! (.-fillStyle ctx) "rgba(255, 165, 0, 1.0)") ; Orange
          (.fillRect ctx x y rect-w rect-h))

      :hidden
      ;; Hidden/occluded rect - solid green
      (do (set! (.-fillStyle ctx) "rgba(0, 255, 0, 1.0)")
          (.fillRect ctx x y rect-w rect-h))

      :revealed
      ;; Revealed rect (after answer) - border + answer text, transparent background
      (do (set! (.-strokeStyle ctx) "#00ff00")
          (set! (.-lineWidth ctx) 3)
          (.strokeRect ctx x y rect-w rect-h)
          (set! (.-fillStyle ctx) "rgba(0, 255, 0, 0.8)")
          (.fillRect ctx x y (min rect-w 200) 25)
          (set! (.-fillStyle ctx) "#000000")
          (set! (.-font ctx) "14px sans-serif")
          (.fillText ctx (:answer occ) (+ x 5) (+ y 17)))

      :context
      ;; Context rect (hide-one mode) - blue border to show as reference
      (do (set! (.-strokeStyle ctx) "#0088ff")
          (set! (.-lineWidth ctx) 2)
          (.strokeRect ctx x y rect-w rect-h))

      :invisible
      nil)))

(defn draw-occlusion-mask!
  "Draw occlusion mask on canvas - supports hide-all-guess-one and hide-one-guess-one modes"
  [canvas card show-answer?]
  (js/console.log "draw-occlusion-mask! called with canvas:" canvas "show-answer?" show-answer?)
  (js/console.log "Card data:" (pr-str card))
  (when canvas
    (let [ctx (.getContext canvas "2d")
          asset (:asset card)
          w (or (:width asset) 400)
          h (or (:height asset) 300)
          image-url (:url asset)]

      ;; Set canvas size
      (set! (.-width canvas) w)
      (set! (.-height canvas) h)

      (if image-url
        ;; Draw uploaded image
        (let [img (js/Image.)]
          (set! (.-onload img)
                (fn []
                  (.drawImage ctx img 0 0 w h)
                  (let [mode (or (:mode card) :hide-one-guess-one)
                        current-oid (:current-oid card)
                        all-occlusions (:occlusions card)]
                    (doseq [occ all-occlusions]
                      (let [style (determine-occlusion-style occ current-oid mode show-answer?)]
                        (draw-styled-occlusion! ctx w h occ style))))))
          (set! (.-src img) image-url))

        ;; Draw noise background (for testing/fallback)
        (do
          (let [image-data (.createImageData ctx w h)
                data (.-data image-data)]
            (dotimes [i (/ (.-length data) 4)]
              (let [val (+ 100 (rand-int 100))
                    idx (* i 4)]
                (aset data idx val)
                (aset data (+ idx 1) val)
                (aset data (+ idx 2) val)
                (aset data (+ idx 3) 255)))
            (.putImageData ctx image-data 0 0))
          (js/console.log "Noise background drawn")
          ;; Draw mask if not revealed
          (when-not show-answer?
            (let [shape (:shape card)
                  x (* (:x shape) w)
                  y (* (:y shape) h)
                  rect-w (* (:w shape) w)
                  rect-h (* (:h shape) h)]
              (js/console.log "Drawing green mask at" x y rect-w rect-h)
              (set! (.-fillStyle ctx) "rgba(0, 255, 0, 1.0)")
              (.fillRect ctx x y rect-w rect-h))))))))

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
       [:button {:on {:click [::show-answer]}} "Show Answer (Space)"])]))

(defn review-history
  "Display recent review events"
  [{:keys [events state]}]
  (let [review-events (->> events
                           (filter #(= :review (:event/type %)))
                           ;; Only show reviews for cards that still exist
                           (filter (fn [event]
                                     (let [card-hash (get-in event [:event/data :card-hash])]
                                       (contains? (:cards state) card-hash))))
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
              [:span.card-preview (get-card-preview card)]
              [:span.rating {:class (str "rating-" (name rating))}
               (str/capitalize (name rating))]]
             [:span.history-time (time-ago timestamp)]]))]])))

(defn review-screen [{:keys [state events show-answer? current-card-hash selected-deck]}]
  (let [;; Filter cards by selected deck if specified
        filtered-cards (if selected-deck
                         (into {} (filter (fn [[_h card]]
                                            (= selected-deck (:deck card)))
                                          (:cards state)))
                         (:cards state))

        ;; Get due cards from filtered set
        due-hashes (->> (core/due-cards state)
                        (filter #(contains? filtered-cards %))
                        vec)

        ;; Use current-card-hash if valid and still due, otherwise first due card
        current-hash (if (and current-card-hash (some #{current-card-hash} due-hashes))
                       current-card-hash
                       (first due-hashes))
        remaining (count due-hashes)]
    (if current-hash
      (let [card (core/card-with-meta state current-hash)]
        [:div.review-screen
         [:div.review-header
          [:p (str "Cards remaining: " remaining)
           (when selected-deck
             (str " (Deck: " selected-deck ")"))]]
         [:div.review-content
          (review-card {:card card :show-answer? show-answer?})]])
      [:div.review-screen
       [:h2 "No cards due!"]
       [:p (if selected-deck
             (str "No cards due in \"" selected-deck "\" deck.")
             "Come back later for more reviews.")]

       (review-history {:events events :state state})])))

(defn stats-modal [{:keys [stats on-close]}]
  [:div.stats-modal-overlay
   {:on {:click [::toggle-stats]}}
   [:div.stats-modal
    {:on {:click (fn [e] (.stopPropagation e))}} ; Prevent close when clicking inside
    [:h2 "Statistics"]
    [:div.stats-content
     [:div.stat-row
      [:span.stat-label "Total Cards:"]
      [:span.stat-value (:total-cards stats)]]
     [:div.stat-row
      [:span.stat-label "Due Now:"]
      [:span.stat-value (:due-now stats)]]
     [:div.stat-row
      [:span.stat-label "New Cards:"]
      [:span.stat-value (:new-cards stats)]]
     [:div.stat-row
      [:span.stat-label "Reviews Today:"]
      [:span.stat-value (:reviews-today stats)]]
     [:div.stat-row
      [:span.stat-label "Total Reviews:"]
      [:span.stat-value (:total-reviews stats)]]
     [:div.stat-row
      [:span.stat-label "Retention Rate:"]
      [:span.stat-value (str (js/Math.round (* 100 (:retention-rate stats))) "%")]]
     [:div.stat-row
      [:span.stat-label "Avg Reviews/Card:"]
      [:span.stat-value (.toFixed (:avg-reviews-per-card stats) 1)]]

     [:h3 "Ratings Breakdown"]
     (let [breakdown (:ratings-breakdown stats)]
       [:div.ratings-breakdown
        (for [rating [:forgot :hard :good :easy]]
          ^{:key rating}
          [:div.stat-row
           [:span.stat-label (str/capitalize (name rating)) ":"]
           [:span.stat-value (get breakdown rating 0)]])])]
    [:button.close-button
     {:on {:click [::toggle-stats]}}
     "Close"]]])

(defn add-card-panel [{:keys [card-text selected-file available-files]}]
  (let [parsed-card (core/parse-card card-text)
        is-valid? (and (seq card-text) (some? parsed-card))
        validation-msg (cond
                         (empty? card-text) ""
                         is-valid? (str "✓ Valid " (name (:type parsed-card)) " card")
                         :else "✗ Invalid format - use 'q ... / a ...' or 'c ...'")]
    [:div.add-card-panel
     [:h3 "Add New Card"]
     [:div.add-card-form
      [:textarea
       {:placeholder "Type card here:\nq What is 2+2?\na 4\n\nOR\n\nc The [capital] of France is [Paris]"
        :value card-text
        :rows 5
        :on {:input [::update-card-text :event/target.value]}}]
      [:div.validation-status
       {:class (if is-valid? "valid" "invalid")}
       validation-msg]
      [:div.file-selector
       [:label "Save to: "]
       [:select
        {:value selected-file
         :on {:change [::select-card-file :event/target.value]}}
        (for [file available-files]
          ^{:key file}
          [:option {:value file} file])]]
      [:div.add-card-buttons
       [:button.btn-primary
        {:disabled (not is-valid?)
         :on {:click [::save-new-card]}}
        "Add Card"]
       [:button.btn-ghost
        {:on {:click [::toggle-add-card]}}
        "Cancel"]]]]))

(def commands
  [{:id :add-card :title "Add Card" :shortcut ", a" :action ::toggle-add-card}
   {:id :stats :title "View Statistics" :shortcut ", s" :action ::toggle-stats}
   {:id :create-occlusion :title "Create Occlusion Card" :shortcut ", o" :action ::create-occlusion}
   {:id :change-folder :title "Change Folder" :shortcut ", f" :action ::select-folder}
   {:id :toggle-history :title "Toggle Event History" :shortcut ", h" :action ::toggle-history}
   {:id :undo :title "Undo Last Review" :shortcut "u" :action ::undo}
   {:id :redo :title "Redo Review" :shortcut "r" :action ::redo}])

(defn command-palette [{:keys [search-text]}]
  (let [filtered-commands (if (seq search-text)
                            (filter #(str/includes? (str/lower-case (:title %))
                                                   (str/lower-case search-text))
                                   commands)
                            commands)]
    [:div.command-palette
     [:div.command-search
      [:input {:type "text"
               :placeholder "Type a command..."
               :value search-text
               :autoFocus true
               :on {:input [::update-command-search :event/target.value]}}]]
     [:div.command-list
      (for [cmd filtered-commands]
        ^{:key (:id cmd)}
        [:div.command-item
         {:on {:click [(:action cmd)]}}
         [:span.command-title (:title cmd)]
         [:span.command-shortcut (:shortcut cmd)]])]]))

(defn history-panel [{:keys [events state]}]
  (let [event-status (core/build-event-status-map events)
        all-events (reverse events)] ; Show newest first
    [:div.history-panel
     [:div.history-header
      [:h3 "Event History"]
      [:button {:on {:click [::toggle-history]}} "Close"]]
     [:div.history-events
      (for [event all-events]
        (let [event-id (:event/id event)
              status (get event-status event-id :active)
              is-active? (= status :active)]
          ^{:key (str event-id)}
          [:div.event-item
           {:class (if is-active? "active" "undone")}
           [:span.event-status
            {:class (if is-active? "active" "undone")}
            (if is-active? "active" "undone")]
           [:div.event-type (name (:event/type event))]
           [:div.event-data
            (case (:event/type event)
              :review (str "Card: " (subs (str (get-in event [:event/data :card-hash])) 0 8)
                          " | Rating: " (name (get-in event [:event/data :rating])))
              :card-created (str "Hash: " (subs (str (get-in event [:event/data :card-hash])) 0 8)
                                " | Deck: " (get-in event [:event/data :deck]))
              :undo (str "Undo event: " (subs (str (get-in event [:event/data :target-event-id])) 0 8))
              :redo (str "Redo event: " (subs (str (get-in event [:event/data :target-event-id])) 0 8))
              (pr-str (:event/data event)))]
           [:div.event-timestamp (time-ago (:event/timestamp event))]]))]]))

(defn main-app [{:keys [screen state events show-answer? saved-handle current-card-hash decks selected-deck show-stats? show-add-card? new-card-text new-card-file available-files show-command-palette? command-search show-history?]}]
  (let [undo-stack (:undo-stack state)
        redo-stack (:redo-stack state)
        can-undo? (seq undo-stack)
        can-redo? (seq redo-stack)]
    [:div.anki-app
     [:nav
      [:h1 "Local-First Anki"]
      [:p "Edit .md files in your folder to add/modify cards"]
      [:div.nav-buttons
       (when (= screen :review)
         [:div {:style {:display "flex" :gap "10px" :align-items "center"}}
          ;; Deck selector
          (when (seq decks)
            [:select {:value (or selected-deck "")
                      :on {:change [::select-deck :event/target.value]}}
             [:option {:value ""} "All Decks"]
             (for [deck decks]
               ^{:key deck}
               [:option {:value deck} deck])])
          [:button.btn-secondary {:on {:click [::toggle-add-card]}}
           "Add Card" [:kbd ", a"]]
          [:button.btn-secondary {:on {:click [::toggle-stats]}}
           "Stats" [:kbd ", s"]]
          [:button.btn-secondary {:on {:click [::toggle-history]}}
           "History" [:kbd ", h"]]
          [:button.btn-secondary {:on {:click [::create-occlusion]}}
           "Occlusion" [:kbd ", o"]]
          [:button.btn-secondary {:on {:click [::select-folder]}}
           "Folder" [:kbd ", f"]]])
       [:div.undo-redo-buttons
        [:button.btn-secondary {:disabled (not can-undo?)
                  :on {:click [::undo]}}
         "Undo" [:kbd "u"]]
        [:button.btn-secondary {:disabled (not can-redo?)
                  :on {:click [::redo]}}
         "Redo" [:kbd "r"]]]]]
     [:main
      (case screen
        :setup (setup-screen {:saved-handle saved-handle})
        :review (review-screen {:state state :events events :show-answer? show-answer? :current-card-hash current-card-hash :selected-deck selected-deck})
        :create-occlusion (creator-ui/creator-screen {:state @creator/!creator-state
                                                      :on-save [::save-occlusion-card]
                                                      :on-cancel [::cancel-occlusion]})
        [:div "Unknown screen"])]
     ;; Stats modal overlay
     (when show-stats?
       (stats-modal {:stats (core/compute-stats state events)
                     :on-close [::toggle-stats]}))
     ;; Add card panel overlay
     (when show-add-card?
       [:div.add-card-overlay
        {:on {:click [::toggle-add-card]}}
        [:div {:on {:click (fn [e] (.stopPropagation e))}}
         (add-card-panel {:card-text new-card-text
                          :selected-file new-card-file
                          :available-files available-files})]])
     ;; Command palette overlay
     (when show-command-palette?
       [:div.command-palette-overlay
        {:on {:click [::toggle-command-palette]}}
        [:div {:on {:click (fn [e] (.stopPropagation e))}}
         (command-palette {:search-text command-search})]])
     ;; History panel (not an overlay - fixed position)
     (when show-history?
       (history-panel {:events events :state state}))]))


;; Forward declarations
(declare render!)

;; Event Handlers

(defn load-and-sync-cards!
  "Load cards from .md files (ground truth), sync with event log (metadata only).
   Returns {:state ... :decks [...]}"
  [dir-handle]
  (let [t-start (.now js/performance)]
    (p/let [md-files (fs/load-cards dir-handle) ; [{:deck "Geography" :filename "..." :content "..."}]
            t-files (.now js/performance)
            _ (js/console.log "⏱️  File loading:" (.toFixed (- t-files t-start) 1) "ms")
            events (fs/load-log dir-handle)
            t-events (.now js/performance)
            _ (js/console.log "⏱️  Event log loading:" (.toFixed (- t-events t-files) 1) "ms")]
      (let [;; Extract unique decks
            all-decks (->> md-files
                           (map :deck)
                           distinct
                           sort
                           vec)

            ;; Parse cards from all files with deck info
            cards-with-decks (->> md-files
                                  (mapcat (fn [{:keys [deck content]}]
                                            (->> (str/split content #"\n\n+")
                                                 (keep core/parse-card)
                                                 (map #(assoc % :deck deck)))))
                                  (map (fn [card]
                                         ;; Hash based on content only (not deck)
                                         (let [content-only (dissoc card :deck)
                                               h (core/card-hash content-only)]
                                           {:hash h
                                            :card card})))
                                  vec)

            _ (js/console.log "Parsed" (count cards-with-decks) "cards from" (count md-files) "files")
            _ (js/console.log "Found decks:" (pr-str all-decks))

          ;; Build cards map from files (hash -> card)
          cards-from-files (into {} (map (fn [{:keys [hash card]}]
                                           [hash card])
                                         cards-with-decks))

          ;; Get existing metadata from events
          meta-state (core/reduce-events events)
          existing-meta (get meta-state :meta {})

          ;; Find new cards (in files but not in events)
          existing-hashes (set (keys existing-meta))
          new-cards (remove #(contains? existing-hashes (:hash %)) cards-with-decks)

          _ (js/console.log "Found" (count new-cards) "new cards")

          ;; Create events for new cards (hash + deck only, no content)
          new-events (mapv (fn [{:keys [hash card]}]
                             (core/card-created-event hash (:deck card)))
                           new-cards)]

      ;; Save new events to log
      (when (seq new-events)
        (js/console.log "Saving" (count new-events) "new card events")
        (p/do! (fs/append-to-log dir-handle new-events)))

      ;; Rebuild state with file content + event metadata
      (let [all-events (concat events new-events)
            meta-state (core/reduce-events all-events)
            final-state (assoc meta-state :cards cards-from-files)
            t-end (.now js/performance)]
        (js/console.log "⏱️  Total load time:" (.toFixed (- t-end t-start) 1) "ms")
        {:state final-state
         :decks all-decks})))))

;; Removed: create-test-occlusion-card - test code no longer needed

(defn interpolate-actions
  "Replace event placeholders with actual values from DOM event"
  [event actions]
  (clojure.walk/postwalk
   (fn [x]
     (case x
       :event/target.value (.. event -target -value)
       :event/target.checked (.. event -target -checked)
       x))
   actions))

(defn handle-event [_replicant-data [action & args]]
  (case action
    ::select-folder
    (p/let [handle (fs/pick-directory)]
      (js/console.log "Folder selected:" handle)
      (fs/save-dir-handle handle)
      (p/let [result (load-and-sync-cards! handle)
              events (fs/load-log handle)
              state (:state result)
              decks (:decks result)
              md-files (fs/list-md-files handle)
              ;; Ensure Default.md is in the list
              files-with-default (if (seq md-files)
                                   (if (some #(= "Default.md" %) md-files)
                                     md-files
                                     (vec (cons "Default.md" md-files)))
                                   ["Default.md"])]
        (swap! !state assoc
               :dir-handle handle
               :state state
               :events events
               :saved-handle handle
               :screen :review
               :current-card-hash nil
               :decks decks
               :selected-deck nil
               :available-files files-with-default
               :new-card-file (first files-with-default))
        (js/console.log "Loaded state with" (count (:cards state)) "total cards")
        (js/console.log "Available decks:" (pr-str decks))
        (js/console.log "Available .md files:" (pr-str files-with-default))))

    ::show-answer
    (swap! !state assoc :show-answer? true)

    ::select-deck
    (let [deck-name (first args)]
      (swap! !state assoc :selected-deck (when (seq deck-name) deck-name))
      (js/console.log "Selected deck:" (or deck-name "All Decks")))

    ::rate-card
    (let [{:keys [state events dir-handle current-card-hash]} @!state
          rating (first args)
          due (core/due-cards state)
          review-hash (or current-card-hash (first due))]
      (when (and review-hash dir-handle)
        (let [card (get-in state [:cards review-hash])
              old-meta (get-in state [:meta review-hash])
              t-start (.now js/performance)]
          (js/console.log "📝 Rating card:" (get-card-preview card))
          (js/console.log "   Hash:" review-hash "Rating:" rating)
          (js/console.log "   Old due-at:" (:due-at old-meta) "Reviews:" (:reviews old-meta))

          (let [event (core/review-event review-hash rating)
                new-events (conj events event)
                new-meta-state (core/reduce-events new-events)
                new-state (assoc new-meta-state :cards (:cards state))
                new-meta (get-in new-state [:meta review-hash])
                next-due (core/due-cards new-state)
                next-hash (first next-due)
                t-end (.now js/performance)]

            (js/console.log "   New due-at:" (:due-at new-meta) "Interval:" (:interval-days new-meta) "days")
            (js/console.log "   Processing time:" (.toFixed (- t-end t-start) 1) "ms")
            (js/console.log "   Remaining due:" (count next-due))

            ;; Fire and forget - write to disk async (no waiting)
            (fs/append-to-log dir-handle [event])
            ;; Update UI immediately
            (swap! !state assoc
                   :state new-state
                   :events new-events
                   :show-answer? false
                   :current-card-hash next-hash)))))

    ::undo
    (let [{:keys [state dir-handle events]} @!state
          undo-stack (:undo-stack state)]
      (when (and (seq undo-stack) dir-handle)
        (let [target-event-id (last undo-stack)
              target-event (first (filter #(= target-event-id (:event/id %)) events))
              event (core/undo-event target-event-id)
              new-events (conj events event)
              new-meta-state (core/reduce-events new-events)
              new-state (assoc new-meta-state :cards (:cards state))
              affected-hash (when (= :review (:event/type target-event))
                              (get-in target-event [:event/data :card-hash]))
              due-cards (core/due-cards new-state)
              next-hash (if (and affected-hash (some #{affected-hash} due-cards))
                          affected-hash
                          (first due-cards))]
          (js/console.log "Undoing event" target-event-id)
          ;; Fire and forget - write to disk async
          (fs/append-to-log dir-handle [event])
          ;; Update UI immediately
          (swap! !state assoc
                 :state new-state
                 :events new-events
                 :show-answer? false
                 :current-card-hash next-hash)
          (js/console.log "Undo complete, undo stack:" (count (:undo-stack new-state))))))

    ::redo
    (let [{:keys [state dir-handle events]} @!state
          redo-stack (:redo-stack state)]
      (when (and (seq redo-stack) dir-handle)
        (let [target-event-id (last redo-stack)
              target-event (first (filter #(= target-event-id (:event/id %)) events))
              event (core/redo-event target-event-id)
              new-events (conj events event)
              new-meta-state (core/reduce-events new-events)
              new-state (assoc new-meta-state :cards (:cards state))
              due-cards (core/due-cards new-state)
              next-hash (first due-cards)]
          (js/console.log "Redoing event" target-event-id)
          ;; Fire and forget - write to disk async
          (fs/append-to-log dir-handle [event])
          ;; Update UI immediately
          (swap! !state assoc
                 :state new-state
                 :events new-events
                 :show-answer? false
                 :current-card-hash next-hash)
          (js/console.log "Redo complete, redo stack:" (count (:redo-stack new-state))))))

    ::create-occlusion
    (do
      (creator/reset-creator!)
      (swap! !state assoc :screen :create-occlusion))

    ::save-occlusion-card
    (let [{:keys [dir-handle]} @!state
          occlusion-card (creator/create-occlusion-card)]
      (when (and occlusion-card dir-handle)
        (let [occlusions (:occlusions occlusion-card)
              mode (:mode occlusion-card)]
          (js/console.log "Saving" (count occlusions) "occlusion cards in mode:" mode)
          ;; Create individual cards for each occlusion
          (p/let [individual-cards (mapv (fn [occ]
                                           {:type :image-occlusion/item
                                            :asset (:asset occlusion-card)
                                            :prompt (:prompt occlusion-card)
                                            :mode mode
                                            :occlusions occlusions ; ALL occlusions
                                            :current-oid (:oid occ) ; Which one to test
                                            :answer (:answer occ)
                                            :shape (:shape occ)}) ; For backwards compat
                                         occlusions)
                  ;; Save to Occlusions.md file (ground truth)
                  _ (fs/append-occlusion-cards dir-handle individual-cards)
                  ;; Reload all cards from files (picks up new occlusions)
                  {:keys [state decks]} (load-and-sync-cards! dir-handle)
                  ;; Reload events from log
                  events (fs/load-log dir-handle)]
            (swap! !state assoc
                   :state state
                   :events events
                   :decks decks
                   :screen :review
                   :show-answer? false
                   :current-card-hash (first (core/due-cards state)))
            (creator/reset-creator!)
            (js/console.log (count individual-cards) "occlusion cards saved to Occlusions.md")))))

    ::cancel-occlusion
    (do
      (creator/reset-creator!)
      (swap! !state assoc :screen :review))

    ::toggle-stats
    (swap! !state update :show-stats? not)

    ::toggle-add-card
    (swap! !state update :show-add-card? not)

    ::toggle-command-palette
    (swap! !state assoc :show-command-palette? (not (:show-command-palette? @!state))
                        :command-search "")

    ::update-command-search
    (let [new-text (first args)]
      (swap! !state assoc :command-search new-text))

    ::toggle-history
    (swap! !state update :show-history? not)

    ::update-card-text
    (let [new-text (first args)]
      (swap! !state assoc :new-card-text new-text))

    ::select-card-file
    (let [filename (first args)]
      (swap! !state assoc :new-card-file filename))

    ::save-new-card
    (let [{:keys [dir-handle new-card-text new-card-file]} @!state]
      (when (and dir-handle (seq new-card-text))
        (let [parsed-card (core/parse-card new-card-text)]
          (when parsed-card
            (js/console.log "Saving new card to" new-card-file)
            (p/let [_ (fs/append-card-text dir-handle new-card-file new-card-text)
                    ;; Reload all cards from files
                    {:keys [state decks]} (load-and-sync-cards! dir-handle)
                    events (fs/load-log dir-handle)]
              (swap! !state assoc
                     :state state
                     :events events
                     :decks decks
                     :new-card-text ""
                     :show-add-card? false)
              (js/console.log "Card saved and reloaded"))))))

    (js/console.warn "Unknown action:" action)))

;; Rendering

(defn render! []
  (r/render (js/document.getElementById "root")
            (main-app @!state)))

;; Keyboard shortcuts

(defonce !leader-key-active (atom false))
(defonce !leader-timeout (atom nil))

(defn clear-leader-key! []
  (reset! !leader-key-active false)
  (when-let [timeout @!leader-timeout]
    (js/clearTimeout timeout)
    (reset! !leader-timeout nil)))

(defn activate-leader-key! []
  (reset! !leader-key-active true)
  ;; Clear leader key after 1.5 seconds if no follow-up key
  (when-let [timeout @!leader-timeout]
    (js/clearTimeout timeout))
  (reset! !leader-timeout (js/setTimeout clear-leader-key! 1500)))

(defn handle-keydown [e]
  (let [{:keys [screen show-answer?]} @!state
        key (.-key e)
        ;; Ignore if typing in input/textarea/select
        target (.-target e)
        tag-name (when (and target (.-tagName target))
                   (str/lower-case (.-tagName target)))
        is-input? (contains? #{"input" "textarea" "select"} tag-name)]

    (when-not is-input?
      ;; Leader key sequence
      (cond
        ;; Comma activates leader key
        (= key ",")
        (do (.preventDefault e)
            (activate-leader-key!)
            (js/console.log "Leader key activated"))

        ;; Leader key + command
        @!leader-key-active
        (do (.preventDefault e)
            (clear-leader-key!)
            (case key
              "a" (handle-event nil [::toggle-add-card])
              "s" (handle-event nil [::toggle-stats])
              "h" (handle-event nil [::toggle-history])
              "o" (handle-event nil [::create-occlusion])
              "f" (handle-event nil [::select-folder])
              nil)
            (render!))

        ;; Regular shortcuts (review screen)
        (= screen :review)
        (cond
          ;; Space to reveal answer
          (and (= key " ") (not show-answer?))
          (do (.preventDefault e)
              (handle-event nil [::show-answer])
              (render!))

          ;; Number keys for rating (only when answer is shown)
          (and show-answer? (contains? #{"1" "2" "3" "4"} key))
          (do (.preventDefault e)
              (let [rating (case key
                            "1" :forgot
                            "2" :hard
                            "3" :good
                            "4" :easy)]
                (handle-event nil [::rate-card rating])
                (render!)))

          ;; u for undo
          (and (= key "u") (seq (:undo-stack (:state @!state))))
          (do (.preventDefault e)
              (handle-event nil [::undo])
              (render!))

          ;; r for redo
          (and (= key "r") (seq (:redo-stack (:state @!state))))
          (do (.preventDefault e)
              (handle-event nil [::redo])
              (render!)))

        ;; Default: do nothing
        :else nil))))


;; Initialization

(defn ^:export main []
  (js/console.log "Anki app starting with Replicant...")

  ;; Set up dispatch with interpolation middleware
  (r/set-dispatch!
   (fn [event-data handler-data]
     (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (let [dom-event (:replicant/dom-event event-data)
             enriched-actions (interpolate-actions dom-event handler-data)]
         (handle-event event-data enriched-actions)
         ;; Re-render after handling event
         (render!)))))

  ;; Set up keyboard shortcuts
  (.addEventListener js/document "keydown" handle-keydown)

  (render!)
  (add-watch !state :render (fn [_ _ _ _] (render!)))

  ;; Watch creator state for changes (needed for image occlusion creator)
  (add-watch creator/!creator-state :render (fn [_ _ _ _] (render!)))

  ;; Load debug helpers in development
  (when ^boolean js/goog.DEBUG
    (js/console.log "🔧 Debug mode enabled. Try: checkIntegrity()")
    (set! js/window.checkIntegrity
          (fn []
            (let [s @!state
                  issues (core/check-integrity (:state s) (:events s))]
              (if (empty? issues)
                (do
                  (js/console.log "✅ No integrity issues found")
                  #js {:ok true :issues #js []})
                (do
                  (js/console.log "⚠️  Found" (count issues) "issue(s):")
                  (doseq [issue issues]
                    (let [icon (case (:severity issue)
                                 :error "❌"
                                 :warning "⚠️ "
                                 :info "ℹ️ ")]
                      (js/console.log icon (:message issue))
                      (when (:data issue)
                        (js/console.log "   Data:" (pr-str (:data issue))))))
                  #js {:ok false :issues (clj->js issues)}))))))

  ;; Auto-resume last session if available
  (p/let [saved-handle (fs/load-dir-handle)]
    (if saved-handle
      (do
        (js/console.log "Auto-resuming last session...")
        (p/catch
         (p/let [permission (.requestPermission saved-handle #js {:mode "readwrite"})]
           (if (= permission "granted")
             (p/let [result (load-and-sync-cards! saved-handle)
                     events (fs/load-log saved-handle)
                     state (:state result)
                     decks (:decks result)
                     md-files (fs/list-md-files saved-handle)
                     files-with-default (if (seq md-files)
                                          (if (some #(= "Default.md" %) md-files)
                                            md-files
                                            (vec (cons "Default.md" md-files)))
                                          ["Default.md"])]
               (swap! !state assoc
                      :dir-handle saved-handle
                      :state state
                      :events events
                      :saved-handle saved-handle
                      :screen :review
                      :decks decks
                      :selected-deck nil
                      :available-files files-with-default
                      :new-card-file (first files-with-default))
               (js/console.log "Auto-resumed with" (count (:cards state)) "cards")
               (js/console.log "Available decks:" (pr-str decks))
               (js/console.log "Available .md files:" (pr-str files-with-default)))
             (do
               (js/console.warn "Permission denied, showing setup screen")
               (swap! !state assoc :saved-handle saved-handle))))
         (fn [e]
           (js/console.error "Failed to auto-resume:" (.-message e))
           (swap! !state assoc :saved-handle saved-handle))))
      (js/console.log "No saved session, showing setup screen"))))
