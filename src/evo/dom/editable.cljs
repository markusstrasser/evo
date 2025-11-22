(ns evo.dom.editable
  "DOM driver for contenteditable elements using use-editable patterns.

   Architecture: MutationObserver + rollback pattern ensures DOM reflects state.

   Key concepts:
   - DOM is never the source of truth, only a view
   - On every DOM mutation, we:
     1. Read the new state from DOM
     2. Rollback DOM to last known state
     3. Dispatch onChange with new state
     4. Re-render will apply the state back to DOM

   This prevents the 'fighting' between DB updates and browser cursor management.")

;; ── DOM Position Extraction (from use-editable) ───────────────────────────────

(defn- traverse-text-nodes
  "Traverse text nodes in element, calling f with accumulator and each text node.
   Returns [final-accumulator found-node] when f returns truthy, or [final-accumulator nil]."
  [element f init]
  (let [walker (.createTreeWalker js/document element (.-SHOW_TEXT js/NodeFilter) nil)]
    (loop [acc init]
      (let [node (.nextNode walker)]
        (if (nil? node)
          [acc nil]
          (let [result (f acc node)]
            (if (true? (:found result))
              [(:acc result) node]
              (recur (:acc result)))))))))

(defn get-position
  "Extract current cursor position from contenteditable element.

   Returns map with:
   {:anchor int   ; start of selection
    :head int}    ; end of selection (= anchor for collapsed cursor)

   Returns nil if no selection or element not focused."
  [element]
  (when element
    (let [selection (.getSelection js/window)]
      (when (and selection
                 (> (.-rangeCount selection) 0)
                 (.contains element (.-anchorNode selection)))
        (let [range (.getRangeAt selection 0)
              anchor-node (.-anchorNode range)
              anchor-offset (.-anchorOffset range)
              focus-node (.-focusNode selection)
              focus-offset (.-focusOffset selection)]
          ;; Traverse text nodes to convert DOM offsets to character indices
          (let [[anchor-pos _] (traverse-text-nodes
                                 element
                                 (fn [acc node]
                                   (let [text (.-textContent node)
                                         node-len (count text)]
                                     (if (identical? node anchor-node)
                                       {:acc (+ acc anchor-offset) :found true}
                                       {:acc (+ acc node-len) :found false})))
                                 0)
                [focus-pos _] (traverse-text-nodes
                                element
                                (fn [acc node]
                                  (let [text (.-textContent node)
                                        node-len (count text)]
                                    (if (identical? node focus-node)
                                      {:acc (+ acc focus-offset) :found true}
                                      {:acc (+ acc node-len) :found false})))
                                0)]
            {:anchor anchor-pos
             :head focus-pos}))))))

;; ── DOM Position Application (from use-editable) ──────────────────────────────

(defn make-range
  "Create DOM Range at specified character positions.

   - element: contenteditable element
   - anchor: start position (character index)
   - head: end position (character index)

   Returns Range object or nil if positions invalid."
  [element anchor head]
  (when element
    (let [range (.createRange js/document)]
      ;; Find text node and offset for anchor
      (let [[_ anchor-node] (traverse-text-nodes
                              element
                              (fn [acc node]
                                (let [text (.-textContent node)
                                      node-len (count text)
                                      new-acc (+ acc node-len)]
                                  (if (and (>= anchor acc) (< anchor new-acc))
                                    {:acc acc :found true}
                                    {:acc new-acc :found false})))
                              0)]
        (when anchor-node
          (let [anchor-text (.-textContent anchor-node)
                [acc-before _] (traverse-text-nodes
                                 element
                                 (fn [acc node]
                                   (if (identical? node anchor-node)
                                     {:acc acc :found true}
                                     {:acc (+ acc (count (.-textContent node))) :found false}))
                                 0)
                anchor-offset (- anchor acc-before)]
            (.setStart range anchor-node (min anchor-offset (count anchor-text)))

            ;; Find text node and offset for head
            (let [[_ head-node] (traverse-text-nodes
                                  element
                                  (fn [acc node]
                                    (let [text (.-textContent node)
                                          node-len (count text)
                                          new-acc (+ acc node-len)]
                                      (if (and (>= head acc) (< head new-acc))
                                        {:acc acc :found true}
                                        {:acc new-acc :found false})))
                                  0)]
              (when head-node
                (let [head-text (.-textContent head-node)
                      [acc-before-head _] (traverse-text-nodes
                                            element
                                            (fn [acc node]
                                              (if (identical? node head-node)
                                                {:acc acc :found true}
                                                {:acc (+ acc (count (.-textContent node))) :found false}))
                                            0)
                      head-offset (- head acc-before-head)]
                  (.setEnd range head-node (min head-offset (count head-text)))
                  range)))))))))  ;; Added one more closing paren

(defn apply-selection!
  "Apply selection to element using Range API.

   - element: contenteditable element
   - cursor-pos: map with {:anchor int :head int}"
  [element {:keys [anchor head]}]
  (when element
    (when-let [range (make-range element anchor head)]
      (let [selection (.getSelection js/window)]
        (.removeAllRanges selection)
        (.addRange selection range)))))

;; ── Controlled Contenteditable (MutationObserver Pattern) ─────────────────────

(defn extract-text
  "Extract plain text from contenteditable, handling BR elements."
  [element]
  (when element
    (-> (.-textContent element)
        (str ""))))  ; Ensure string

(defn setup-controlled-editable!
  "Set up controlled contenteditable with MutationObserver + rollback pattern.

   - element: contenteditable DOM element
   - on-change: callback (fn [text cursor-pos]) called when user edits
   - initial-text: initial text content
   - initial-cursor: initial cursor position {:anchor int :head int}

   Returns cleanup function to disconnect observer.

   Pattern:
   1. User types → DOM mutates
   2. Observer fires → extract new state from DOM
   3. Rollback DOM to last known state
   4. Call on-change with new state
   5. Parent updates DB
   6. Re-render applies DB state back to DOM"
  [element on-change initial-text initial-cursor]
  (when element
    ;; Set initial content
    (set! (.-textContent element) initial-text)
    (apply-selection! element initial-cursor)

    ;; Track last known state (what we rendered)
    ;; Store on element so update-controlled-editable! can access them
    (let [!last-text (atom initial-text)
          !last-cursor (atom initial-cursor)]

      ;; Store atoms on element for update-controlled-editable!
      (aset element "__editable-last-text" !last-text)
      (aset element "__editable-last-cursor" !last-cursor)

      (let [;; Create MutationObserver
            observer (js/MutationObserver.
                       (fn [mutations]
                         ;; Read new state from DOM before rollback
                         (let [new-text (extract-text element)
                               new-cursor (or (get-position element) @!last-cursor)]

                           ;; Rollback DOM to last known state
                           (when (not= new-text @!last-text)
                             (set! (.-textContent element) @!last-text)
                             (apply-selection! element @!last-cursor))

                           ;; Notify parent of change
                           (when (or (not= new-text @!last-text)
                                     (not= new-cursor @!last-cursor))
                             (on-change new-text new-cursor)))))]

        ;; Start observing
        (.observe observer element
                  #js {:characterData true
                       :characterDataOldValue true
                       :childList true
                       :subtree true})

        ;; Return cleanup function
        (fn cleanup []
          (.disconnect observer)))))
  ;; No element, return no-op cleanup
  (fn []))

(defn update-controlled-editable!
  "Update controlled contenteditable with new state from DB.

   - element: contenteditable DOM element
   - text: new text content
   - cursor-pos: new cursor position {:anchor int :head int}

   Only updates DOM if different from current state (avoid thrashing)."
  [element text cursor-pos]
  (when element
    (let [current-text (extract-text element)
          current-cursor (get-position element)]
      ;; Only update if changed
      (when (not= text current-text)
        (set! (.-textContent element) text))
      (when (not= cursor-pos current-cursor)
        (apply-selection! element cursor-pos))

      ;; CRITICAL: Update atoms so MutationObserver knows this is the new baseline
      (when-let [!last-text (aget element "__editable-last-text")]
        (reset! !last-text text))
      (when-let [!last-cursor (aget element "__editable-last-cursor")]
        (reset! !last-cursor cursor-pos)))))
