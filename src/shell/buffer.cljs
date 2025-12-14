(ns shell.buffer
  "Unified buffer API for high-velocity text storage during editing.

   Architecture:
   - Buffer is separate from view-state to avoid re-renders on every keystroke
   - Browser owns contenteditable DOM during editing; buffer is a sync copy
   - Buffer is committed to DB on blur, Enter, or other finalizing actions

   Buffer Lifecycle:
   1. User enters edit mode on block
   2. Keystrokes update buffer via (set! block-id text)
   3. Intent handlers read buffer via (text block-id)
   4. On blur/finalize: buffer committed to DB, then (clear! block-id)

   Usage:
     (require '[shell.buffer :as buffer])

     ;; During editing (component keystroke handler)
     (buffer/set! block-id new-text)

     ;; In intent handler (read current text)
     (let [current (buffer/text block-id)]
       ...)

     ;; After commit to DB
     (buffer/clear! block-id)

   Why not in kernel?
   - Buffer is ephemeral UI state, not persistent document state
   - High-velocity (every keystroke), not transactional
   - Lives in shell layer, not kernel layer"
  (:require [shell.view-state :as vs]))

;; ── Buffer API ───────────────────────────────────────────────────────────────

(defn text
  "Get buffer text for a block.

   Returns the current buffer content for block-id, or nil if not set.
   This is the text the user has typed but not yet committed to DB.

   Example:
     (buffer/text \"block-123\")
     ;=> \"current typing text...\""
  [block-id]
  (vs/buffer-text block-id))

(defn set!
  "Store text in buffer for a block.

   Called on every keystroke during editing. Does NOT trigger re-render.
   Browser owns the contenteditable DOM; this is just a sync copy.

   Example:
     (buffer/set! \"block-123\" \"Hello world\")"
  [block-id content]
  (vs/buffer-set! block-id content))

(defn clear!
  "Clear buffer for a block (or all blocks if no id given).

   Call after committing text to DB to prevent stale buffer data.

   Example:
     (buffer/clear! \"block-123\")  ; clear one
     (buffer/clear!)               ; clear all"
  ([]
   (vs/buffer-clear!))
  ([block-id]
   (vs/buffer-clear! block-id)))

(defn has-pending?
  "Check if block has uncommitted buffer text.

   Useful for handlers that need to know if buffer should be committed
   before performing operations that might lose the text.

   Example:
     (when (buffer/has-pending? block-id)
       ;; commit buffer first
       ...)"
  [block-id]
  (some? (text block-id)))

(defn pending-blocks
  "Get set of block IDs with uncommitted buffer text.

   Useful for debugging or ensuring all buffers are committed.

   Example:
     (buffer/pending-blocks)
     ;=> #{\"block-123\" \"block-456\"}"
  []
  (set (keys @vs/!buffer)))
