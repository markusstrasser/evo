(ns kernel.constants
  "Centralized constants for the kernel layer.")

;; Root node keywords
(def roots
  "Top-level root keywords in the DB."
  [:doc :trash :session])

(def root-doc :doc)
(def root-trash :trash)
(def root-session :session)

;; Session child node IDs
(def session-selection-id "session/selection")
(def session-ui-id "session/ui")
