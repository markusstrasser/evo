(ns shell.storage
  "Filesystem storage layer using Logseq-style markdown format.

   Storage format:
   - User picks a folder via File System Access API
   - One .md file per page (e.g., projects.md, tasks.md)
   - Directory handle persisted to IndexedDB for reload

   Markdown format per page:
   ```
   title:: Page Title
   - Block text
     - Child block
       - Grandchild
   ```"
  (:require [clojure.string :as str]))

;; ── IndexedDB for directory handle persistence ──────────────────────────────

(def ^:private db-name "evo-storage")
(def ^:private db-version 1)
(def ^:private store-name "handles")

(defonce ^:private !dir-handle (atom nil))
(defonce ^:private !idb (atom nil))

(defn- open-idb []
  (js/Promise.
   (fn [on-resolve on-reject]
     (let [request (.open js/indexedDB db-name db-version)]
       (set! (.-onupgradeneeded request)
             (fn [e]
               (let [idb-conn (.-result (.-target e))]
                 (when-not (.contains (.-objectStoreNames idb-conn) store-name)
                   (.createObjectStore idb-conn store-name)))))
       (set! (.-onsuccess request)
             (fn [e]
               (reset! !idb (.-result (.-target e)))
               (on-resolve (.-result (.-target e)))))
       (set! (.-onerror request)
             (fn [e] (on-reject (.-error (.-target e)))))))))

