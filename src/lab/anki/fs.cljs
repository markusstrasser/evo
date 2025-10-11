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

(defn load-all-md-files
  "Recursively load all .md files from directory and subdirectories"
  ([dir-handle] (load-all-md-files dir-handle ""))
  ([dir-handle path-prefix]
   (p/let [entries (list-entries dir-handle)
           results (p/all
                    (for [entry entries]
                      (let [name (.-name entry)
                            kind (.-kind entry)]
                        (cond
                          (and (= kind "file") (.endsWith name ".md"))
                          (p/let [content (get-file-content entry)]
                            {:deck (if (empty? path-prefix) "default" path-prefix)
                             :filename name
                             :content content})

                          (= kind "directory")
                          (p/let [subdir-handle entry
                                  subpath (if (empty? path-prefix)
                                            name
                                            (str path-prefix "/" name))]
                            (load-all-md-files subdir-handle subpath))

                          :else
                          (p/resolved nil)))))]
     (p/resolved (->> results
                      flatten
                      (remove nil?)
                      vec)))))

(defn load-cards
  "Load cards from all .md files recursively"
  [dir-handle]
  (p/let [md-files (load-all-md-files dir-handle)]
    (->> md-files
         (map (fn [{:keys [content deck filename]}]
                (str ";; Deck: " deck " | File: " filename "\n" content)))
         (str/join "\n\n"))))

(defn save-cards
  "Save cards to cards.md"
  [dir-handle content]
  (write-markdown-file dir-handle "cards.md" content))

;; Directory handle persistence (IndexedDB)

(defn save-dir-handle
  "Save directory handle to IndexedDB for next session"
  [handle]
  (p/let [db-name "anki-storage"
          store-name "file-handles"
          request (.open js/indexedDB db-name 1)]
    (set! (.-onupgradeneeded request)
          (fn [e]
            (let [db (-> e .-target .-result)]
              (when-not (.contains (.-objectStoreNames db) store-name)
                (.createObjectStore db store-name)))))

    (p/create
     (fn [resolve reject]
       (set! (.-onsuccess request)
             (fn [e]
               (let [db (-> e .-target .-result)
                     tx (.transaction db #js [store-name] "readwrite")
                     store (.objectStore tx store-name)
                     put-req (.put store handle "dir-handle")]
                 (set! (.-onsuccess put-req) #(resolve true))
                 (set! (.-onerror put-req) #(reject (.-error put-req))))))
       (set! (.-onerror request) #(reject (.-error request)))))))

(defn load-dir-handle
  "Load directory handle from IndexedDB"
  []
  (p/let [db-name "anki-storage"
          store-name "file-handles"
          request (.open js/indexedDB db-name 1)]
    (p/create
     (fn [resolve reject]
       (set! (.-onsuccess request)
             (fn [e]
               (let [db (-> e .-target .-result)]
                 (if (.contains (.-objectStoreNames db) store-name)
                   (let [tx (.transaction db #js [store-name] "readonly")
                         store (.objectStore tx store-name)
                         get-req (.get store "dir-handle")]
                     (set! (.-onsuccess get-req)
                           (fn [e]
                             (let [result (-> e .-target .-result)]
                               (resolve result))))
                     (set! (.-onerror get-req) #(resolve nil)))
                   (resolve nil)))))
       (set! (.-onerror request) #(resolve nil))))))

(defn has-saved-dir?
  "Check if we have a saved directory handle"
  []
  (p/let [handle (load-dir-handle)]
    (some? handle)))
