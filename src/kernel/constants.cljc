(ns kernel.constants
  "Centralized constants for the kernel layer.")

;; Root node keywords
(def roots
  "Top-level root keywords in the DB. Session state lives in shell.session atom."
  [:doc :trash])

(def root-doc :doc)
(def root-trash :trash)
