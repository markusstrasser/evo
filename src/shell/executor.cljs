(ns shell.executor
  "Shared runtime helpers for intent dispatch.

   Extracted from the old shell dispatch surfaces so intent application logic
   lives in one place."
  (:require [kernel.api :as api]
            [kernel.db :as db]
            [shell.log :as slog]
            [shell.view-state :as vs]
            [shell.storage :as storage]
            [shell.url-sync :as url-sync]
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

;; ── Pure Helper Functions ─────────────────────────────────────────────────────

(defn- prepare-intent-with-buffer
  "Inject pending buffer text into intent for single-transaction commit.

   Handlers can check :pending-buffer and emit :update-content ops if needed.

   Returns: [intent-with-buffer editing-block-id buffer-text]"
  [intent-map]
  (let [editing-block-id (vs/editing-block-id)
        buffer-text (when editing-block-id (vs/buffer-text editing-block-id))
        intent-with-buffer (if buffer-text
                             (assoc intent-map
                                    :pending-buffer {:block-id editing-block-id
                                                     :text buffer-text})
                             intent-map)]
    [intent-with-buffer editing-block-id buffer-text]))

(defn- clipboard-op-type
  "Determine clipboard operation type from intent type.

   Returns: :copy | :cut | :kill | :unknown"
  [intent-type]
  (cond
    (#{:copy-block :copy-selected} intent-type) :copy
    (#{:cut-block :cut-selected} intent-type) :cut
    (#{:kill-to-end :kill-to-beginning
       :kill-word-forward :kill-word-backward} intent-type) :kill
    :else :unknown))

;; ── Side-Effect Helpers ───────────────────────────────────────────────────────

(defn- handle-clipboard-copy!
  "Write clipboard text to system clipboard and record for debugging.

   Extracts block IDs from clipboard-blocks and determines operation type."
  [session-updates intent-type]
  (when-let [clipboard-text (get-in session-updates [:ui :clipboard-text])]
    (write-to-clipboard! clipboard-text)
    (let [clipboard-blocks (get-in session-updates [:ui :clipboard-blocks])
          block-ids (mapv :id clipboard-blocks)
          op-type (clipboard-op-type intent-type)]
      (dev/record-clipboard-op! op-type clipboard-text block-ids))))

(defn- update-navigation-history!
  "Track page changes and journals view for browser-style back/forward navigation.

   Handles three cases:
   1. Page change: Update history, recents, queue auto-trash
   2. Journals view: Track as virtual :journals page
   3. Navigate back/forward: Skip (those modify history-index directly)"
  [session-updates intent-type old-page]
  (let [new-page (get-in session-updates [:ui :current-page])
        journals-view? (true? (get-in session-updates [:ui :journals-view?]))
        skip-history? (#{:navigate-back :navigate-forward} intent-type)]

    ;; Track page changes
    (when (and new-page (not skip-history?))
      (vs/push-history! new-page old-page)
      (vs/add-to-recents! new-page)
      (when (and old-page (not= old-page new-page))
        (vs/queue-auto-trash-check! old-page)))

    ;; Track journals view as virtual page
    (when (and journals-view? (not skip-history?))
      (vs/push-history! :journals old-page))))

(defn- handle-storage-cleanup!
  "Delete old markdown file when page is renamed.

   Prevents duplicate pages from old .md files being reloaded on next session."
  [session-updates]
  (when-let [old-title (get-in session-updates [:storage :delete-old-file])]
    (storage/delete-page-file! nil old-title)))

(defn- sync-url-for-page!
  "Update browser URL when page changes.

   Syncs current page title to ?page= query param for deep linking.
   Skips for back/forward navigation (popstate handles those)."
  [db session-updates intent-type]
  (let [new-page (get-in session-updates [:ui :current-page])
        skip-sync? (#{:navigate-back :navigate-forward :url-navigate} intent-type)]
    (when (and new-page (not skip-sync?))
      (url-sync/sync-url-to-page! db new-page))))

;; ── Main Intent Application ───────────────────────────────────────────────────

(defn apply-intent!
  "Apply an intent to DB + session, with logging and validation.

   This is the canonical intent application path. Steps:
   1. Sync cursor-pos from intent to session (for undo/redo)
   2. Inject pending buffer into intent
   3. Call api/dispatch with current session
   4. Report validation issues
   5. Apply session-updates (BEFORE db, for on-mount hooks)
   6. Update navigation history
   7. Handle clipboard operations
   8. Clean up storage
   9. Reset db (triggers re-render)
   10. Clear buffer
   11. Assert derived indexes are fresh
   12. Log to devtools

   Args:
     !db        - DB atom
     intent-map - The intent to dispatch
     label      - Debug label for assert (e.g. \"DIRECT\" or \"KEYBOARD\")"
  [!db intent-map label]
  ;; UNDO/REDO: Capture cursor position from intent before dispatch
  (when-let [cursor-pos (:cursor-pos intent-map)]
    (vs/set-cursor-position! cursor-pos))

  ;; BUFFER INJECTION: Attach pending buffer text to intent
  (let [[intent-with-buffer editing-block-id buffer-text] (prepare-intent-with-buffer intent-map)
        intent-type (:type intent-map)
        current-session (vs/get-view-state)
        db-before @!db
        {:keys [db ops issues session-updates]} (api/dispatch db-before current-session intent-with-buffer)
        db-after db
        should-log? (not (contains? no-log-intents intent-type))]

    ;; Report validation issues
    (when (seq issues)
      (js/console.error "Intent validation failed:" (pr-str issues)))

    ;; Append the transaction to the op log for structural intents.
    ;; Ephemeral intents (session-updates only, no ops) don't touch the log.
    (when (seq ops)
      (slog/append-and-advance! intent-map ops current-session))

    ;; Capture old page BEFORE applying session updates
    ;; (so push-history! can seed it into history if needed)
    (let [old-page (vs/current-page)]

      ;; CRITICAL: Apply session updates BEFORE DB changes!
      ;; The DB reset triggers Replicant re-render, which fires on-mount hooks.
      ;; Those hooks read session state (cursor-position), so session must be updated first.
      (when session-updates
        (vs/merge-view-state-updates! session-updates))

      ;; Update navigation history and recents
      (update-navigation-history! session-updates intent-type old-page)

      ;; Sync URL to reflect current page (for deep linking)
      (sync-url-for-page! db-after session-updates intent-type))

    ;; Handle clipboard operations
    (handle-clipboard-copy! session-updates intent-type)

    ;; Clean up storage (delete renamed page files)
    (handle-storage-cleanup! session-updates)

    ;; Apply DB changes ONLY IF changed (triggers re-render + debounced save)
    ;; Skip reset when db unchanged to avoid spurious watcher notifications
    (when-not (identical? db-before db-after)
      (reset! !db db-after))

    ;; Clear buffer after successful dispatch (if buffer was injected and used)
    (when buffer-text
      (vs/buffer-clear! editing-block-id))

    ;; Assert derived indexes are fresh
    (assert-derived-fresh! db-after (str "after " label " dispatch: " intent-type))

    ;; Log to devtools
    (when should-log?
      (dev/log-dispatch! intent-map db-before db-after))))
