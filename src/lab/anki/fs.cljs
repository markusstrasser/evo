(ns lab.anki.fs
  "File System Access API operations"
  (:require [promesa.core :as p]
            [clojure.edn :as edn]))

;; File System Access API wrappers

(defn pick-directory
  "Show directory picker and return directory handle"
  []
  (.showDirectoryPicker js/window))

(defn get-file-handle
  "Get or create a file handle in the directory"
  [dir-handle filename create?]
  (.getFileHandle dir-handle filename #js {:create create?}))

(defn get-file-content
  "Read file content as text"
  [file-handle]
  (p/let [f (.getFile file-handle)]
    (.text f)))

(defn write-file-content
  "Write text content to file"
  [file-handle content]
  (p/let [w (.createWritable file-handle)]
    (p/do!
      (.write w content)
      (.close w))))

;; EDN file operations

(defn read-edn-file
  "Read and parse an EDN file"
  [dir-handle filename]
  (p/let [fh (get-file-handle dir-handle filename false)
          cnt (get-file-content fh)]
    (edn/read-string cnt)))

(defn write-edn-file
  "Write data to an EDN file"
  [dir-handle filename data]
  (p/let [file-handle (get-file-handle dir-handle filename true)
          content (pr-str data)]
    (write-file-content file-handle content)))

;; Markdown file operations

(defn read-markdown-file
  "Read a markdown file"
  [dir-handle filename]
  (p/let [file-handle (get-file-handle dir-handle filename false)]
    (get-file-content file-handle)))

(defn write-markdown-file
  "Write content to a markdown file"
  [dir-handle filename content]
  (p/let [file-handle (get-file-handle dir-handle filename true)]
    (write-file-content file-handle content)))

;; SRS-specific file operations

(defn load-log
  "Load the event log from log.edn"
  [dir-handle]
  (p/catch
    (read-edn-file dir-handle "log.edn")
    (fn [_e]
      ;; If file doesn't exist, return empty log
      [])))

(defn append-to-log
  "Append events to the log file"
  [dir-handle events]
  (p/let [curr-log (load-log dir-handle)
          combined-log (into curr-log events)]
    (write-edn-file dir-handle "log.edn" combined-log)))

(defn load-cards
  "Load cards from cards.md"
  [dir-handle]
  (p/catch
    (read-markdown-file dir-handle "cards.md")
    (fn [_e]
      ;; If file doesn't exist, return empty string
      "")))

(defn save-cards
  "Save cards to cards.md"
  [dir-handle content]
  (write-markdown-file dir-handle "cards.md" content))

;; Directory handle persistence (localStorage)

(defn save-dir-handle
  "Save directory handle to localStorage for next session"
  [_handle]
  ;; Note: Can't actually serialize FileSystemHandle
  ;; Instead, we'll need to request permission again on reload
  ;; This is a limitation of the File System Access API
  (js/localStorage.setItem "anki-dir-requested" "true"))

(defn has-saved-dir?
  "Check if we've requested a directory before"
  []
  (some? (js/localStorage.getItem "anki-dir-requested")))
