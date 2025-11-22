(ns evo.text
  "Pure text engine for contenteditable state management.

   Architecture: Single source of truth for text + selection.
   All operations are pure functions that take and return text state.

   Text state shape:
   {:text \"hello world\"
    :sel {:anchor 5 :head 5}}  ; collapsed cursor at position 5

   For selections (not just cursor):
   {:text \"hello world\"
    :sel {:anchor 0 :head 5}}  ; \"hello\" selected")

;; ── State Constructors ────────────────────────────────────────────────────────

(defn make-state
  "Create a new text state with optional initial text and cursor position."
  ([text]
   (make-state text (count text)))
  ([text cursor-pos]
   {:text text
    :sel {:anchor cursor-pos :head cursor-pos}}))

(defn make-selection
  "Create a text state with a selection range."
  [text anchor head]
  {:text text
   :sel {:anchor anchor :head head}})

;; ── State Queries ─────────────────────────────────────────────────────────────

(defn collapsed?
  "Check if selection is collapsed (cursor, not a range)."
  [{:keys [sel]}]
  (= (:anchor sel) (:head sel)))

(defn cursor-pos
  "Get cursor position (anchor position for collapsed selection)."
  [{:keys [sel]}]
  (:anchor sel))

(defn selection-range
  "Get selection as [start end] with start <= end."
  [{:keys [sel]}]
  (let [{:keys [anchor head]} sel]
    (if (<= anchor head)
      [anchor head]
      [head anchor])))

(defn selected-text
  "Get the currently selected text."
  [{:keys [text sel]}]
  (let [[start end] (selection-range {:sel sel})]
    (subs text start end)))

;; ── Text Operations ───────────────────────────────────────────────────────────

(defn insert-text
  "Insert text at cursor position or replace selection.

   If selection exists, replaces it. Otherwise inserts at cursor."
  [{:keys [text sel]} new-text]
  (let [[start end] (selection-range {:sel sel})
        before (subs text 0 start)
        after (subs text end)
        result-text (str before new-text after)
        new-pos (+ start (count new-text))]
    {:text result-text
     :sel {:anchor new-pos :head new-pos}}))

(defn delete-backward
  "Delete character before cursor, or delete selection if exists."
  [{:keys [text sel] :as state}]
  (if (collapsed? state)
    ;; Collapsed: delete char before cursor
    (let [pos (:anchor sel)]
      (if (zero? pos)
        state  ; At start, nothing to delete
        (let [before (subs text 0 (dec pos))
              after (subs text pos)
              new-pos (dec pos)]
          {:text (str before after)
           :sel {:anchor new-pos :head new-pos}})))
    ;; Selection: delete selected text
    (let [[start end] (selection-range {:sel sel})
          before (subs text 0 start)
          after (subs text end)]
      {:text (str before after)
       :sel {:anchor start :head start}})))

(defn delete-forward
  "Delete character after cursor, or delete selection if exists."
  [{:keys [text sel] :as state}]
  (if (collapsed? state)
    ;; Collapsed: delete char after cursor
    (let [pos (:anchor sel)]
      (if (>= pos (count text))
        state  ; At end, nothing to delete
        (let [before (subs text 0 pos)
              after (subs text (inc pos))]
          {:text (str before after)
           :sel {:anchor pos :head pos}})))
    ;; Selection: delete selected text (same as delete-backward)
    (delete-backward state)))

(defn move-cursor
  "Move cursor to specific position."
  [state pos]
  (assoc state :sel {:anchor pos :head pos}))

(defn select-range
  "Select text from anchor to head."
  [state anchor head]
  (assoc state :sel {:anchor anchor :head head}))

(defn select-all
  "Select all text."
  [{:keys [text]}]
  {:text text
   :sel {:anchor 0 :head (count text)}})

;; ── High-Level Operations ─────────────────────────────────────────────────────

(defn apply-input
  "Apply user input to text state.

   Handles:
   - Single character insertion
   - Multi-character paste
   - Deletion (backspace/delete)
   - Text replacement"
  [state input-text]
  (insert-text state input-text))

(defn normalize
  "Normalize text state to ensure valid cursor positions."
  [{:keys [text sel] :as state}]
  (let [text-len (count text)
        {:keys [anchor head]} sel
        norm-anchor (max 0 (min text-len anchor))
        norm-head (max 0 (min text-len head))]
    (assoc state :sel {:anchor norm-anchor :head norm-head})))
