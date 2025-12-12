(ns shell.executor
  "Shared runtime helpers for intent dispatch.

   Extracted from nexus.cljs and blocks_ui.cljs to eliminate duplication.
   Both files had near-identical intent application logic."
  (:require [kernel.api :as api]
            [kernel.db :as db]
            [shell.view-state :as vs]
            [dev.tooling :as dev]))

;; ── Clipboard API ─────────────────────────────────────────────────────────────

(defn- write-to-clipboard!
  "Write text to system clipboard using Clipboard API.
   Writes both text/plain for external apps and a custom MIME type for internal paste."
  [text]
  (when (and text js/navigator js/navigator.clipboard)
    (-> (js/navigator.clipboard.writeText text)
        (.then #(js/console.log "📋 Copied to clipboard:" (if (> (count text) 50)
                                                            (str (subs text 0 50) "...")
                                                            text)))
        (.catch #(js/console.error "Clipboard write failed:" %)))))

(defn assert-derived-fresh!
  "DEBUG: Assert that derived indexes are consistent after DB reset.
   
   Call after any DB mutation to catch corruption immediately."
  [db-val label]
  (when ^boolean goog.DEBUG
    (when-let [inconsistency (db/check-parent-of-consistency db-val)]
      (js/console.error "🚨🚨🚨 DERIVED INDEX CORRUPTION DETECTED 🚨🚨🚨"
                        "\nLabel:" label
                        "\nInconsistency:" (pr-str inconsistency)
                        "\nDB hash:" (hash db-val)
                        "\nchildren-by-parent keys:" (pr-str (keys (:children-by-parent db-val))))
      (js/console.trace "Stack trace for corruption detection"))))

(def ^:private no-log-intents
  "Intent types that shouldn't be logged to devtools."
  #{:inspect-dataspex :clear-log})

(defn apply-intent!
  "Apply an intent to DB + session, with logging and validation.
   
   This is the canonical intent application path. Steps:
   1. Sync cursor-pos from intent to session (for undo/redo)
   2. Call api/dispatch with current session
   3. Report validation issues
   4. Apply session-updates (BEFORE db, for on-mount hooks)
   5. Write to system clipboard if clipboard-text present
   6. Reset db (triggers re-render)
   7. Assert derived indexes are fresh
   8. Log to devtools
   
   Args:
     !db        - DB atom
     intent-map - The intent to dispatch
     label      - Debug label for assert (e.g. \"NEXUS\" or \"DIRECT\")"
  [!db intent-map label]
  ;; Debug logging for image upload tracing
  (when (= (:type intent-map) :update-content)
    (js/console.log "📷 apply-intent! received :update-content"
                    (clj->js {:blockId (:block-id intent-map)
                              :textLength (count (:text intent-map))
                              :textPreview (subs (:text intent-map) 0 (min 100 (count (:text intent-map))))})))

  ;; UNDO/REDO FIX: Capture cursor position from intent before dispatch
  (when-let [cursor-pos (:cursor-pos intent-map)]
    (vs/set-cursor-position! cursor-pos))

  ;; BUFFER INJECTION: Attach pending buffer text to intent for single-transaction commit
  ;; Handlers can check :pending-buffer and emit :update-content ops if needed
  (let [editing-block-id (vs/editing-block-id)
        buffer-text (when editing-block-id (vs/buffer-text editing-block-id))
        intent-with-buffer (if buffer-text
                             (assoc intent-map
                                    :pending-buffer {:block-id editing-block-id
                                                     :text buffer-text})
                             intent-map)
        intent-type (:type intent-map)
        current-session (vs/get-view-state)
        db-before @!db
        {:keys [db issues session-updates]} (api/dispatch db-before current-session intent-with-buffer)
        db-after db
        should-log? (not (contains? no-log-intents intent-type))]

    ;; Debug: Check if DB actually changed for update-content
    (when (= intent-type :update-content)
      (let [block-id (:block-id intent-map)
            text-before (get-in db-before [:nodes block-id :props :text])
            text-after (get-in db-after [:nodes block-id :props :text])]
        (js/console.log "📷 DB update check:"
                        (clj->js {:blockId block-id
                                  :textBefore text-before
                                  :textAfter text-after
                                  :changed? (not= text-before text-after)
                                  :issues issues}))))

    ;; Report validation issues
    (when (seq issues)
      (js/console.error "Intent validation failed:" (pr-str issues)))

    ;; CRITICAL: Apply session updates BEFORE DB changes!
    ;; The DB reset triggers Replicant re-render, which fires on-mount hooks.
    ;; Those hooks read session state (cursor-position), so session must be updated first.
    (when session-updates
      (vs/merge-view-state-updates! session-updates))

    ;; CLIPBOARD: Write to system clipboard if copy/cut operation set clipboard-text
    (when-let [clipboard-text (get-in session-updates [:ui :clipboard-text])]
      (write-to-clipboard! clipboard-text)
      ;; Record for debug API - extract block IDs from clipboard-blocks if available
      (let [clipboard-blocks (get-in session-updates [:ui :clipboard-blocks])
            block-ids (mapv :id clipboard-blocks)
            op-type (cond
                      (#{:copy-block :copy-selected} intent-type) :copy
                      (#{:cut-block :cut-selected} intent-type) :cut
                      (#{:kill-to-end :kill-to-beginning
                         :kill-word-forward :kill-word-backward} intent-type) :kill
                      :else :unknown)]
        (dev/record-clipboard-op! op-type clipboard-text block-ids)))

    ;; Apply DB changes (triggers re-render)
    (reset! !db db-after)

    ;; Clear buffer after successful dispatch (if buffer was injected and used)
    (when buffer-text
      (vs/buffer-clear! editing-block-id))

    ;; Assert derived indexes are fresh
    (assert-derived-fresh! db-after (str "after " label " dispatch: " intent-type))

    ;; Log to devtools
    (when should-log?
      (dev/log-dispatch! intent-map db-before db-after))))
