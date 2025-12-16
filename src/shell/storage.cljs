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
   Returns vector of lines with proper indentation.
   
   Multiline blocks (from Shift+Enter) are formatted as:
   - first line
     continuation line
     another continuation"
  [db block-id depth]
  (let [text (get-in db [:nodes block-id :props :text] "")
        children (get-in db [:children-by-parent block-id] [])
        indent (apply str (repeat (* depth 2) " "))
        ;; Split text on newlines for multiline support
        text-lines (str/split-lines text)
        ;; First line gets bullet, continuation lines get extra indent (no bullet)
        first-line (str indent "- " (first text-lines))
        continuation-indent (str indent "  ") ;; 2 extra spaces to align with text after "- "
        continuation-lines (map #(str continuation-indent %) (rest text-lines))
        lines (into [first-line] continuation-lines)]
    (into lines
          (mapcat #(block->markdown db % (inc depth)) children))))

(defn page->markdown
  "Serialize a page to Logseq-style markdown."
  [db page-id]
  (let [title (get-in db [:nodes page-id :props :title] "Untitled")
        trashed-at (get-in db [:nodes page-id :props :trashed-at])
        children (get-in db [:children-by-parent page-id] [])
        header-lines (cond-> [(str "title:: " title)]
                       trashed-at (conj (str "trashed-at:: " trashed-at)))]
    (str/join "\n"
              (into header-lines
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

(defn- parse-trashed-at-line
  "Extract trashed-at timestamp from 'trashed-at:: Value' line."
  [line]
  (when (str/starts-with? line "trashed-at:: ")
    (js/parseInt (subs line 13) 10)))

(defn markdown->ops
  "Parse Logseq-style markdown into kernel ops.
   
   Handles multiline blocks where continuation lines are indented without bullets:
   - first line
     continuation line (no bullet, just indented)"
  [page-id markdown]
  (let [lines (str/split-lines markdown)
        ;; Parse header properties (title::, trashed-at::)
        {:keys [title trashed-at content-lines]}
        (loop [lines lines
               title nil
               trashed-at nil]
          (if (empty? lines)
            {:title (or title "Untitled") :trashed-at trashed-at :content-lines []}
            (let [line (first lines)]
              (cond
                (str/starts-with? line "title:: ")
                (recur (rest lines) (subs line 8) trashed-at)

                (str/starts-with? line "trashed-at:: ")
                (recur (rest lines) title (js/parseInt (subs line 13) 10))

                :else
                {:title (or title "Untitled")
                 :trashed-at trashed-at
                 :content-lines lines}))))

        ;; Determine root based on trashed state
        root (if trashed-at :trash :doc)

        ;; Page props include trashed-at if present
        page-props (cond-> {:title title}
                     trashed-at (assoc :trashed-at trashed-at))

        ops (atom [{:op :create-node :id page-id :type :page :props page-props}
                   {:op :place :id page-id :under root :at :last}])
        counter (atom 0)
        parent-stack (atom {0 page-id})
        ;; Track last block for continuation lines
        last-block-id (atom nil)
        last-block-text (atom nil)]

    (doseq [line content-lines]
      (let [has-bullet? (str/includes? line "- ")
            trimmed (str/triml line)
            ;; Continuation line: indented but no bullet at start
            is-continuation? (and (not (str/starts-with? trimmed "-"))
                                  (not (str/blank? trimmed))
                                  @last-block-id)]
        (cond
          ;; Regular bullet line - new block
          has-bullet?
          (let [depth (parse-indent-level line)
                text (strip-bullet line)
                block-id (str page-id "-b" (swap! counter inc))
                parent-id (or (get @parent-stack depth) page-id)]
            (swap! ops conj {:op :create-node :id block-id :type :block :props {:text text}})
            (swap! ops conj {:op :place :id block-id :under parent-id :at :last})
            (swap! parent-stack assoc (inc depth) block-id)
            ;; Track for potential continuation
            (reset! last-block-id block-id)
            (reset! last-block-text text))

          ;; Continuation line - append to last block with newline
          is-continuation?
          (let [new-text (str @last-block-text "\n" trimmed)]
            (reset! last-block-text new-text)
            ;; Update the create-node op for this block
            (swap! ops (fn [current-ops]
                         (mapv (fn [op]
                                 (if (and (= (:op op) :create-node)
                                          (= (:id op) @last-block-id))
                                   (assoc-in op [:props :text] new-text)
                                   op))
                               current-ops)))))))

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
   Returns false immediately on unsupported browsers.

   IMPORTANT: After getting permission, we verify the folder still exists
   by attempting to iterate its entries. If the folder was moved/deleted,
   this will fail and we clear the stale handle."
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
                                  ;; Verify folder still exists by trying to access it
                                  (-> (.entries handle)
                                      (.next)
                                      (.then (fn [_]
                                               (reset! !dir-handle handle)
                                               (js/console.log "📁 Restored folder:" (.-name handle))
                                               true))
                                      (.catch (fn [err]
                                                ;; Folder was moved/deleted - clear stale handle
                                                (js/console.warn "📁 Folder no longer exists:" (.-name handle))
                                                (js/console.warn "   Error:" (.-message err))
                                                ;; Just reset atom - full cleanup via clear-folder! not needed here
                                                (reset! !dir-handle nil)
                                                false)))
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

(defn- hash-blob
  "Compute SHA-256 hash of a blob, returning first 12 hex chars.
   Uses Web Crypto API (built into browsers)."
  [blob]
  (-> (.arrayBuffer blob)
      (.then (fn [buffer]
               (js/crypto.subtle.digest "SHA-256" buffer)))
      (.then (fn [hash-buffer]
               (let [hash-array (js/Array.from (js/Uint8Array. hash-buffer))
                     hex-string (.join (.map hash-array
                                             (fn [b] (.padStart (.toString b 16) 2 "0")))
                                       "")]
                 ;; Return first 12 chars - enough for uniqueness
                 (subs hex-string 0 12))))))

(defn generate-asset-filename
  "Generate a unique filename for an asset.
   Format: {sanitized-name}_{timestamp}_{index}.{ext}

   Example: screenshot_1734000000000_0.png

   Note: This is the fallback. Prefer generate-asset-filename-with-hash for dedup."
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

(defn generate-asset-filename-with-hash
  "Generate filename using content hash for deduplication.
   Format: {sanitized-name}_{hash}.{ext}

   Returns Promise<string>."
  [original-name blob]
  (-> (hash-blob blob)
      (.then (fn [hash]
               (let [dot-idx (str/last-index-of original-name ".")
                     ext (if dot-idx
                           (subs original-name (inc dot-idx))
                           "bin")
                     stem (if dot-idx
                            (subs original-name 0 dot-idx)
                            original-name)
                     sanitized-stem (-> stem
                                        (str/replace #"[^a-zA-Z0-9]" "_")
                                        (str/replace #"_+" "_")
                                        (str/replace #"^_|_$" ""))
                     final-stem (if (str/blank? sanitized-stem) "image" sanitized-stem)]
                 (str final-stem "_" hash "." ext))))))

(defn- file-exists?
  "Check if a file exists in the assets directory.
   Returns Promise<boolean>."
  [assets-dir filename]
  (-> (.getFileHandle assets-dir filename #js {:create false})
      (.then (fn [_] true))
      (.catch (fn [_] false))))

(defn write-asset!
  "Write a binary blob to the assets directory with deduplication.

   If a file with the same name already exists, returns the path
   without rewriting (assumes hash-based filename means same content).

   Args:
     filename: Name of the file (e.g., 'image_a1b2c3d4e5f6.png')
     blob: File or Blob object to write

   Returns Promise<string> with the relative path '../assets/filename'
   for use in markdown image syntax."
  [filename blob]
  (-> (ensure-assets-dir!)
      (.then (fn [assets-dir]
               (-> (file-exists? assets-dir filename)
                   (.then (fn [exists?]
                            (if exists?
                              ;; File exists - dedup: return path without writing
                              (do
                                (js/console.log "📷 Dedup: reusing existing asset" filename)
                                (str "../assets/" filename))
                              ;; File doesn't exist - write it
                              (-> (.getFileHandle assets-dir filename #js {:create true})
                                  (.then (fn [file-handle]
                                           (.createWritable file-handle)))
                                  (.then (fn [writable]
                                           (-> (.write writable blob)
                                               (.then #(.close writable))
                                               (.then #(str "../assets/" filename))))))))))))
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
