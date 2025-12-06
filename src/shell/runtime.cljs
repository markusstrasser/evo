(ns shell.runtime
  "Shared runtime helpers for intent dispatch.

   Extracted from nexus.cljs and blocks_ui.cljs to eliminate duplication.
   Both files had near-identical intent application logic."
  (:require [kernel.api :as api]
            [kernel.db :as db]
            [shell.session :as session]
            [dev.tooling :as dev]))

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
   5. Reset db (triggers re-render)
   6. Assert derived indexes are fresh
   7. Log to devtools
   
   Args:
     !db        - DB atom
     intent-map - The intent to dispatch
     label      - Debug label for assert (e.g. \"NEXUS\" or \"DIRECT\")"
  [!db intent-map label]
  ;; UNDO/REDO FIX: Capture cursor position from intent before dispatch
  (when-let [cursor-pos (:cursor-pos intent-map)]
    (session/set-cursor-position! cursor-pos))

  ;; BUFFER INJECTION: Attach pending buffer text to intent for single-transaction commit
  ;; Handlers can check :pending-buffer and emit :update-content ops if needed
  (let [editing-block-id (session/editing-block-id)
        buffer-text (when editing-block-id (session/buffer-text editing-block-id))
        intent-with-buffer (if buffer-text
                             (assoc intent-map
                                    :pending-buffer {:block-id editing-block-id
                                                     :text buffer-text})
                             intent-map)
        intent-type (:type intent-map)
        current-session (session/get-session)
        db-before @!db
        {:keys [db issues session-updates]} (api/dispatch db-before current-session intent-with-buffer)
        db-after db
        should-log? (not (contains? no-log-intents intent-type))]

    ;; Report validation issues
    (when (seq issues)
      (js/console.error "Intent validation failed:" (pr-str issues)))

    ;; CRITICAL: Apply session updates BEFORE DB changes!
    ;; The DB reset triggers Replicant re-render, which fires on-mount hooks.
    ;; Those hooks read session state (cursor-position), so session must be updated first.
    (when session-updates
      (session/merge-session-updates! session-updates))

    ;; Apply DB changes (triggers re-render)
    (reset! !db db-after)

    ;; Clear buffer after successful dispatch (if buffer was injected and used)
    (when buffer-text
      (session/buffer-clear! editing-block-id))

    ;; Assert derived indexes are fresh
    (assert-derived-fresh! db-after (str "after " label " dispatch: " intent-type))

    ;; Log to devtools
    (when should-log?
      (dev/log-dispatch! intent-map db-before db-after))))
