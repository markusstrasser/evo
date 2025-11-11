(ns plugins.smart-editing
  "Smart editing behaviors: context-aware editing operations.

   Features:
   - Context-aware Enter (markup, code blocks, refs, lists)
   - Merge with next block (Delete at end)
   - List formatting (auto-increment, empty list unformat)
   - Checkbox toggling
   - Paired character handling"
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [plugins.context :as ctx]
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
          [:input-char :string]]

   :handler
   (fn [db {:keys [block-id cursor-pos input-char] :as _intent}]
     (let [text (get-block-text db block-id)
           next-char (when (< cursor-pos (count text))
                      (str (nth text cursor-pos)))
           closing-char (get pairs input-char)]

       (cond
         ;; Closing char and next char matches - skip over
         (and (contains? (set (vals pairs)) input-char)
              (= next-char input-char))
         [{:op :update-node
           :id const/session-ui-id
           :props {:editing-block-id block-id
                   :cursor-position (inc cursor-pos)}}]

         ;; Opening char - insert both and position cursor between
         closing-char
         (let [new-text (str (subs text 0 cursor-pos)
                            input-char
                            closing-char
                            (subs text cursor-pos))]
           [{:op :update-node
             :id block-id
             :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position (+ cursor-pos (count input-char))}}])

         ;; Not a paired char - just insert
         :else
         (let [new-text (str (subs text 0 cursor-pos)
                            input-char
                            (subs text cursor-pos))]
           [{:op :update-node
             :id block-id
             :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position (+ cursor-pos (count input-char))}}]))))})

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
  {:doc "Context-aware block splitting on Enter (Logseq parity).

         Behaviors:
         - Inside code fence (```) → insert newline (don't split block)
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
           new-id (str "block-" (random-uuid))

           ;; CRITICAL: Check if cursor is inside code fence (Logseq behavior)
           code-block-ctx (ctx/detect-code-block-at-cursor text cursor-pos)]

       (cond
         ;; LOGSEQ PARITY: Inside code fence → insert newline (don't split)
         code-block-ctx
         (let [new-text (str before "\n" after)]
           [{:op :update-node :id block-id :props {:text new-text}}
            ;; Position cursor after newline
            {:op :update-node
             :id const/session-ui-id
             :props {:cursor-position (inc cursor-pos)}}])

         ;; Empty list marker - just unformat
         (and (empty? after) (list-marker? before))
         [{:op :update-node :id block-id :props {:text ""}}]

         ;; Empty checkbox - unformat
         (and (empty? after) (empty-checkbox? before))
         [{:op :update-node :id block-id :props {:text ""}}]

         ;; Numbered list - increment
         (extract-list-number text)
         (let [num-value (extract-list-number before)
               new-text (str (inc num-value) ". " after)]
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

;; ── Context-Aware Enter (Enhanced with Context Detection) ────────────────────

(intent/register-intent! :context-aware-enter
  {:doc "Handle Enter key with full context awareness.

         Uses plugins.context to detect cursor context and route to appropriate behavior:
         - Inside markup (**, __, etc.) → Exit markup first, then split
         - Inside code block → Insert newline (stay in block)
         - Inside block-ref ((ref)) → Open ref in sidebar (no split)
         - Inside page-ref [[page]] → Navigate to page (no split)
         - Empty list item → Unformat (remove marker)
         - List item with content → Continue list pattern
         - Checkbox → Continue checkbox pattern
         - Plain text → Normal split"

   :spec [:map
          [:type [:= :context-aware-enter]]
          [:block-id :string]
          [:cursor-pos :int]]

   :handler
   (fn [db {:keys [block-id cursor-pos]}]
     (let [text (get-block-text db block-id)
           context (ctx/context-at-cursor text cursor-pos)
           parent (get-in db [:derived :parent-of block-id])]

       (case (:type context)

         ;; Inside markup - exit markup first (move cursor after closing marker)
         :markup
         (let [exit-pos (:end context)]
           [{:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position exit-pos}}])

         ;; Inside code block - insert newline (don't create new block)
         :code-block
         (let [new-text (str (subs text 0 cursor-pos)
                            "\n"
                            (subs text cursor-pos))]
           [{:op :update-node
             :id block-id
             :props {:text new-text}}
            {:op :update-node
             :id const/session-ui-id
             :props {:editing-block-id block-id
                     :cursor-position (inc cursor-pos)}}])

         ;; Inside block-ref - open in sidebar (TODO: implement sidebar)
         :block-ref
         [{:op :update-node
           :id const/session-ui-id
           :props {:sidebar-opened-ref (:uuid context)}}]

         ;; Inside page-ref - navigate to page (TODO: implement page navigation)
         :page-ref
         [{:op :update-node
           :id const/session-ui-id
           :props {:navigate-to-page (:page-name context)}}]

         ;; Checkbox - check for empty
         :checkbox
         (if (str/blank? (:content context))
           ;; Empty checkbox - unformat
           [{:op :update-node
             :id block-id
             :props {:text ""}}]
           ;; Checkbox with content - continue pattern
           (let [before (subs text 0 cursor-pos)
                 after (subs text cursor-pos)
                 new-id (str "block-" (random-uuid))
                 ;; New block gets unchecked checkbox
                 new-text (str "- [ ] " after)
                 marker-len (count (:marker context))]
             (when parent
               [{:op :update-node :id block-id :props {:text before}}
                {:op :create-node :id new-id :type :block :props {:text new-text}}
                {:op :place :id new-id :under parent :at {:after block-id}}
                {:op :update-node
                 :id const/session-ui-id
                 :props {:editing-block-id new-id
                         :cursor-position 6}}])))  ; After "- [ ] "

         ;; List item - check for empty
         :list-item
         (if (str/blank? (:content context))
           ;; Empty list - unformat
           [{:op :update-node
             :id block-id
             :props {:text ""}}]
           ;; List with content - continue pattern
           (let [before (subs text 0 cursor-pos)
                 after (subs text cursor-pos)
                 new-id (str "block-" (random-uuid))]
             (when parent
               (if (:numbered? context)
                 ;; Numbered list - increment
                 (let [new-number (inc (:number context))
                       new-text (str new-number ". " after)]
                   [{:op :update-node :id block-id :props {:text before}}
                    {:op :create-node :id new-id :type :block :props {:text new-text}}
                    {:op :place :id new-id :under parent :at {:after block-id}}
                    {:op :update-node
                     :id const/session-ui-id
                     :props {:editing-block-id new-id
                             :cursor-position (+ (count (str new-number)) 2)}}])
                 ;; Simple list - continue with same marker
                 (let [new-text (str (:marker context) after)]
                   [{:op :update-node :id block-id :props {:text before}}
                    {:op :create-node :id new-id :type :block :props {:text new-text}}
                    {:op :place :id new-id :under parent :at {:after block-id}}
                    {:op :update-node
                     :id const/session-ui-id
                     :props {:editing-block-id new-id
                             :cursor-position (count (:marker context))}}])))))

         ;; Plain text - normal split
         :none
         (let [before (subs text 0 cursor-pos)
               after (subs text cursor-pos)
               new-id (str "block-" (random-uuid))]
           (when parent
             [{:op :update-node :id block-id :props {:text before}}
              {:op :create-node :id new-id :type :block :props {:text after}}
              {:op :place :id new-id :under parent :at {:after block-id}}
              {:op :update-node
               :id const/session-ui-id
               :props {:editing-block-id new-id
                       :cursor-position 0}}]))

         ;; Default (shouldn't happen) - normal split
         (let [before (subs text 0 cursor-pos)
               after (subs text cursor-pos)
               new-id (str "block-" (random-uuid))]
           (when parent
             [{:op :update-node :id block-id :props {:text before}}
              {:op :create-node :id new-id :type :block :props {:text after}}
              {:op :place :id new-id :under parent :at {:after block-id}}])))))})
