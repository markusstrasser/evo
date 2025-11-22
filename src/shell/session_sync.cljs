(ns shell.session-sync
  "Session-DB synchronization layer for gradual migration.

   Phase 2-3 Migration Strategy:
   - Components READ from session (fast, direct)
   - Intents WRITE to both DB and session (dual-write)
   - Phase 4+ will remove DB writes entirely

   This namespace provides helpers to sync session state from DB
   after intent dispatch, maintaining backward compatibility during migration."
  (:require [shell.session :as session]
            [kernel.constants :as const]))

;; ── DB → Session Sync ─────────────────────────────────────────────────────────

(defn sync-ui-from-db!
  "Sync UI state from DB to session.

   Called after intent dispatch to keep session in sync with DB.

   Phase 2-3: Dual-write period - DB is source of truth
   Phase 4+: Session becomes source of truth, this fn removed"
  [db]
  (let [session-ui (get-in db [:nodes const/session-ui-id :props])
        session-sel (get-in db [:nodes const/session-selection-id :props])]

    ;; Sync UI state
    (when session-ui
      (session/swap-session! assoc :ui
                             {:folded (get session-ui :folded #{})
                              :zoom-root (get session-ui :zoom-root)
                              :current-page (get session-ui :current-page)
                              :editing-block-id (get session-ui :editing-block-id)
                              :cursor-position (get session-ui :cursor-position)
                              :slash-menu (get session-ui :slash-menu {:open? false :search "" :selected-idx 0})
                              :quick-switcher (get session-ui :quick-switcher {:open? false :query "" :selected-idx 0})}))

    ;; Sync selection state
    (when session-sel
      (session/swap-session! assoc :selection
                             {:nodes (get session-sel :nodes #{})
                              :focus (get session-sel :focus)
                              :anchor (get session-sel :anchor)}))))

(defn sync-buffer-from-db!
  "Sync buffer state from DB to session.

   Called after buffer update intents."
  [db]
  (let [buffer-node (get-in db [:nodes "session/buffer" :props])]
    (when buffer-node
      (session/swap-session! assoc :buffer buffer-node))))

(defn sync-all-from-db!
  "Sync all session state from DB.

   Called on app init and after any intent that might affect session state."
  [db]
  (sync-ui-from-db! db)
  (sync-buffer-from-db! db))

;; ── Session → DB Sync (for dual-write period) ────────────────────────────────

(defn session-to-ui-ops
  "Generate ops to sync session UI state to DB.

   Used during dual-write period (Phase 2-3).
   Phase 4+ will remove this entirely."
  []
  (let [session-state (session/get-session)
        ui-state (:ui session-state)]
    [{:op :update-node
      :id const/session-ui-id
      :props ui-state}]))

(defn session-to-selection-ops
  "Generate ops to sync session selection state to DB.

   Used during dual-write period (Phase 2-3).
   Phase 4+ will remove this entirely."
  []
  (let [session-state (session/get-session)
        sel-state (:selection session-state)]
    [{:op :update-node
      :id const/session-selection-id
      :props sel-state}]))

;; ── Init ──────────────────────────────────────────────────────────────────────

(defn init-session-from-db!
  "Initialize session state from DB on app startup.

   Only needed during Phase 2-3 migration.
   Phase 4+ will start with fresh session state."
  [db]
  (sync-all-from-db! db))