(defn- save-handle-to-idb [handle]
  (when-let [idb-conn @!idb]
    (let [tx (.transaction idb-conn #js [store-name] "readwrite")
          store (.objectStore tx store-name)]
      (.put store handle "dir-handle"))))

(defn- load-handle-from-idb []
  (js/Promise.
   (fn [on-resolve on-reject]
     (if-let [idb-conn @!idb]
       (let [tx (.transaction idb-conn #js [store-name] "readonly")
             store (.objectStore tx store-name)
             request (.get store "dir-handle")]
         (set! (.-onsuccess request)
               (fn [_] (on-resolve (.-result request))))
         (set! (.-onerror request)
               (fn [e] (on-reject (.-error (.-target e))))))
       (on-resolve nil)))))

;; ── Markdown Serialization ───────────────────────────────────────────────────

(defn- block->markdown
  "Convert a single block and its children to markdown lines.
   Returns vector of lines with proper indentation."
  [db block-id depth]
  (let [text (get-in db [:nodes block-id :props :text] "")
        children (get-in db [:children-by-parent block-id] [])
        indent (apply str (repeat (* depth 2) " "))
        lines [(str indent "- " text)]]
    (into lines
          (mapcat #(block->markdown db % (inc depth)) children))))

(defn page->markdown
  "Serialize a page to Logseq-style markdown."
  [db page-id]
  (let [title (get-in db [:nodes page-id :props :title] "Untitled")
        children (get-in db [:children-by-parent page-id] [])]
    (str/join "\n"
              (into [(str "title:: " title)]
                    (mapcat #(block->markdown db % 0) children)))))

;; ── Markdown Parsing ─────────────────────────────────────────────────────────

(defn- parse-indent-level
  "Get indentation level from a line (number of leading 2-space groups)."
  [line]
  (let [spaces (count (take-while #(= % \space) line))]
    (quot spaces 2)))

(defn- strip-bullet
  "Remove bullet prefix '- ' from line content."
  [line]
  (let [trimmed (str/triml line)]
    (if (str/starts-with? trimmed "- ")
      (subs trimmed 2)
      trimmed)))

(defn- parse-title-line
  "Extract title from 'title:: Value' line."
  [line]
  (when (str/starts-with? line "title:: ")
    (subs line 8)))

(defn markdown->ops
  "Parse Logseq-style markdown into kernel ops."
  [page-id markdown]
  (let [lines (str/split-lines markdown)
        title-line (first lines)
        title (or (parse-title-line title-line) "Untitled")
        content-lines (if (parse-title-line title-line)
                        (rest lines)
                        lines)
        ops (atom [{:op :create-node :id page-id :type :page :props {:title title}}
                   {:op :place :id page-id :under :doc :at :last}])
        counter (atom 0)
        parent-stack (atom {0 page-id})]

    (doseq [line content-lines]
      (when (str/includes? line "- ")
        (let [depth (parse-indent-level line)
              text (strip-bullet line)
              block-id (str page-id "-b" (swap! counter inc))
              parent-id (or (get @parent-stack depth) page-id)]
          (swap! ops conj {:op :create-node :id block-id :type :block :props {:text text}})
          (swap! ops conj {:op :place :id block-id :under parent-id :at :last})
          (swap! parent-stack assoc (inc depth) block-id))))

    @ops))

;; ── File System Access API ───────────────────────────────────────────────────

(defn pick-folder!
  "Show folder picker and store the directory handle.
   Returns nil with console warning on unsupported browsers (Safari/Firefox)."
  []
  (if-not (exists? js/window.showDirectoryPicker)
    (do
      (js/console.warn "⚠️ File System Access API not supported in this browser. Use Chrome, Edge, or Brave.")
      (js/alert "Folder access requires Chrome, Edge, or Brave browser.\n\nSafari and Firefox don't support the File System Access API.")
      nil)
    (-> (.showDirectoryPicker js/window #js {:mode "readwrite"})
        (.then (fn [handle]
                 (reset! !dir-handle handle)
                 (save-handle-to-idb handle)
                 (js/console.log "📁 Folder selected:" (.-name handle))
                 handle))
        (.catch (fn [err]
                  (js/console.warn "Folder selection cancelled:" err)
                  nil)))))

(defn has-folder?
  "Check if a folder is currently selected."
  []
  (boolean @!dir-handle))

(defn- sanitize-filename
  "Convert page title to safe filename."
  [title]
  (-> (str/lower-case title)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn- write-file!
  "Write content to a file in the selected directory."
  [filename content]
  (when-let [dir @!dir-handle]
    (-> (.getFileHandle dir filename #js {:create true})
        (.then (fn [file-handle]
                 (.createWritable file-handle)))
        (.then (fn [writable]
                 (-> (.write writable content)
                     (.then #(.close writable)))))
        (.catch (fn [err]
                  (js/console.error "Failed to write file:" filename err))))))

(defn- read-file
  "Read content from a file in the selected directory."
  [filename]
  (when-let [dir @!dir-handle]
    (-> (.getFileHandle dir filename)
        (.then (fn [file-handle]
                 (.getFile file-handle)))
        (.then (fn [file]
                 (.text file)))
        (.catch (fn [_err]
                  nil))))) ; File doesn't exist

(defn- list-md-files
  "List all .md files in the selected directory."
  []
  (when-let [dir @!dir-handle]
    (js/Promise.
     (fn [on-resolve _on-reject]
       (let [files (atom [])]
         (-> (js/Promise.resolve)
             (.then
              (fn []
                (let [entries (.entries dir)]
                  (js/Promise.
                   (fn [inner-resolve _inner-reject]
                     (letfn [(process-next []
                               (-> (.next entries)
                                   (.then (fn [result]
                                            (if (.-done result)
                                              (inner-resolve @files)
                                              (let [entry (.-value result)
                                                    filename (aget entry 0)
                                                    kind (.-kind (aget entry 1))]
                                                (when (and (= kind "file")
                                                           (str/ends-with? filename ".md"))
                                                  (swap! files conj filename))
                                                (process-next)))))))]
                       (process-next)))))))
             (.then on-resolve)))))))

;; ── Public API ───────────────────────────────────────────────────────────────

(defn save-page!
  "Save a single page to its markdown file."
  [db page-id]
  (let [title (get-in db [:nodes page-id :props :title] page-id)
        filename (str (sanitize-filename title) ".md")
        markdown (page->markdown db page-id)]
    (write-file! filename markdown)))

(defn save-db!
  "Save all pages to markdown files."
  [db]
  (when (has-folder?)
    (let [pages (filter (fn [[_id node]]
                          (= :page (:type node)))
                        (:nodes db))]
      (doseq [[page-id _] pages]
        (save-page! db page-id))
      (js/console.log "💾 Saved to folder"))))

(defn load-all-pages
  "Load all pages from markdown files in the folder.
   Returns a Promise that resolves to vector of ops."
  []
  (if (has-folder?)
    (-> (list-md-files)
        (.then (fn [files]
                 (js/Promise.all
                  (to-array
                   (map (fn [filename]
                          (-> (read-file filename)
                              (.then (fn [content]
                                       (when content
                                         (let [page-id (str/replace filename #"\.md$" "")]
                                           (markdown->ops page-id content)))))))
                        files)))))
        (.then (fn [results]
                 (vec (apply concat (filter some? results))))))
    (js/Promise.resolve nil)))

(defn restore-folder!
  "Try to restore the previously selected folder from IndexedDB.
   Returns Promise<boolean> indicating if folder was restored.
   Returns false immediately on unsupported browsers."
  []
  (if-not (exists? js/window.showDirectoryPicker)
    (js/Promise.resolve false)
    (-> (open-idb)
        (.then (fn [_] (load-handle-from-idb)))
        (.then (fn [handle]
                 (if handle
                   ;; Request permission again (required after page reload)
                   (-> (.requestPermission handle #js {:mode "readwrite"})
                       (.then (fn [permission]
                                (if (= permission "granted")
                                  (do
                                    (reset! !dir-handle handle)
                                    (js/console.log "📁 Restored folder:" (.-name handle))
                                    true)
                                  (do
                                    (js/console.log "📁 Permission denied, need to pick folder again")
                                    false)))))
                   (js/Promise.resolve false))))
        (.catch (fn [err]
                  (js/console.warn "Could not restore folder:" err)
                  false)))))

(defn get-folder-name
  "Get the name of the currently selected folder."
  []
  (when-let [handle @!dir-handle]
    (.-name handle)))

(defn delete-page-file!
  "Delete a page's markdown file."
  [_page-id title]
  (when-let [dir @!dir-handle]
    (let [filename (str (sanitize-filename title) ".md")]
      (-> (.removeEntry dir filename)
          (.then #(js/console.log "🗑️ Deleted:" filename))
          (.catch #(js/console.warn "Could not delete:" filename))))))

(defn clear-folder!
  "Clear the stored folder reference."
  []
  (reset! !dir-handle nil)
  (when-let [idb-conn @!idb]
    (let [tx (.transaction idb-conn #js [store-name] "readwrite")
          store (.objectStore tx store-name)]
      (.delete store "dir-handle"))))

;; ── Asset Storage ────────────────────────────────────────────────────────────

(defn ensure-assets-dir!
  "Create assets directory if it doesn't exist.
   Returns Promise<DirectoryHandle> for the assets folder."
  []
  (when-let [dir @!dir-handle]
    (.getDirectoryHandle dir "assets" #js {:create true})))

(defn generate-asset-filename
  "Generate a unique filename for an asset.
   Format: {sanitized-name}_{timestamp}_{index}.{ext}
   
   Example: screenshot_1734000000000_0.png"
  [original-name index]
  (let [;; Extract extension
        dot-idx (str/last-index-of original-name ".")
        ext (if dot-idx
              (subs original-name (inc dot-idx))
              "bin")
        ;; Extract and sanitize stem
        stem (if dot-idx
               (subs original-name 0 dot-idx)
               original-name)
        sanitized-stem (-> stem
                           (str/replace #"[^a-zA-Z0-9]" "_")
                           (str/replace #"_+" "_")
                           (str/replace #"^_|_$" ""))
        ;; Use "image" if stem is empty
        final-stem (if (str/blank? sanitized-stem) "image" sanitized-stem)]
    (str final-stem "_" (.now js/Date) "_" index "." ext)))

(defn write-asset!
  "Write a binary blob to the assets directory.
   
   Args:
     filename: Name of the file (e.g., 'screenshot_123_0.png')
     blob: File or Blob object to write
     
   Returns Promise<string> with the relative path '../assets/filename'
   for use in markdown image syntax."
  [filename blob]
  (-> (ensure-assets-dir!)
      (.then (fn [assets-dir]
               (.getFileHandle assets-dir filename #js {:create true})))
      (.then (fn [file-handle]
               (.createWritable file-handle)))
      (.then (fn [writable]
               (-> (.write writable blob)
                   (.then #(.close writable))
                   (.then #(str "../assets/" filename)))))
      (.catch (fn [err]
                (js/console.error "Failed to write asset:" filename err)
                nil))))

(defn get-asset-url
  "Get a URL for an asset file that can be used in <img> src.
   Returns Promise<string> with a blob URL, or nil if file doesn't exist."
  [filename]
  (-> (ensure-assets-dir!)
      (.then (fn [assets-dir]
               (.getFileHandle assets-dir filename)))
      (.then (fn [file-handle]
               (.getFile file-handle)))
      (.then (fn [file]
               (js/URL.createObjectURL file)))
      (.catch (fn [_err]
                nil))))
