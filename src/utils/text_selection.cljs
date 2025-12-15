(ns utils.text-selection
  "Text selection utilities for contenteditable elements.

   Ported from use-editable (https://github.com/kitten/use-editable)
   Provides robust text selection and cursor positioning that works with
   complex DOM structures (text nodes, BR elements, etc.)."
  (:require [clojure.string :as str]))

;; ── Position & Selection ────────────────────────────────────────────────────

(defn get-current-range
  "Get the current selection range from the window."
  []
  (when-let [selection (.getSelection js/window)]
    (when (> (.-rangeCount selection) 0)
      (.getRangeAt selection 0))))

(defn set-current-range!
  "Set the window selection to the given range."
  [range]
  (when-let [selection (.getSelection js/window)]
    (.empty selection)
    (.addRange selection range)))

(defn selection-present?
  "Check if there is a non-collapsed text selection in the window.
   Returns true if user has selected text, false otherwise."
  []
  (when-let [range (get-current-range)]
    (not (.-collapsed range))))

;; ── Text Content Extraction ─────────────────────────────────────────────────

(defn element->text
  "Convert an element's content to plain text, handling text nodes and BR elements.

   contenteditable quirk: Without plaintext-only mode, a pre/pre-wrap element
   must always end with at least one newline character."
  [element]
  (when element
    (loop [queue [(.-firstChild element)]
           result ""]
      (if-let [node (peek queue)]
        (let [next-queue (pop queue)
              node-type (.-nodeType node)]
          (cond
            ;; Text node - append text content
            (= node-type js/Node.TEXT_NODE)
            (let [text (.-textContent node)
                  queue' (cond-> next-queue
                           (.-nextSibling node) (conj (.-nextSibling node))
                           (.-firstChild node) (conj (.-firstChild node)))]
              (recur queue' (str result text)))

            ;; BR element - append newline
            (and (= node-type js/Node.ELEMENT_NODE)
                 (= (.-nodeName node) "BR"))
            (let [queue' (cond-> next-queue
                           (.-nextSibling node) (conj (.-nextSibling node))
                           (.-firstChild node) (conj (.-firstChild node)))]
              (recur queue' (str result "\n")))

            ;; Other element - traverse children
            :else
            (let [queue' (cond-> next-queue
                           (.-nextSibling node) (conj (.-nextSibling node))
                           (.-firstChild node) (conj (.-firstChild node)))]
              (recur queue' result))))

        ;; Ensure trailing newline for contenteditable quirk
        (if (str/ends-with? result "\n")
          result
          (str result "\n"))))))

;; ── Position Calculation ────────────────────────────────────────────────────

(defn- count-brs-in-range
  "Count BR elements in a range. Used to correct for range.toString() ignoring BRs."
  [range]
  (let [fragment (.cloneContents range)]
    (.-length (.querySelectorAll fragment "br"))))

(defn get-position
  "Get cursor position information from an element.

   Returns map with:
   - :position - absolute character offset from start of element (BR = 1 char)
   - :extent - length of selection (0 if collapsed)
   - :content - text content of current line up to cursor
   - :line - zero-based line number
   
   NOTE: Corrects for range.toString() ignoring BR elements by counting them
   separately. This is critical for multiline contenteditable."
  [element]
  (when-let [range (get-current-range)]
    (let [;; Calculate extent (selection length)
          extent (if (.-collapsed range)
                   0
                   (count (.toString range)))

          ;; Create range from element start to cursor
          until-range (.createRange js/document)
          _ (.setStart until-range element 0)
          _ (.setEnd until-range (.-startContainer range) (.-startOffset range))

          ;; Get text content (doesn't include BRs)
          text-content (.toString until-range)
          ;; Count BR elements that toString() missed
          br-count (count-brs-in-range until-range)
          ;; True position = text chars + BR chars (as newlines)
          position (+ (count text-content) br-count)

          ;; Get text up to cursor for line info
          full-text (element->text element)
          text-before-cursor (subs full-text 0 (min position (count full-text)))
          lines (str/split text-before-cursor #"\n" -1)
          line (dec (count lines))
          line-content (last lines)]
      {:position position
       :extent extent
       :content line-content
       :line line})))

;; ── Range Creation ──────────────────────────────────────────────────────────

(defn- set-range-start!
  "Set range start, handling edge case where offset equals text length."
  [range node offset]
  (if (< offset (count (.-textContent node)))
    (.setStart range node offset)
    (.setStartAfter range node)))

(defn- set-range-end!
  "Set range end, handling edge case where offset equals text length."
  [range node offset]
  (if (< offset (count (.-textContent node)))
    (.setEnd range node offset)
    (.setEndAfter range node)))

(defn make-range
  "Create a Range object spanning from start to end character positions.

   Traverses the DOM tree to find the correct text nodes and offsets.
   Handles both text nodes and BR elements correctly.

   Args:
   - element: The contenteditable element
   - start: Start character position (clamped to >= 0)
   - end: End character position (defaults to start if nil/negative)"
  [element start end]
  (let [start (max 0 start)
        end (if (and end (>= end 0)) end start)
        range (.createRange js/document)]

    (loop [queue [(.-firstChild element)]
           current 0
           position start]
      (when-let [node (peek queue)]
        (let [node-type (.-nodeType node)]
          (cond
            ;; Text node
            (= node-type js/Node.TEXT_NODE)
            (let [length (count (.-textContent node))]
              (if (>= (+ current length) position)
                (let [offset (- position current)]
                  (if (= position start)
                    (do
                      (set-range-start! range node offset)
                      (if (not= end start)
                        ;; Continue to find end position
                        (recur queue current end)
                        ;; Done - start and end are the same
                        range))
                    (do
                      (set-range-end! range node offset)
                      range)))
                ;; Not reached position yet
                (recur (-> (pop queue)
                           (cond-> (.-nextSibling node) (conj (.-nextSibling node))
                                   (.-firstChild node) (conj (.-firstChild node))))
                       (+ current length)
                       position)))

            ;; BR element
            (and (= node-type js/Node.ELEMENT_NODE)
                 (= (.-nodeName node) "BR"))
            (if (>= (inc current) position)
              (if (= position start)
                (do
                  (set-range-start! range node 0)
                  (if (not= end start)
                    (recur queue current end)
                    range))
                (do
                  (set-range-end! range node 0)
                  range))
              (recur (-> (pop queue)
                         (cond-> (.-nextSibling node) (conj (.-nextSibling node))
                                 (.-firstChild node) (conj (.-firstChild node))))
                     (inc current)
                     position))

            ;; Other element - traverse
            :else
            (recur (-> (pop queue)
                       (cond-> (.-nextSibling node) (conj (.-nextSibling node))
                               (.-firstChild node) (conj (.-firstChild node))))
                   current
                   position)))))

    range))

;; ── High-level Operations ───────────────────────────────────────────────────

(defn move-cursor!
  "Position the cursor at the specified character offset or row/column.

   Args:
   - element: The contenteditable element
   - pos: Either a number (character offset) or map with :row and :column"
  [element pos]
  (when element
    (.focus element)
    (let [position (if (number? pos)
                     pos
                     (let [text (element->text element)
                           lines (str/split text #"\n")
                           row-lines (take (:row pos) lines)
                           row-offset (if (pos? (:row pos))
                                        (inc (count (str/join "\n" row-lines)))
                                        0)]
                       (+ row-offset (:column pos))))]
      (set-current-range! (make-range element position position)))))

(defn insert-text!
  "Insert text at the current cursor position.

   Args:
   - element: The contenteditable element
   - text: Text to insert
   - delete-offset: Optional offset for deletion (negative = before cursor, positive = after)"
  [element text delete-offset]
  (when element
    (let [range (get-current-range)]
      ;; Delete current selection
      (.deleteContents range)
      (.collapse range)

      ;; Handle delete-offset
      (when delete-offset
        (let [position (:position (get-position element))
              offset (or delete-offset 0)
              start (+ position (if (neg? offset) offset 0))
              end (+ position (if (pos? offset) offset 0))
              delete-range (make-range element start end)]
          (.deleteContents delete-range)))

      ;; Insert new text
      (when (seq text)
        (let [text-node (.createTextNode js/document text)]
          (.insertNode range text-node)))

      ;; Update cursor position
      (let [position (:position (get-position element))
            new-position (+ position (count text))]
        (set-current-range! (make-range element new-position new-position))))))

(defn update-content!
  "Replace the entire content of the editable while adjusting caret position.

   Calculates the position delta and updates the cursor accordingly."
  [element content on-change]
  (when element
    (let [position (get-position element)
          prev-content (element->text element)
          position-delta (- (count content) (count prev-content))
          new-position (+ (:position position) position-delta)
          new-pos-info (assoc position :position new-position)]
      (on-change content new-pos-info))))

(defn get-state
  "Get current editor state (text content and position)."
  [element]
  (when element
    {:text (element->text element)
     :position (get-position element)}))
