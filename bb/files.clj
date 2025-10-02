(ns files
  "File I/O utilities for Clojure/Babashka scripts."
  (:require [babashka.fs :as fs]))

(defn exists?
  "Check if file or directory exists."
  [path]
  (fs/exists? path))

(defn mkdir-p
  "Create directory and all parents. Returns path."
  [path]
  (fs/create-dirs path)
  path)

(defn read-file
  "Read file as string. Returns nil if missing and :ignore-missing? true."
  [path & [{:keys [ignore-missing?]}]]
  (if (and ignore-missing? (not (exists? path)))
    nil
    (slurp (str path))))

(defn write-file
  "Write string to file, creating parent dirs. Returns path."
  [path content]
  (mkdir-p (fs/parent path))
  (spit (str path) content)
  path)

(defn list-files
  "List files in directory. Pass {:glob \"*.clj\"} for pattern matching."
  [dir & [{:keys [glob]}]]
  (if glob
    (fs/glob dir glob)
    (fs/list-dir dir)))

(defn file-size
  "Get file size in bytes. Returns nil if file doesn't exist."
  [path]
  (when (exists? path)
    (fs/size path)))

(defn file-modified
  "Get file modification time as Instant. Returns nil if missing."
  [path]
  (when (exists? path)
    (fs/file-time->instant (fs/last-modified-time path))))
