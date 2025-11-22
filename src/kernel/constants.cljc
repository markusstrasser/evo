(ns kernel.constants
  "Centralized constants for the kernel layer.")

;; Root node keywords
(def roots
  "Top-level root keywords in the DB.

   Phases 4 & 5: :session removed - session state lives in shell.session atom."
  [:doc :trash])  ; Phases 4 & 5: :session removed

(def root-doc :doc)
(def root-trash :trash)

;; Phases 4 & 5: Session constants kept for backward compat during migration
;; These IDs no longer exist in DB but may be referenced in old code
(def root-session :session)  ; OBSOLETE - kept for compatibility
(def session-selection-id "session/selection")  ; OBSOLETE
(def session-ui-id "session/ui")  ; OBSOLETE
