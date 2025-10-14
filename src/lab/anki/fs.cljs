(ns lab.anki.fs
  "File System Access API operations"
  (:require [promesa.core :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

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

;; Generic file operations with optional transformation

(defn- read-file
  "Read a file and optionally transform its content"
  ([dir-handle filename]
   (read-file dir-handle filename identity))
  ([dir-handle filename parse-fn]
   (p/let [fh (get-file-handle dir-handle filename false)
           content (get-file-content fh)]
     (parse-fn content))))

(defn- write-file
  "Write content to a file, optionally transforming before writing"
  ([dir-handle filename content]
   (write-file dir-handle filename content identity))
  ([dir-handle filename data serialize-fn]
   (p/let [fh (get-file-handle dir-handle filename true)
           content (serialize-fn data)]
     (write-file-content fh content))))

;; EDN file operations

(defn read-edn-file
  "Read and parse an EDN file"
  [dir-handle filename]
  (read-file dir-handle filename edn/read-string))

(defn write-edn-file
  "Write data to an EDN file"
  [dir-handle filename data]
  (write-file dir-handle filename data pr-str))

;; Markdown file operations

(defn read-markdown-file
  "Read a markdown file"
  [dir-handle filename]
  (read-file dir-handle filename))

(defn write-markdown-file
  "Write content to a markdown file"
  [dir-handle filename content]
  (write-file dir-handle filename content))

;; SRS-specific file operations

(defn load-log
  "Load the event log from log.edn, returning empty vector if file doesn't exist"
  [dir-handle]
  (p/catch
   (read-edn-file dir-handle "log.edn")
   (fn [e]
     (js/console.log "No existing log file, starting fresh")
     [])))

(defn append-to-log
  "Append events to the log file"
  [dir-handle events]
  (p/let [curr-log (load-log dir-handle)
          combined-log (into curr-log events)]
    (write-edn-file dir-handle "log.edn" combined-log)))

(defn get-subdir
  "Get subdirectory handle"
  [dir-handle dirname]
  (p/catch
   (.getDirectoryHandle dir-handle dirname)
   (fn [_e] nil)))

(defn list-entries
  "List all entries (files/dirs) in directory"
  [dir-handle]
  (p/let [entries (js/Array.from (.values dir-handle))]
    (js->clj entries :keywordize-keys false)))

;; Async iteration helpers

(defn- collect-async-iterator
  "Collect all values from an async iterator into a vector"
  [iterator]
  (p/loop [acc []]
    (p/let [item (.next iterator)]
      (if (.-done item)
        acc
        (p/recur (conj acc (.-value item)))))))

(defn- process-md-entry
  "Process a single directory entry (file or subdirectory)"
  [entry path-prefix]
  (let [name (.-name entry)
        kind (.-kind entry)]
    (js/console.log "Processing entry:" name "kind:" kind)
    (cond
      (and (= kind "file") (.endsWith name ".md"))
      (p/let [content (get-file-content entry)]
        (js/console.log "Loaded .md file:" name)
        [{:deck (if (seq path-prefix) path-prefix "default")
          :filename name
          :content content}])

      (= kind "directory")
      (let [subpath (if (seq path-prefix)
                      (str path-prefix "/" name)
                      name)]
        (load-all-md-files entry subpath))

      :else
      [])))

(defn load-all-md-files
  "Recursively load all .md files from directory and subdirectories"
  ([dir-handle] (load-all-md-files dir-handle ""))
  ([dir-handle path-prefix]
   (p/let [entries (collect-async-iterator (.values dir-handle))
           _ (js/console.log "Found" (count entries) "entries")
           results (p/all (map #(process-md-entry % path-prefix) entries))]
     (into [] cat results))))

(defn load-cards
  "Load cards from all .md files recursively, returning structured data"
  [dir-handle]
  (p/let [md-files (load-all-md-files dir-handle)]
    ;; Return structured data with deck info
    md-files))

(defn save-cards
  "Save cards to cards.md"
  [dir-handle content]
  (write-markdown-file dir-handle "cards.md" content))

(defn append-occlusion-cards
  "Append occlusion cards to Occlusions.md (creates file if doesn't exist)"
  [dir-handle cards]
  (p/let [;; Try to read existing content
          existing-content (p/catch
                            (read-markdown-file dir-handle "Occlusions.md")
                            (fn [_e] ""))
          ;; Format cards as EDN blocks separated by blank lines
          card-strings (mapv pr-str cards)
          new-content (str/join "\n\n" card-strings)
          ;; Combine with existing (add blank line separator if needed)
          combined (if (seq existing-content)
                     (str existing-content "\n\n" new-content)
                     new-content)]
    (write-markdown-file dir-handle "Occlusions.md" combined)))

(defn append-card-text
  "Append card text to a specific .md file (creates file if doesn't exist)"
  [dir-handle filename card-text]
  (p/let [;; Try to read existing content
          existing-content (p/catch
                            (read-markdown-file dir-handle filename)
                            (fn [_e] ""))
          ;; Combine with existing (add blank line separator if needed)
          combined (if (seq existing-content)
                     (str existing-content "\n\n" card-text)
                     card-text)]
    (write-markdown-file dir-handle filename combined)))

(defn list-md-files
  "List all .md files in directory (non-recursive, excludes Occlusions.md and log.edn)"
  [dir-handle]
  (p/let [entries (collect-async-iterator (.values dir-handle))]
    (->> entries
         (filter #(and (= "file" (.-kind %))
                      (.endsWith (.-name %) ".md")
                      (not= "Occlusions.md" (.-name %))))
         (map #(.-name %))
         (into []))))

;; Directory handle persistence using IndexedDB
;; FileSystemHandle can be stored directly in IndexedDB (not localStorage)

(defonce db-name "anki-db")
(defonce store-name "handles")
(defonce db-version 1)

(defn- idb-request->promise
  "Convert an IndexedDB request into a promise"
  [request]
  (p/create
   (fn [resolve reject]
     (set! (.-onsuccess request)
           (fn [e] (resolve (-> e .-target .-result))))
     (set! (.-onerror request)
           (fn [e] (reject (-> e .-target .-error)))))))

(defn- open-db
  "Open IndexedDB with proper upgrade handling"
  []
  (let [request (.open js/indexedDB db-name db-version)]
    (set! (.-onupgradeneeded request)
          (fn [e]
            (js/console.log "Upgrading IndexedDB...")
            (let [db (-> e .-target .-result)]
              (when-not (.contains (.-objectStoreNames db) store-name)
                (js/console.log "Creating object store:" store-name)
                (.createObjectStore db store-name)))))
    (p/then (idb-request->promise request)
            (fn [db]
              (js/console.log "IndexedDB opened successfully")
              db))))

(defn- idb-transaction
  "Execute an IndexedDB operation within a transaction"
  [mode operation-fn]
  (p/let [db (open-db)
          tx (.transaction db #js [store-name] mode)
          store (.objectStore tx store-name)]
    (operation-fn store)))

(defn save-dir-handle
  "Save directory handle to IndexedDB"
  [handle]
  (js/console.log "Saving directory handle...")
  (p/let [_ (idb-transaction "readwrite"
                             #(idb-request->promise (.put % handle "dir-handle")))]
    (js/console.log "Directory handle saved")
    true))

(defn load-dir-handle
  "Load directory handle from IndexedDB"
  []
  (js/console.log "Loading directory handle...")
  (p/catch
   (p/let [result (idb-transaction "readonly"
                                   #(idb-request->promise (.get % "dir-handle")))]
     (when result
       (js/console.log "Directory handle found"))
     (or result
         (do (js/console.log "No saved directory handle")
             nil)))
   (fn [e]
     (js/console.error "Failed to load handle:" e)
     nil)))
