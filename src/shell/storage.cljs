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

   Each block is followed by a Logseq-style `id:: <block-id>` property
   line so reload preserves identity across sessions. This unlocks
   cross-device consistency (same file → same ids) and future block-ref
   support. Trade-off: one extra property line per block in the diff.

   Handles block types:
   - :block (default): Text content with bullet (images use ![alt](path){width=N} markdown)
   - :embed: Embed syntax {{video url}} or {{tweet url}}

   Multiline text blocks (from Shift+Enter) are formatted as:
   - first line
     continuation line
     id:: <uuid>"
  [db block-id depth]
  (let [node (get-in db [:nodes block-id])
        block-type (get node :type :block)
        props (get node :props {})
        children (get-in db [:children-by-parent block-id] [])
        indent (apply str (repeat (* depth 2) " "))
        property-indent (str indent "  ")
        own-lines (case block-type
                    ;; Embed block: serialize as {{type url}} syntax
                    :embed
                    (let [url (get props :url "")
                          embed-type (get props :embed-type :video)
                          type-name (case embed-type
                                      :twitter "tweet"
                                      :youtube "video"
                                      :vimeo "video"
                                      "video")]
                      [(str indent "- {{" type-name " " url "}}")])

                    ;; Default text block
                    (let [text (get props :text "")
                          text-lines (str/split-lines text)
                          first-line (str indent "- " (first text-lines))
                          continuation-lines (map #(str property-indent %) (rest text-lines))]
                      (into [first-line] continuation-lines)))
        id-line (str property-indent "id:: " block-id)
        block-lines (conj (vec own-lines) id-line)]
    (into block-lines
          (mapcat #(block->markdown db % (inc depth)) children))))

(defn page->markdown
  "Serialize a page to Logseq-style markdown."
  [db page-id]
  (let [title (get-in db [:nodes page-id :props :title] "Untitled")
        created-at (get-in db [:nodes page-id :props :created-at])
        updated-at (get-in db [:nodes page-id :props :updated-at])
        trashed-at (get-in db [:nodes page-id :props :trashed-at])
        children (get-in db [:children-by-parent page-id] [])
        header-lines (cond-> [(str "title:: " title)]
                       created-at (conj (str "created-at:: " created-at))
                       updated-at (conj (str "updated-at:: " updated-at))
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

(defn- parse-header-line
  "Parse a single header line, returning a map with the property key and value.
   Returns nil if line is not a recognized header property."
  [line]
  (cond
    (str/starts-with? line "title:: ")
    {:key :title :value (subs line 8)}

    (str/starts-with? line "created-at:: ")
    {:key :created-at :value (js/parseInt (subs line 13) 10)}

    (str/starts-with? line "updated-at:: ")
    {:key :updated-at :value (js/parseInt (subs line 13) 10)}

    (str/starts-with? line "trashed-at:: ")
    {:key :trashed-at :value (js/parseInt (subs line 13) 10)}

    :else nil))

(defn- parse-markdown-header
  "Extract header properties and content lines from markdown.
   Returns {:title :created-at :updated-at :trashed-at :content-lines}."
  [lines]
  (loop [remaining lines
         metadata {}]
    (if-let [line (first remaining)]
      (if-let [{:keys [key value]} (parse-header-line line)]
        (recur (rest remaining) (assoc metadata key value))
        ;; First non-header line - done with header
        {:title (get metadata :title "Untitled")
         :created-at (:created-at metadata)
         :updated-at (:updated-at metadata)
         :trashed-at (:trashed-at metadata)
         :content-lines remaining})
      ;; No more lines - header-only file
      {:title (get metadata :title "Untitled")
       :created-at (:created-at metadata)
       :updated-at (:updated-at metadata)
       :trashed-at (:trashed-at metadata)
       :content-lines []})))

(defn- build-page-props
  "Build page props map with timestamps, using frontmatter or file-modified fallback."
  [title created-at updated-at trashed-at file-modified]
  (cond-> {:title title}
    trashed-at (assoc :trashed-at trashed-at)
    (or created-at file-modified)
    (assoc :created-at (or created-at file-modified))
    (or updated-at file-modified)
    (assoc :updated-at (or updated-at file-modified))))

(def ^:private embed-line-pattern
  "Regex to match embed syntax: {{type url}}
   Supports: {{video url}}, {{tweet url}}"
  #"^\{\{(video|tweet)\s+([^}]+)\}\}$")

(defn- parse-embed-line
  "Parse an embed line. Returns {:embed-type :url} or nil if not an embed line."
  [text]
  (when-let [match (re-matches embed-line-pattern text)]
    (let [type-str (nth match 1)
          url (str/trim (nth match 2))]
      {:embed-type (case type-str
                     "tweet" :twitter
                     "video" :youtube  ;; Default video to :youtube, could be refined
                     :youtube)
       :url url})))

(defn- is-bullet-line?
  "Check if line contains a bullet marker."
  [line]
  (str/includes? line "- "))

(def ^:private id-property-prefix "id:: ")

(defn- id-property-value
  "Return the trimmed id value if this is an `id:: ...` property line, else nil."
  [trimmed]
  (when (str/starts-with? trimmed id-property-prefix)
    (str/trim (subs trimmed (count id-property-prefix)))))

(defn- finalize-block
  "Close out a collected block. If the *last* continuation line is an
   `id:: ...` property, extract it as :raw-id and drop from body.

   Only the terminal id:: counts — any id::-looking line with real text
   or another id:: after it is preserved as user content. This matches
   evo's serializer invariant (one trailing id:: per block) while
   refusing to steal user text that happens to contain 'id:: ' as a
   continuation."
  [block]
  (let [lines (:raw-lines block)
        n (count lines)
        last-line (when (pos? n) (nth lines (dec n)))
        raw-id (when last-line
                 (id-property-value (str/triml last-line)))
        body-lines (if raw-id (subvec (vec lines) 0 (dec n)) (vec lines))
        text (if (:embed? block) "" (str/join "\n" body-lines))]
    (-> block
        (dissoc :raw-lines)
        (assoc :text text :raw-id raw-id))))

(defn- parse-blocks
  "First pass: group content lines into block records preserving source
   order. Returns vector of {:depth :embed? :text :embed-data :raw-id}.

   All non-blank, non-bullet lines are collected into :raw-lines;
   finalize-block (on block close) decides whether the final line was
   the block's id:: property."
  [content-lines]
  (let [push (fn [blocks current]
               (if current (conj blocks (finalize-block current)) blocks))]
    (loop [remaining content-lines
           blocks []
           current nil]
      (if-let [line (first remaining)]
        (let [trimmed (str/triml line)]
          (cond
            ;; New bullet → flush current, start new block
            (is-bullet-line? line)
            (let [depth (parse-indent-level line)
                  content (strip-bullet line)
                  embed-data (parse-embed-line content)
                  block (if embed-data
                          {:depth depth :embed? true :embed-data embed-data
                           :raw-lines []}
                          {:depth depth :embed? false
                           :raw-lines [content]})]
              (recur (rest remaining) (push blocks current) block))

            ;; Non-blank, non-bullet line → continuation
            (and current
                 (not (str/blank? trimmed))
                 (not (str/starts-with? trimmed "-")))
            (recur (rest remaining) blocks
                   (update current :raw-lines conj trimmed))

            ;; Blank / pre-first-bullet — skip
            :else
            (recur (rest remaining) blocks current)))
        (push blocks current)))))

(defn- warn
  "Emit a warning about malformed input. Never throws."
  [msg]
  (js/console.warn (str "[storage/markdown->ops] " msg)))

(defn- mint-id
  "Mint a new block id. Uses the runtime's `block-<uuid>` convention so the
   id will round-trip cleanly on next save."
  []
  (str "block-" (random-uuid)))

(defn- assign-ids
  "Second pass: resolve :id for each block.

   Total rules for malformed input:
   - No :raw-id (never had id::, or user deleted it) → mint fresh.
   - Blank :raw-id value → mint + warn.
   - Duplicate :raw-id across blocks → keep first, mint replacement + warn.
   Everything else is accepted as-is; we deliberately don't enforce a
   UUID shape so evo's own `block-<uuid>` / `page-<uuid>` formats pass."
  [blocks]
  (let [seen (volatile! #{})]
    (mapv (fn [{:keys [raw-id] :as block}]
            (let [id (cond
                       (nil? raw-id) (mint-id)

                       (str/blank? raw-id)
                       (do (warn "blank id:: value — minting new id")
                           (mint-id))

                       (contains? @seen raw-id)
                       (do (warn (str "duplicate id:: " raw-id
                                      " — re-minting second occurrence"))
                           (mint-id))

                       :else raw-id)]
              (vswap! seen conj id)
              (assoc block :id id)))
          blocks)))

(defn- blocks->ops
  "Third pass: emit create-node + place ops for assigned blocks, tracking
   the parent stack by depth."
  [page-id blocks]
  (:ops
   (reduce
    (fn [{:keys [ops parent-stack]} {:keys [depth embed? embed-data text id]}]
      (let [parent-id (get parent-stack depth page-id)
            create-op (if embed?
                        {:op :create-node :id id :type :embed
                         :props {:url (:url embed-data)
                                 :embed-type (:embed-type embed-data)}}
                        ;; Default: text block (images are markdown text)
                        {:op :create-node :id id :type :block
                         :props {:text text}})]
        {:ops (-> ops
                  (conj create-op)
                  (conj {:op :place :id id :under parent-id :at :last}))
         :parent-stack (assoc parent-stack (inc depth) id)}))
    {:ops [] :parent-stack {0 page-id}}
    blocks)))

(defn markdown->ops
  "Parse Logseq-style markdown into kernel ops.

   Three passes: group lines into block records, resolve ids (using
   Logseq-style `id::` property lines when present), emit ops.

   Optional opts map:
   - :file-modified - file's lastModified timestamp (fallback for created-at/updated-at)"
  [page-id markdown & [{:keys [file-modified]}]]
  (let [lines (str/split-lines markdown)
        {:keys [title created-at updated-at trashed-at content-lines]}
        (parse-markdown-header lines)

        root (if trashed-at :trash :doc)
        page-props (build-page-props title created-at updated-at trashed-at file-modified)

        page-ops [{:op :create-node :id page-id :type :page :props page-props}
                  {:op :place :id page-id :under root :at :last}]

        blocks (-> content-lines parse-blocks assign-ids)
        body-ops (blocks->ops page-id blocks)]
    (into page-ops body-ops)))

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
  "Read content from a file in the selected directory.
   Returns {:content string :last-modified timestamp} or nil."
  [filename]
  (when-let [dir @!dir-handle]
    (-> (.getFileHandle dir filename)
        (.then (fn [file-handle]
                 (.getFile file-handle)))
        (.then (fn [file]
                 (let [last-mod (.-lastModified file)]
                   (-> (.text file)
                       (.then (fn [text]
                                {:content text
                                 :last-modified last-mod}))))))
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

(defn save-pages!
  "Save only the given page ids to markdown files. Skips ids that are not pages
   in the current db (e.g., deleted since the change was observed)."
  [db page-ids]
  (when (and (has-folder?) (seq page-ids))
    (let [written (volatile! 0)]
      (doseq [page-id page-ids]
        (when (= :page (get-in db [:nodes page-id :type]))
          (save-page! db page-id)
          (vswap! written inc)))
      (when (pos? @written)
        (js/console.log (str "💾 Saved " @written " page(s)"))))))

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
                              (.then (fn [file-data]
                                       (when file-data
                                         (let [page-id (str/replace filename #"\.md$" "")
                                               {:keys [content last-modified]} file-data]
                                           (markdown->ops page-id content
                                                          {:file-modified last-modified})))))))
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
      (.then (fn [content-hash]
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
                 (str final-stem "_" content-hash "." ext))))))

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
                   (.then (fn [file-exists]
                            (if file-exists
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
