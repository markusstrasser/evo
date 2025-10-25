(ns components.mock-text
  "Mock-text cursor row detection (Logseq technique).

   Uses a hidden DOM element to mirror contenteditable text and detect
   cursor position relative to text rows. Enables seamless up/down navigation
   at block boundaries.")

(defn update-mock-text!
  "Update hidden mock-text element with current contenteditable content.
   This enables cursor row position detection (Logseq technique)."
  [text]
  (when-let [mock-elem (js/document.getElementById "mock-text")]
    (let [content (str text "0")
          chars (seq content)]
      (set! (.-innerHTML mock-elem) "")
      (doseq [[idx c] (map-indexed vector chars)]
        (let [span (.createElement js/document "span")]
          (.setAttribute span "id" (str "mock-text_" idx))
          (if (= c \newline)
            (do
              (set! (.-textContent span) "0")
              (.appendChild span (.createElement js/document "br")))
            (set! (.-textContent span) (str c)))
          (.appendChild mock-elem span))))))

(defn- get-caret-rect
  "Get bounding rect of cursor position in contenteditable element."
  [elem]
  (when elem
    (let [selection (.getSelection js/window)]
      (when (and selection (> (.-rangeCount selection) 0))
        (let [range (.getRangeAt selection 0)
              rects (.getClientRects range)]
          (when (> (.-length rects) 0)
            (.item rects 0)))))))

(defn- get-mock-text-tops
  "Get unique Y positions (tops) of all characters in mock-text element."
  []
  (when-let [mock-elem (js/document.getElementById "mock-text")]
    (let [children (array-seq (.-children mock-elem))
          tops (->> children
                    (map (fn [span]
                           (let [rect (.getBoundingClientRect span)]
                             (.-top rect))))
                    distinct
                    sort)]
      tops)))

(defn detect-cursor-row-position
  "Detect if cursor is on first/last row of contenteditable.
   Returns {:first-row? bool :last-row? bool}"
  [elem]
  (when-let [cursor-rect (get-caret-rect elem)]
    (let [tops (get-mock-text-tops)
          cursor-top (.-top cursor-rect)]
      {:first-row? (and (seq tops) (= (first tops) cursor-top))
       :last-row? (and (seq tops) (= (last tops) cursor-top))})))

(defn has-text-selection?
  "Check if user has selected text within contenteditable."
  []
  (let [selection (.getSelection js/window)]
    (and selection
         (not (.-isCollapsed selection))
         (> (.-rangeCount selection) 0))))
