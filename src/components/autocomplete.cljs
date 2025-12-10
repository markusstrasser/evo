(ns components.autocomplete
  "Generic autocomplete popup component.

   Features:
   - Positions at text cursor using Range API
   - Uses HTML Popover API for top-layer rendering
   - Keyboard navigation (handled by parent)
   - Highlight matching characters in results

   Usage:
     [autocomplete/Popup {:autocomplete autocomplete-state
                          :on-select #(dispatch [:autocomplete/select])
                          :on-dismiss #(dispatch [:autocomplete/dismiss])}]"
  (:require [plugins.autocomplete :as ac]
            [utils.fuzzy-search :as fuzzy]))

;; ── Positioning ───────────────────────────────────────────────────────────────

(defn- get-caret-rect
  "Get bounding rect of current text selection/caret.

   Returns DOMRect with x, y, top, left, bottom, right, width, height.
   Returns nil if no selection."
  []
  (when-let [selection (.getSelection js/window)]
    (when (pos? (.-rangeCount selection))
      (let [range (.getRangeAt selection 0)]
        (.getBoundingClientRect range)))))

(defn- position-popup!
  "Position popup element at current caret position.

   Uses fixed positioning relative to viewport.
   Flips above caret if not enough room below."
  [popup-el]
  (when-let [rect (get-caret-rect)]
    (let [viewport-height (.-innerHeight js/window)
          popup-height 300 ; max-height from CSS
          space-below (- viewport-height (.-bottom rect))
          show-above? (< space-below popup-height)
          top (if show-above?
                (- (.-top rect) popup-height 4)
                (+ (.-bottom rect) 4))
          left (.-left rect)]
      (set! (.. popup-el -style -position) "fixed")
      (set! (.. popup-el -style -top) (str top "px"))
      (set! (.. popup-el -style -left) (str left "px")))))

;; ── Highlight Rendering ───────────────────────────────────────────────────────

(defn- render-highlighted-label
  "Render label with matched characters highlighted.

   query: The search query
   label: The full label text

   Returns hiccup with :mark elements around matched chars."
  [query label]
  (if (empty? query)
    label
    (let [ranges (fuzzy/highlight-match label query)]
      (if (empty? ranges)
        label
        ;; Build highlighted spans
        (let [chars (vec label)]
          (loop [idx 0
                 remaining-ranges ranges
                 result []]
            (if (>= idx (count chars))
              (into [:span] result)
              (let [[start end] (first remaining-ranges)
                    c (nth chars idx)]
                (cond
                  ;; At start of highlight range
                  (and start (= idx start))
                  (let [highlighted-text (subs label start end)]
                    (recur end
                           (rest remaining-ranges)
                           (conj result [:mark highlighted-text])))

                  ;; Normal character
                  :else
                  (recur (inc idx)
                         remaining-ranges
                         (conj result (str c))))))))))))

;; ── Item Component ────────────────────────────────────────────────────────────

(defn- AutocompleteItem
  "Single autocomplete item.

   Props:
   - item: The item data (may have :type :create-new for new page option)
   - source-type: :page-ref, :command, etc.
   - query: Current search query (for highlighting)
   - selected?: Is this item selected?
   - on-click: Click handler"
  [{:keys [item source-type query selected? on-click]}]
  (let [is-create-new? (= (:type item) :create-new)
        is-command? (= source-type :command)
        label (if is-create-new?
                (:title item)
                (ac/item-label {:type source-type :item item}))]
    [:div.autocomplete-item
     {:class [(when selected? "selected")
              (when is-create-new? "create-new")
              (when is-command? "command-item")]
      :on {:click (fn [e]
                    (.preventDefault e)
                    (.stopPropagation e)
                    (on-click))}}

     (cond
       ;; Create new page option
       is-create-new?
       [:span.create-new-label
        [:span.create-icon "+"]
        " Create page: "
        [:strong (:title item)]]

       ;; Slash command with icon and description
       ;; Note: Replicant doesn't support :<> fragments, use wrapper with display:contents
       is-command?
       (list
        [:span.command-icon {:replicant/key "icon"} (:icon item)]
        [:span.command-content {:replicant/key "content"}
         [:span.command-name (render-highlighted-label query label)]
         [:span.command-description (:description item)]])

       ;; Default: page-ref style
       :else
       (render-highlighted-label query label))]))

;; ── Main Popup Component ──────────────────────────────────────────────────────

(defn Popup
  "Autocomplete popup component.

   Props:
   - autocomplete: Current autocomplete state from view-state
     {:type :page-ref
      :block-id \"...\"
      :trigger-pos 5
      :query \"pro\"
      :selected 0
      :items [...]}
   - on-select: Called when item is selected (click or Enter)
   - on-dismiss: Called when popup should close (Escape, click outside)

   Uses Popover API for top-layer rendering."
  [{:keys [autocomplete on-select on-dismiss]}]
  (when autocomplete
    (let [{:keys [type query selected items]} autocomplete]
      [:div#autocomplete-popup.autocomplete-popup
       {:popover "manual"
        :replicant/on-mount (fn [{:replicant/keys [node]}]
                              (.showPopover node)
                              (position-popup! node))
        :replicant/on-render (fn [{:replicant/keys [node]}]
                               (position-popup! node))}

       (if (empty? items)
         [:div.autocomplete-empty "No matches"]

         (into [:div.autocomplete-items]
               (map-indexed
                (fn [idx item]
                  ;; Call function directly - Replicant doesn't use [Component props] syntax
                  (AutocompleteItem
                   {:item item
                    :source-type type
                    :query query
                    :selected? (= idx selected)
                    :on-click on-select}))
                items)))])))
