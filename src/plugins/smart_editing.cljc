(ns plugins.smart-editing
  "Smart editing behaviors: context-aware editing operations.

   Features:
   - Merge with next block (Delete at end)
   - List formatting (auto-increment, empty list unformat)
   - Checkbox toggling
   - Smart text manipulation (future: markup exit, code blocks)"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            #?(:clj [clojure.string :as str]
               :cljs [clojure.string :as str])))

;; ── Pattern Detection Helpers ────────────────────────────────────────────────

(defn- checkbox-pattern?
  "Check if text starts with checkbox pattern."
  [text]
  (re-matches #"^\[[ xX]\]\s.*" text))

(defn- empty-checkbox?
  "Check if text is empty checkbox marker."
  [text]
  (re-matches #"^\[[ xX]\]\s*$" text))

;; ── Private Helpers ───────────────────────────────────────────────────────────

(defn- get-block-text
  "Get text content of a block."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

(defn- list-marker?
  "Check if text is only a list marker (empty list item)."
  [text]
  (re-matches #"^([-*+]|\d+\.)\s*$" text))

(defn- extract-list-number
  "Extract number from numbered list marker. Returns nil if not numbered."
  [text]
  (when-let [match (re-matches #"^(\d+)\.\s.*" text)]
    #?(:clj (Integer/parseInt (second match))
       :cljs (js/parseInt (second match)))))

(defn- toggle-checkbox-text
  "Toggle checkbox in text. Returns updated text."
  [text]
  (cond
    (str/includes? text "[ ]")
    (str/replace-first text "[ ]" "[x]")

    (str/includes? text "[x]")
    (str/replace-first text "[x]" "[ ]")

    (str/includes? text "[X]")
    (str/replace-first text "[X]" "[ ]")

    :else text))

;; ── Paired Character Handling ──────────────────────────────────────────────────

(def pairs
  "Character pairs that auto-close and delete together."
  {"[" "]"
   "(" ")"
   "{" "}"
   "\"" "\""
   "**" "**"    ; Bold
   "__" "__"    ; Italic
   "~~" "~~"    ; Strikethrough
   "^^" "^^"})  ; Highlight

(intent/register-intent! :insert-paired-char
  {:doc "Insert character with auto-closing pair.

         If opening char typed, insert both and position cursor between.
         If closing char typed and next char matches, skip over it instead."

   :spec [:map
          [:type [:= :insert-paired-char]]
          [:block-id :string]
          [:cursor-pos :int]
          [:char :string]]

   :handler
   (fn [db {:keys [block-id cursor-pos char]}]
     (let [text (get-block-text db block-id)
           next-char (when (< cursor-pos (count text))
                      (str (nth text cursor-pos)))
           closing-char (get pairs char)]

       (cond
         ;; Closing char and next char matches - skip over
         (and (contains? (set (vals pairs)) char)
              (= next-char char))
         [{:op :update-node
           :id const/session-ui-id
           :props {:editing-block-id block-id
                   :cursor-position (inc cursor-pos)}}]

         ;; Opening char - insert both and position cursor between
         closing-char
         (let [new-text (str (subs text 0 cursor-pos)
                            char
                            closing-char
                            (subs text cursor-pos))]
           [{:op :update-node
             :id block-id
             :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position (+ cursor-pos (count char))}}])

         ;; Not a paired char - just insert
         :else
         (let [new-text (str (subs text 0 cursor-pos)
                            char
                            (subs text cursor-pos))]
           [{:op :update-node
             :id block-id
             :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position (+ cursor-pos (count char))}}]))))})

(intent/register-intent! :delete-with-pair-check
  {:doc "Delete character, removing paired closing char if present.

         If cursor is after opening char and before closing char,
         delete both (e.g., [|] becomes empty).

         Otherwise, normal backspace behavior."

   :spec [:map
          [:type [:= :delete-with-pair-check]]
          [:block-id :string]
          [:cursor-pos :int]]

   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (when (pos? cursor-pos)
       (let [text (get-block-text db block-id)
             ;; Check for matching pairs - try multi-char first, then single-char
             ;; Sort pairs by length descending so we check multi-char pairs first
             pair-match (some (fn [[opening closing]]
                               (let [open-len (count opening)
                                     close-len (count closing)
                                     start-pos (- cursor-pos open-len)
                                     end-pos cursor-pos]
                                 (when (and (>= start-pos 0)
                                           (<= (+ cursor-pos close-len) (count text))
                                           (= (subs text start-pos end-pos) opening)
                                           (= (subs text cursor-pos (+ cursor-pos close-len)) closing))
                                   {:opening opening
                                    :closing closing
                                    :open-len open-len
                                    :close-len close-len})))
                             (sort-by (comp - count key) pairs))]  ; Sort by key length descending

         (if pair-match
           ;; Delete both pair characters
           (let [{:keys [open-len close-len]} pair-match
                 new-text (str (subs text 0 (- cursor-pos open-len))
                              (subs text (+ cursor-pos close-len)))]
             [{:op :update-node
               :id block-id
               :props {:text new-text}}
              {:op :update-node
               :id const/session-ui-id
               :props {:editing-block-id block-id
                       :cursor-position (- cursor-pos open-len)}}])

           ;; Normal backspace - delete one char
           (let [new-text (str (subs text 0 (dec cursor-pos))
                              (subs text cursor-pos))]
             [{:op :update-node
               :id block-id
               :props {:text new-text}}
              {:op :update-node
               :id const/session-ui-id
               :props {:editing-block-id block-id
                       :cursor-position (dec cursor-pos)}}])))))})

;; ── Merge Operations ──────────────────────────────────────────────────────────

(intent/register-intent! :merge-with-next
                         {:doc "Merge block with next sibling, delete next block."
                          :spec [:map [:type [:= :merge-with-next]] [:block-id :string]]
                          :handler (fn [db {:keys [block-id]}]
                                     (let [next-id (get-in db [:derived :next-id-of block-id])
                                           curr-text (get-block-text db block-id)
                                           next-text (get-block-text db next-id)
                                           merged-text (str curr-text next-text)
                                           next-children (get-in db [:children-by-parent next-id] [])]
                                       (when next-id
                                         (concat
                   ;; Update current block with merged text
                                          [{:op :update-node :id block-id :props {:text merged-text}}]
                   ;; Move next block's children to current block (as last children)
                                          (map (fn [child-id]
                                                 {:op :place :id child-id :under block-id :at :last})
                                               next-children)
                   ;; Delete next block
                                          [{:op :place :id next-id :under const/root-trash :at :last}]))))})

;; ── List Item Behaviors ───────────────────────────────────────────────────────

(intent/register-intent! :unformat-empty-list
                         {:doc "Remove list marker from empty list item (becomes plain block)."
                          :spec [:map [:type [:= :unformat-empty-list]] [:block-id :string]]
                          :handler (fn [db {:keys [block-id]}]
                                     (let [text (get-block-text db block-id)]
                                       (when (list-marker? text)
                                         [{:op :update-node :id block-id :props {:text ""}}])))})

(intent/register-intent! :split-with-list-increment
                         {:doc "Split block at cursor, incrementing numbered list marker if applicable."
                          :spec [:map [:type [:= :split-with-list-increment]]
                                 [:block-id :string]
                                 [:cursor-pos :int]]
                          :handler (fn [db {:keys [block-id cursor-pos]}]
                                     (let [text (get-block-text db block-id)
                                           before (subs text 0 cursor-pos)
                                           after (subs text cursor-pos)
                                           parent (get-in db [:derived :parent-of block-id])
                                           new-id (str "block-" (random-uuid))
                                           list-num (extract-list-number text)
                                           new-text (if list-num
                                                      (str (inc list-num) ". " after)
                                                      after)]
                                       (when parent
                                         [{:op :update-node :id block-id :props {:text before}}
                                          {:op :create-node :id new-id :type :block :props {:text new-text}}
                                          {:op :place :id new-id :under parent :at {:after block-id}}])))})

;; ── Checkbox Operations ───────────────────────────────────────────────────────

(intent/register-intent! :toggle-checkbox
                         {:doc "Toggle checkbox state in block text ([ ] <-> [x])."
                          :spec [:map [:type [:= :toggle-checkbox]] [:block-id :string]]
                          :handler (fn [db {:keys [block-id]}]
                                     (let [text (get-block-text db block-id)
                                           new-text (toggle-checkbox-text text)]
                                       (when (not= text new-text)
                                         [{:op :update-node :id block-id :props {:text new-text}}])))})

;; ── Smart Split (Unified Enter Key Behavior) ──────────────────────────────────

(intent/register-intent! :smart-split
  {:doc "Context-aware block splitting on Enter.
         
         Behaviors:
         - Empty list marker → unformat to plain block
         - Numbered list → increment number for new block
         - Checkbox → continue checkbox pattern
         - Empty checkbox → unformat
         - Otherwise → simple split"
   :spec [:map
          [:type [:= :smart-split]]
          [:block-id :string]
          [:cursor-pos :int]]
   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (let [text (get-block-text db block-id)
           before (subs text 0 cursor-pos)
           after (subs text cursor-pos)
           parent (get-in db [:derived :parent-of block-id])
           new-id (str "block-" (random-uuid))]
       
       (cond
         ;; Empty list marker - just unformat
         (and (empty? after) (list-marker? before))
         [{:op :update-node :id block-id :props {:text ""}}]

         ;; Empty checkbox - unformat
         (and (empty? after) (empty-checkbox? before))
         [{:op :update-node :id block-id :props {:text ""}}]

         ;; Numbered list - increment
         (extract-list-number text)
         (let [num (extract-list-number before)
               new-text (str (inc num) ". " after)]
           (when parent
             [{:op :update-node :id block-id :props {:text before}}
              {:op :create-node :id new-id :type :block :props {:text new-text}}
              {:op :place :id new-id :under parent :at {:after block-id}}]))

         ;; Checkbox - continue pattern
         (checkbox-pattern? text)
         (let [new-text (str "[ ] " after)]
           (when parent
             [{:op :update-node :id block-id :props {:text before}}
              {:op :create-node :id new-id :type :block :props {:text new-text}}
              {:op :place :id new-id :under parent :at {:after block-id}}]))

         ;; Default split
         :else
         (when parent
           [{:op :update-node :id block-id :props {:text before}}
            {:op :create-node :id new-id :type :block :props {:text after}}
            {:op :place :id new-id :under parent :at {:after block-id}}]))))})
