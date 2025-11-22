(ns plugins.buffer
  "Ephemeral buffer for high-velocity text input during editing.

   ARCHITECTURE: Uncontrolled editing pattern
   - Browser owns text state during edit mode
   - Input events stream to buffer (no history, no indexing)
   - Blur commits buffer → canonical DB
   - Enables reactive overlays (slash menu) without DOM interference"
  (:require [kernel.intent :as intent]))

;; ── Intent Handler ────────────────────────────────────────────────────────────

(intent/register-intent! :buffer/update
  {:doc "Update ephemeral buffer during editing. No history, no re-indexing."
   :spec [:map
          [:type [:= :buffer/update]]
          [:block-id :string]
          [:text :string]]
   :handler (fn [db {:keys [block-id text]}]
              ;; Simple assoc-in - no transaction overhead
              ;; This is explicitly NOT a canonical operation
              [{:op :update-node
                :id "session/buffer"
                :props {(keyword block-id) text}}])})
