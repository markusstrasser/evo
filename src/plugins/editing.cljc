(ns plugins.editing
  "Editing plugin: edit mode state, content operations.

   Edit state stored in :ui (ephemeral, not in history).
   Content changes emit ops for full undo/redo support."
  (:require [kernel.intent :as intent]
            [kernel.constants :as const]
            [kernel.history :as H]
            [utils.text :as text]))

;; ── Private Helpers ───────────────────────────────────────────────────────────

(defn- get-block-text
  "Get text content of a block (internal helper)."
  [db block-id]
  (get-in db [:nodes block-id :props :text] ""))

;; ── Intent Implementations (Session State Changes) ───────────────────────────

(intent/register-intent! :enter-edit
                         {:doc "Enter edit mode for a block. Ephemeral - not in undo/redo history.
         Optional :cursor-at can be :start or :end to position cursor.
         Clears selection to maintain edit/view mode mutual exclusivity."
                          :spec [:map [:type [:= :enter-edit]] [:block-id :string] [:cursor-at {:optional true} [:enum :start :end]]]
                          :handler (fn [_db _session {:keys [block-id cursor-at]}]
                                     {:session-updates
                                      {:selection {:nodes #{} :focus nil :anchor nil}
                                       :ui {:editing-block-id block-id
                                            :cursor-position cursor-at}}})})

(intent/register-intent! :exit-edit
                         {:doc "Exit edit mode WITHOUT selecting block. Ephemeral - not in undo/redo history."
                          :spec [:map [:type [:= :exit-edit]]]
                          :handler (fn [_db _session _intent]
                                     {:session-updates {:ui {:editing-block-id nil :cursor-position nil}}})})

(intent/register-intent! :exit-edit-and-select
                         {:doc "Exit edit mode and select the block (Logseq parity).
                                This is the default Escape behavior in Logseq."
                          :spec [:map [:type [:= :exit-edit-and-select]]]
                          :handler (fn [_db session _intent]
                                     (when-let [editing-block-id (get-in session [:ui :editing-block-id])]
                                       {:session-updates
                                        {:ui {:editing-block-id nil :cursor-position nil}
                                         :selection {:nodes #{editing-block-id}
                                                     :focus editing-block-id
                                                     :anchor editing-block-id}}}))})

(intent/register-intent! :enter-edit-selected
                         {:doc "Enter edit mode in selected block at end of text (Logseq parity).
                                Does NOT create a new block."
                          :spec [:map [:type [:= :enter-edit-selected]]]
                          :handler (fn [db session _intent]
                                     (when-let [focused-block (get-in session [:selection :focus])]
                                       (let [text-length (count (get-in db [:nodes focused-block :props :text] ""))]
                                         {:session-updates
                                          {:selection {:nodes #{} :focus nil :anchor nil}
                                           :ui {:editing-block-id focused-block
                                                :cursor-position text-length}}})))})

(intent/register-intent! :clear-cursor-position
                         {:doc "Clear cursor-position from session state. Used after applying cursor position to prevent reapplication."
                          :spec [:map [:type [:= :clear-cursor-position]]]
                          :handler (fn [_db _session _intent]
                                     {:session-updates {:ui {:cursor-position nil}}})})

(intent/register-intent! :update-cursor-state
                         {:doc "Update cursor position state for boundary detection. Ephemeral - not in history."
                          :spec [:map [:type [:= :update-cursor-state]] [:block-id :string] [:first-row? :boolean] [:last-row? :boolean]]
                          :handler (fn [_db session {:keys [block-id first-row? last-row?]}]
                                     (let [current-cursor (get-in session [:ui :cursor] {})
                                           updated-cursor (assoc current-cursor block-id {:first-row? first-row? :last-row? last-row?})]
                                       {:session-updates {:ui {:cursor updated-cursor}}}))})

;; ── Intent Implementations (Structural Changes) ───────────────────────────────

(intent/register-intent! :update-content
                         {:doc "Update block text content."
                          :spec [:map [:type [:= :update-content]] [:block-id :string] [:text :string]]
                          :handler (fn [_db _session {:keys [block-id text]}]
                                     [{:op :update-node :id block-id :props {:text text}}])})

(intent/register-intent! :insert-newline
                         {:doc "Insert a literal newline character at cursor position (Shift+Enter).
                                LOGSEQ PARITY: Does NOT create a new block, just adds \\n to text."
                          :spec [:map [:type [:= :insert-newline]] [:block-id :string] [:cursor-pos :int]]
                          :handler (fn [db _session {:keys [block-id cursor-pos]}]
                                     (let [text (get-block-text db block-id)
                                           new-text (str (subs text 0 cursor-pos)
                                                        "\n"
                                                        (subs text cursor-pos))]
                                       {:ops [{:op :update-node :id block-id :props {:text new-text}}]
                                        :session-updates {:ui {:editing-block-id block-id
                                                               :cursor-position (inc cursor-pos)}}}))})

(intent/register-intent! :merge-with-prev
                         {:doc "Merge block with previous sibling, placing cursor at merge point.

                                CRITICAL: Re-parents children of deleted block to prev block (Logseq behavior)."

                          :fr/ids #{:fr.edit/backspace-merge}

                          :spec [:map [:type [:= :merge-with-prev]] [:block-id :string]]
                          :handler (fn [db _session {:keys [block-id]}]
                                     (let [prev-id (get-in db [:derived :prev-id-of block-id])
                                           prev-text (get-block-text db prev-id)
                                           curr-text (get-block-text db block-id)
                                           merged-text (str prev-text curr-text)
                    ;; LOGSEQ PARITY: Use string length (UTF-16 code units) for cursor positioning
                                           cursor-at #?(:cljs (.-length prev-text)
                                                       :clj (count prev-text))
                    ;; CRITICAL FIX: Get children of block being deleted so they can be re-parented
                                           curr-children (get-in db [:children-by-parent block-id] [])]
                                       (when prev-id
                                         {:ops (vec (concat
                                                     ;; Update prev block with merged text
                                                     [{:op :update-node :id prev-id :props {:text merged-text}}]
                                                     ;; Re-parent children of deleted block to prev block
                                                     (mapv (fn [child-id]
                                                             {:op :place :id child-id :under prev-id :at :last})
                                                           curr-children)
                                                     ;; Move current block to trash
                                                     [{:op :place :id block-id :under const/root-trash :at :last}]))
                                          :session-updates {:ui {:editing-block-id prev-id
                                                                 :cursor-position cursor-at}}})))})

(intent/register-intent! :split-at-cursor
                         {:doc "Split block at cursor position into two blocks."
                          :spec [:map [:type [:= :split-at-cursor]] [:block-id :string] [:cursor-pos :int]]
                          :handler (fn [db _session {:keys [block-id cursor-pos]}]
                                     (let [text (get-block-text db block-id)
                                           before (subs text 0 cursor-pos)
                                           after (subs text cursor-pos)
                                           parent (get-in db [:derived :parent-of block-id])
                                           new-id (str "block-" (random-uuid))]
                                       (when parent
                                         [{:op :update-node :id block-id :props {:text before}}
                                          {:op :create-node :id new-id :type :block :props {:text after}}
                                          {:op :place :id new-id :under parent :at {:after block-id}}])))})

(intent/register-intent! :delete-forward
                         {:doc "Handle Delete key (forward delete).

         Behaviors:
         - Has text selection → Delete selection (handled by component)
         - At end of block → Merge with next block (child-first, then sibling)
         - Middle of text → Delete next character

         Merge priority:
         1. First child (if exists)
         2. Next sibling (if no children)
         3. No-op (if neither exists)"
                          :spec [:map
                                 [:type [:= :delete-forward]]
                                 [:block-id :string]
                                 [:cursor-pos :int]
                                 [:has-selection? :boolean]]
                          :handler (fn [db _session {:keys [block-id cursor-pos has-selection?]}]
                                     (let [text (get-block-text db block-id)
                                           at-end? (= cursor-pos (count text))]
                                       (cond
                                         ;; Has selection - component handles this
                                         has-selection?
                                         nil

                                         ;; At end - merge with next (child-first priority)
                                         at-end?
                                         (let [first-child (first (get-in db [:children-by-parent block-id]))
                                               next-sibling (get-in db [:derived :next-id-of block-id])
                                               target-id (or first-child next-sibling)]
                                           (when target-id
                                             (let [target-text (get-block-text db target-id)
                                                   merged-text (str text target-text)
                                                   target-children (get-in db [:children-by-parent target-id] [])]
                                               {:ops (vec (concat
                                                           [{:op :update-node :id block-id :props {:text merged-text}}]
                                                           (map (fn [child-id]
                                                                  {:op :place :id child-id :under block-id :at :last})
                                                                target-children)
                                                           [{:op :place :id target-id :under const/root-trash :at :last}]))
                                                :session-updates {:ui {:editing-block-id block-id
                                                                       :cursor-position (count text)}}})))

                                         ;; Middle of text - delete next character
                                         :else
                                         (let [next-char-len (text/grapheme-length-at text cursor-pos)
                                               new-text (str (subs text 0 cursor-pos)
                                                            (subs text (+ cursor-pos next-char-len)))]
                                           {:ops [{:op :update-node :id block-id :props {:text new-text}}]
                                            :session-updates {:ui {:editing-block-id block-id
                                                                   :cursor-position cursor-pos}}}))))})

;; ── Word Navigation Intents ──────────────────────────────────────────────────

(intent/register-intent! :move-cursor-forward-word
  {:doc "Move cursor to start of next word (Alt+F / Ctrl+Shift+F on Mac).

         Uses word boundary detection (stops at spaces/newlines)."
   :spec [:map
          [:type [:= :move-cursor-forward-word]]
          [:block-id :string]]
   :handler (fn [db session {:keys [block-id]}]
              (let [block-text (get-block-text db block-id)
                    cursor-pos (get-in session [:ui :cursor-position])
                    next-pos (text/find-next-word-boundary block-text (or cursor-pos 0))]
                {:session-updates {:ui {:cursor-position next-pos}}}))})

(intent/register-intent! :move-cursor-backward-word
  {:doc "Move cursor to start of previous word (Alt+B / Ctrl+Shift+B on Mac)."
   :spec [:map
          [:type [:= :move-cursor-backward-word]]
          [:block-id :string]]
   :handler (fn [db session {:keys [block-id]}]
              (let [block-text (get-block-text db block-id)
                    cursor-pos (get-in session [:ui :cursor-position])
                    prev-pos (text/find-prev-word-boundary block-text (or cursor-pos 0))]
                (when prev-pos
                  {:session-updates {:ui {:cursor-position prev-pos}}})))})

;; ── Kill Commands (Emacs-style) ──────────────────────────────────────────────

(intent/register-intent! :clear-block-content
  {:doc "Clear entire block content (Cmd+L).

         Sets text to empty string, cursor to position 0."
   :spec [:map
          [:type [:= :clear-block-content]]
          [:block-id :string]]
   :handler (fn [_db _session {:keys [block-id]}]
              {:ops [{:op :update-node :id block-id :props {:text ""}}]
               :session-updates {:ui {:cursor-position 0}}})})

(intent/register-intent! :kill-to-beginning
  {:doc "Kill from cursor to beginning of block (Cmd+U).

         Deletes text before cursor, copies to clipboard."
   :spec [:map
          [:type [:= :kill-to-beginning]]
          [:block-id :string]]
   :handler (fn [db session {:keys [block-id]}]
              (let [block-text (get-block-text db block-id)
                    cursor-pos (get-in session [:ui :cursor-position] 0)
                    killed-text (subs block-text 0 cursor-pos)
                    new-text (subs block-text cursor-pos)]
                ;; TODO: Copy killed-text to clipboard (requires browser API or MCP)
                #?(:cljs (js/console.log "Killed:" killed-text))
                {:ops [{:op :update-node :id block-id :props {:text new-text}}]
                 :session-updates {:ui {:cursor-position 0}}}))})

(intent/register-intent! :kill-to-end
  {:doc "Kill from cursor to end of block (Cmd+K).

         Deletes text after cursor, copies to clipboard."
   :spec [:map
          [:type [:= :kill-to-end]]
          [:block-id :string]]
   :handler (fn [db session {:keys [block-id]}]
              (let [block-text (get-block-text db block-id)
                    cursor-pos (get-in session [:ui :cursor-position] 0)
                    killed-text (subs block-text cursor-pos)
                    new-text (subs block-text 0 cursor-pos)]
                #?(:cljs (js/console.log "Killed:" killed-text))
                [{:op :update-node :id block-id :props {:text new-text}}]))})

(intent/register-intent! :kill-word-forward
  {:doc "Kill next word (Cmd+Delete).

         Deletes from cursor to next word boundary, copies to clipboard."
   :spec [:map
          [:type [:= :kill-word-forward]]
          [:block-id :string]]
   :handler (fn [db session {:keys [block-id]}]
              (let [block-text (get-block-text db block-id)
                    cursor-pos (get-in session [:ui :cursor-position] 0)
                    next-pos (text/find-next-word-boundary block-text cursor-pos)
                    killed-text (subs block-text cursor-pos next-pos)
                    new-text (str (subs block-text 0 cursor-pos)
                                 (subs block-text next-pos))]
                #?(:cljs (js/console.log "Killed:" killed-text))
                [{:op :update-node :id block-id :props {:text new-text}}]))})

(intent/register-intent! :kill-word-backward
  {:doc "Kill previous word (Alt+Delete / Option+Delete on Mac).

         Deletes from cursor back to previous word boundary, copies to clipboard."
   :spec [:map
          [:type [:= :kill-word-backward]]
          [:block-id :string]]
   :handler (fn [db session {:keys [block-id]}]
              (let [block-text (get-block-text db block-id)
                    cursor-pos (get-in session [:ui :cursor-position] 0)
                    prev-pos (text/find-prev-word-boundary block-text cursor-pos)]
                (when prev-pos
                  (let [killed-text (subs block-text prev-pos cursor-pos)
                        new-text (str (subs block-text 0 prev-pos)
                                     (subs block-text cursor-pos))]
                    #?(:cljs (js/console.log "Killed:" killed-text))
                    {:ops [{:op :update-node :id block-id :props {:text new-text}}]
                     :session-updates {:ui {:cursor-position prev-pos}}}))))})
