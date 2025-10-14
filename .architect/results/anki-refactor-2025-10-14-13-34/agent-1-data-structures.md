[2025-10-14T20:34:48] OpenAI Codex v0.42.0 (research preview)
--------
workdir: /Users/alien/Projects/evo
model: gpt-5-codex
provider: openai
approval: never
sandbox: workspace-write [workdir, /tmp, $TMPDIR]
reasoning effort: high
reasoning summaries: auto
--------
[2025-10-14T20:34:48] User instructions:
You are reviewing a local-first Anki clone codebase (533 LOC).

CODEBASE:
This file is a merged representation of a subset of the codebase, containing specifically included files, combined into a single document by Repomix.

<file_summary>
This section contains a summary of this file.

<purpose>
This file contains a packed representation of a subset of the repository's contents that is considered the most important context.
It is designed to be easily consumable by AI systems for analysis, code review,
or other automated processes.
</purpose>

<file_format>
The content is organized as follows:
1. This summary section
2. Repository information
3. Directory structure
4. Repository files (if enabled)
5. Multiple file entries, each consisting of:
  - File path as an attribute
  - Full contents of the file
</file_format>

<usage_guidelines>
- This file should be treated as read-only. Any changes should be made to the
  original repository files, not this packed version.
- When processing this file, use the file path to distinguish
  between different files in the repository.
- Be aware that this file may contain sensitive information. Handle it with
  the same level of security as you would the original repository.
</usage_guidelines>

<notes>
- Some files may have been excluded based on .gitignore rules and Repomix's configuration
- Binary files are not included in this packed representation. Please refer to the Repository Structure section for a complete list of file paths, including binary files
- Only files matching these patterns are included: *.cljc, *.cljs
- Files matching patterns in .gitignore are excluded
- Files matching default ignore patterns are excluded
- Files are sorted by Git change count (files with more changes are at the bottom)
</notes>

</file_summary>

<directory_structure>
core.cljc
fs.cljs
fsrs.cljc
occlusion_creator_ui.cljs
occlusion_creator.cljs
test_occlusion.cljs
ui.cljs
</directory_structure>

<files>
This section contains the contents of the repository's files.

<file path="core.cljc">
(ns lab.anki.core
  "Core Anki clone data structures and operations"
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [medley.core :as m]
            [lab.anki.fsrs :as fsrs]
            [core.time :as time])) ;; New import

;; Card parsing

(def cloze-pattern #"\[([^\]]+)\]")
(def image-occlusion-pattern #"^!\[(.+?)\]\((.+?)\)\s*\{(.+?)\}$")

(defn parse-qa-multiline
  "Parse QA card from consecutive q/a lines"
  [lines]
  (let [line-map (->> lines
                      (map str/trim)
                      (filter #(re-find #"^(q|a) " %))
                      (map (fn [line] [(first line) (subs line 2)]))
                      (into {}))]
    (when (and (get line-map \q) (get line-map \a))
      {:question (str/trim (get line-map \q))
       :answer (str/trim (get line-map \a))})))

(def card-parsers
  "Registry of card parsers - add new card types here.
   Order matters: more specific patterns should come first."
  [{:type :edn
    :parse (fn [text]
             (try
               (let [data (edn/read-string text)]
                 ;; Only return if it's a valid card map with :type
                 (when (and (map? data) (:type data))
                   data))
               (catch #?(:clj Exception :cljs js/Error) _e
                 nil)))}
   {:type :image-occlusion
    :parse (fn [text]
             (when-let [[_ alt-text image-url regions] (re-matches image-occlusion-pattern text)]
               {:alt-text (str/trim alt-text)
                :image-url (str/trim image-url)
                :regions (str/split regions #",\s*")}))}
   {:type :cloze
    :parse (fn [text]
             (when (str/starts-with? (str/trim text) "c ")
               (let [content (subs (str/trim text) 2)]
                 (when-let [matches (re-seq cloze-pattern content)]
                   {:template content
                    :deletions (mapv second matches)}))))}
   {:type :qa
    :parse (fn [text]
             (parse-qa-multiline (str/split-lines text)))}])

(defn parse-card
  "Parse a card from markdown text. Returns nil if invalid."
  [text]
  (let [trimmed (str/trim text)]
    (some (fn [{card-type :type parse-fn :parse}]
            (when-let [data (parse-fn trimmed)]
              (assoc data :type card-type)))
          card-parsers)))

;; Card hashing

(defn card-hash
  "Generate a hash for a card based on its content"
  [card-data]
  #?(:clj (let [digest (java.security.MessageDigest/getInstance "SHA-256")
                bs (.getBytes (pr-str card-data) "UTF-8")]
            (.update digest bs)
            (format "%064x" (BigInteger. 1 (.digest digest))))
     :cljs (let [s (pr-str card-data)]
             ;; Simple hash for CLJS (good enough for this use case)
             (str (cljs.core/hash s)))))

;; Card metadata

(defn new-card-meta
  "Create metadata for a new card"
  [h]
  {:card-hash h
   :created-at (time/date-from-ms (time/now-ms))
   :due-at (time/date-from-ms (time/now-ms))
   :reviews 0})

;; Scheduling (FSRS algorithm)

(defn schedule-card
  "Schedule next review using FSRS algorithm.
   Rating: :forgot :hard :good :easy
   review-time-ms: timestamp of the review (defaults to now)

   For first review, card-meta should not have :stability or :difficulty.
   For subsequent reviews, uses FSRS to calculate new stability/difficulty."
  ([card-meta rating]
   (schedule-card card-meta rating (time/now-ms)))
  ([card-meta rating review-time-ms]
   (let [;; Check if first review (no stability/difficulty yet)
         first-review? (nil? (:stability card-meta))

         ;; For subsequent reviews, need last review time
         last-review-ms (when-not first-review?
                          (.getTime (:created-at card-meta)))

         ;; Calculate FSRS parameters
         fsrs-result (fsrs/schedule-next-review
                      (cond-> {:grade rating
                               :review-time-ms review-time-ms}
                        ;; Add current state for non-first reviews
                        (not first-review?)
                        (assoc :stability (:stability card-meta)
                               :difficulty (:difficulty card-meta)
                               :last-review-ms (or (:last-review-ms card-meta)
                                                   (.getTime (:created-at card-meta))))))

         ;; Build updated metadata
         new-meta (-> card-meta
                      (update :reviews inc)
                      (assoc :stability (:stability fsrs-result)
                             :difficulty (:difficulty fsrs-result)
                             :due-at (:due-at fsrs-result)
                             :interval-days (:interval fsrs-result)
                             :last-rating rating
                             :last-review-ms review-time-ms))]
     new-meta)))

;; Event log

(defn new-event
  "Create a new event with unique ID"
  [event-type data]
  {:event/id (random-uuid)
   :event/type event-type
   :event/timestamp (time/date-from-ms (time/now-ms))
   :event/data data})

(defn review-event
  "Create a review event"
  [h rating]
  (new-event :review {:card-hash h
                      :rating rating}))

(defn card-created-event
  "Create a card-created event - stores only hash and deck reference, not content"
  [h deck]
  (new-event :card-created {:card-hash h
                            :deck deck}))

(defn undo-event
  "Create an undo event that marks a target event as undone"
  [target-event-id]
  (new-event :undo {:target-event-id target-event-id}))

(defn redo-event
  "Create a redo event that marks an undone event as active again"
  [target-event-id]
  (new-event :redo {:target-event-id target-event-id}))

;; Image occlusion helpers

(defn expand-image-occlusion
  "Expand an image-occlusion card into virtual child cards for each occlusion.
   Returns a map of {child-hash virtual-card-data}."
  [parent-hash card]
  (when (= :image-occlusion (:type card))
    (let [occlusions (:occlusions card)]
      (reduce (fn [acc occlusion]
                (let [child-id [parent-hash (:oid occlusion)]
                      child-hash (card-hash child-id)]
                  (assoc acc child-hash
                         {:type :image-occlusion/item
                          :parent-id parent-hash
                          :occlusion-oid (:oid occlusion)
                          :asset (:asset card)
                          :prompt (:prompt card)
                          :shape (:shape occlusion)
                          :answer (:answer occlusion)})))
              {}
              occlusions))))

;; State reduction

(defn apply-event
  "Apply an event to the current state.
   Note: Cards are loaded from files, events only track metadata."
  [state event]
  (case (:event/type event)
    :card-created
    ;; Just create metadata entry - card content comes from files
    (let [{h :card-hash} (:event/data event)]
      (assoc-in state [:meta h] (new-card-meta h)))

    :review
    (let [{h :card-hash rating :rating} (:event/data event)
          card-meta (get-in state [:meta h])
          review-time-ms (.getTime (:event/timestamp event))]
      (if card-meta
        (assoc-in state [:meta h] (schedule-card card-meta rating review-time-ms))
        state))

    ;; Unknown event type - ignore
    state))

(defn build-event-status-map
  "Build a map of event-id -> status (:active or :undone)"
  [events]
  (reduce
   (fn [status-map event]
     (case (:event/type event)
       :undo (assoc status-map
                    (get-in event [:event/data :target-event-id])
                    :undone)
       :redo (assoc status-map
                    (get-in event [:event/data :target-event-id])
                    :active)
       status-map))
   {}
   events))

(defn build-undo-redo-stacks
  "Build undo and redo stacks from events.
   Only review events are undoable - card creation is not undoable during review sessions."
  [events event-status]
  (reduce
   (fn [{:keys [undo-stack redo-stack undone-set] :as stacks} event]
     (let [event-id (:event/id event)]
       (case (:event/type event)
         :review
         (if (contains? undone-set event-id)
           stacks ; Don't add undone events to undo stack
           {:undo-stack (conj undo-stack event-id)
            :redo-stack [] ; Clear redo stack on new action
            :undone-set undone-set})

         :card-created
         stacks ; Card creation is not undoable

         :undo
         (let [target-id (get-in event [:event/data :target-event-id])]
           {:undo-stack (vec (remove #{target-id} undo-stack))
            :redo-stack (conj redo-stack target-id)
            :undone-set (conj undone-set target-id)})

         :redo
         (let [target-id (get-in event [:event/data :target-event-id])]
           {:undo-stack (conj undo-stack target-id)
            :redo-stack (vec (remove #{target-id} redo-stack))
            :undone-set (disj undone-set target-id)})

         stacks)))
   {:undo-stack [] :redo-stack [] :undone-set #{}}
   events))

(defn reduce-events
  "Reduce a sequence of events to produce the current state with undo/redo support"
  [events]
  (let [event-status (build-event-status-map events)
        active-events (filter #(let [event-id (:event/id %)
                                     status (get event-status event-id :active)]
                                 (and (= status :active)
                                      (#{:card-created :review} (:event/type %))))
                              events)
        {:keys [undo-stack redo-stack]} (build-undo-redo-stacks events event-status)
        base-state (reduce apply-event
                           {:cards {}
                            :meta {}}
                           active-events)]
    (merge base-state {:undo-stack undo-stack :redo-stack redo-stack})))

;; Query helpers

(defn due-cards
  "Get all cards that are due for review"
  [state]
  (let [now (time/now-ms)]
    (->> (:meta state)
         (m/filter-vals (fn [card-meta]
                          (<= (.getTime (:due-at card-meta)) now)))
         keys
         vec)))

(defn card-with-meta
  "Get card data with its metadata"
  [state h]
  (when-let [card (get-in state [:cards h])]
    (assoc card :meta (get-in state [:meta h]))))

;; Statistics

(defn- today-start-ms
  "Get timestamp for start of today (midnight)"
  []
  #?(:clj (let [cal (doto (java.util.Calendar/getInstance)
                      (.set java.util.Calendar/HOUR_OF_DAY 0)
                      (.set java.util.Calendar/MINUTE 0)
                      (.set java.util.Calendar/SECOND 0)
                      (.set java.util.Calendar/MILLISECOND 0))]
            (.getTimeInMillis cal))
     :cljs (let [now (js/Date.)
                 year (.getFullYear now)
                 month (.getMonth now)
                 date (.getDate now)]
             (.getTime (js/Date. year month date 0 0 0 0)))))

(defn compute-stats
  "Compute statistics from state and events"
  [state events]
  (let [today-start (time/today-start-ms)
        active-events (filter #(let [event-id (:event/id %)
                                     event-status (build-event-status-map events)
                                     status (get event-status event-id :active)]
                                 (= status :active))
                              events)
        review-events (filter #(= :review (:event/type %)) active-events)
        today-reviews (filter #(>= (.getTime (:event/timestamp %)) today-start) review-events)

        ;; Card counts (only count cards that exist in files)
        card-hashes (set (keys (:cards state)))
        total-cards (count (:cards state))
        total-meta (count (m/filter-keys card-hashes (:meta state)))
        due-now (->> (due-cards state)
                     (filter card-hashes) ; Only count cards that exist in files
                     count)
        new-cards (->> (:meta state)
                       (m/filter-keys card-hashes) ; Only count existing cards
                       (m/filter-vals #(zero? (:reviews %)))
                       count)

        ;; Review counts
        total-reviews (count review-events)
        reviews-today (count today-reviews)

        ;; Ratings breakdown (all time)
        ratings-breakdown (->> review-events
                               (map #(get-in % [:event/data :rating]))
                               frequencies)

        ;; Retention (good+easy vs total)
        good-or-easy (+ (get ratings-breakdown :good 0)
                        (get ratings-breakdown :easy 0))
        retention-rate (if (pos? total-reviews)
                         (/ good-or-easy total-reviews)
                         0.0)

        ;; Average reviews per card
        avg-reviews-per-card (if (pos? total-meta)
                               (/ total-reviews total-meta)
                               0.0)]

    {:total-cards total-cards
     :due-now due-now
     :new-cards new-cards
     :total-reviews total-reviews
     :reviews-today reviews-today
     :retention-rate retention-rate
     :avg-reviews-per-card avg-reviews-per-card
     :ratings-breakdown ratings-breakdown}))

;; Validation

(defn check-integrity
  "Check data integrity and return a report of issues"
  [state events]
  (let [event-status (build-event-status-map events)
        active-events (filter #(= :active (get event-status (:event/id %) :active)) events)

        ;; Find orphaned events (events for cards not in files)
        card-hashes (set (keys (:cards state)))
        orphaned-events (->> active-events
                             (filter #(#{:card-created :review} (:event/type %)))
                             (map (fn [e]
                                    (case (:event/type e)
                                      :card-created (get-in e [:event/data :card-hash])
                                      :review (get-in e [:event/data :card-hash])
                                      nil)))
                             (remove nil?)
                             (remove card-hashes)
                             distinct
                             vec)

        ;; Find cards without metadata (in files but not in events)
        meta-hashes (set (keys (:meta state)))
        cards-without-meta (->> card-hashes
                                (remove meta-hashes)
                                vec)

        ;; Check for duplicate event IDs
        event-ids (map :event/id events)
        duplicate-ids (->> event-ids
                           frequencies
                           (filter #(> (val %) 1))
                           (map key)
                           vec)

        ;; Count active vs undone events
        active-count (count active-events)
        undone-count (- (count events) active-count)

        issues []]

    (cond-> issues
      (seq orphaned-events)
      (conj {:type :orphaned-events
             :severity :warning
             :message (str "Found " (count orphaned-events) " orphaned events (cards deleted from files)")
             :data orphaned-events})

      (seq cards-without-meta)
      (conj {:type :cards-without-meta
             :severity :info
             :message (str "Found " (count cards-without-meta) " cards without metadata (new cards)")
             :data cards-without-meta})

      (seq duplicate-ids)
      (conj {:type :duplicate-event-ids
             :severity :error
             :message (str "Found " (count duplicate-ids) " duplicate event IDs")
             :data duplicate-ids}))))
</file>

<file path="fs.cljs">
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
</file>

<file path="fsrs.cljc">
(ns lab.anki.fsrs
  "FSRS (Free Spaced Repetition Scheduler) algorithm implementation
   Ported from hashcards: https://github.com/eudoxia0/hashcards

   The FSRS algorithm tracks:
   - Stability (S): How long a memory lasts
   - Difficulty (D): How hard the card is (1.0 to 10.0)
   - Interval: Days until next review

   Based on: https://github.com/open-spaced-repetition/fsrs4anki")

;; FSRS Parameters (optimized weights)
(def W
  "FSRS model weights - 19 parameters optimized for SRS"
  [0.40255 1.18385 3.173 15.69105 7.1949 0.5345 1.4604 0.0046 1.54575 0.1192 1.01925
   1.9395 0.11 0.29605 2.2698 0.2315 2.9898 0.51655 0.6621])

;; Constants
(def ^:private F (/ 19.0 81.0))
(def ^:private C -0.5)
(def ^:private DESIRED-RETENTION 0.9) ; Default desired retention rate

;; Type aliases (for documentation)
(comment
  (def Recall double)      ; Retrievability: probability of recall (0.0 to 1.0)
  (def Stability double)   ; Stability: how long memory lasts (in days)
  (def Difficulty double)  ; Difficulty: how hard the card is (1.0 to 10.0)
  (def Interval double))   ; Interval: days until next review

;; Core FSRS Functions

(defn retrievability
  "Calculate retrievability (recall probability) based on time elapsed and stability.
   t: time elapsed since last review (in days)
   s: current stability (in days)
   Returns: probability of recall (0.0 to 1.0)"
  [t s]
  (Math/pow (+ 1.0 (* F (/ t s))) C))

(defn interval
  "Calculate optimal interval to achieve desired retention.
   r-d: desired retention rate (typically 0.9)
   s: current stability (in days)
   Returns: optimal interval in days"
  [r-d s]
  (* (/ s F) (- (Math/pow r-d (/ 1.0 C)) 1.0)))

(defn initial-stability
  "Get initial stability based on first grade.
   grade: :forgot :hard :good :easy
   Returns: initial stability in days"
  [grade]
  (case grade
    :forgot (nth W 0)
    :hard   (nth W 1)
    :good   (nth W 2)
    :easy   (nth W 3)))

(defn- clamp-difficulty
  "Clamp difficulty to valid range [1.0, 10.0]"
  [d]
  (max 1.0 (min 10.0 d)))

(defn initial-difficulty
  "Get initial difficulty based on first grade.
   grade: :forgot :hard :good :easy
   Returns: initial difficulty (1.0 to 10.0)"
  [grade]
  (let [g (case grade
            :forgot 1.0
            :hard   2.0
            :good   3.0
            :easy   4.0)]
    (clamp-difficulty
     (+ (- (nth W 4) (Math/exp (* (nth W 5) (- g 1.0)))) 1.0))))

(defn- delta-d
  "Calculate difficulty change based on grade"
  [grade]
  (let [g (case grade
            :forgot 1.0
            :hard   2.0
            :good   3.0
            :easy   4.0)]
    (* (- (nth W 6)) (- g 3.0))))

(defn- dp
  "Calculate intermediate difficulty"
  [d grade]
  (+ d (* (delta-d grade) (/ (- 10.0 d) 9.0))))

(defn new-difficulty
  "Calculate new difficulty after a review.
   d: current difficulty
   grade: review grade
   Returns: new difficulty (1.0 to 10.0)"
  [d grade]
  (clamp-difficulty
   (+ (* (nth W 7) (initial-difficulty :easy))
      (* (- 1.0 (nth W 7)) (dp d grade)))))

(defn- s-success
  "Calculate new stability for successful reviews (Hard/Good/Easy)"
  [d s r grade]
  (let [t-d (- 11.0 d)
        t-s (Math/pow s (- (nth W 9)))
        t-r (- (Math/exp (* (nth W 10) (- 1.0 r))) 1.0)
        h (if (= grade :hard) (nth W 15) 1.0)
        b (if (= grade :easy) (nth W 16) 1.0)
        c (Math/exp (nth W 8))
        alpha (* (+ 1.0 (* t-d t-s t-r h b c)))]
    (* s alpha)))

(defn- s-fail
  "Calculate new stability for failed reviews (Forgot)"
  [d s r]
  (let [d-f (Math/pow d (- (nth W 12)))
        s-f (- (Math/pow (+ s 1.0) (nth W 13)) 1.0)
        r-f (Math/exp (* (nth W 14) (- 1.0 r)))
        c-f (nth W 11)
        s-f (* d-f s-f r-f c-f)]
    (min s-f s)))

(defn new-stability
  "Calculate new stability after a review.
   d: current difficulty
   s: current stability
   r: retrievability at time of review
   grade: review grade
   Returns: new stability in days"
  [d s r grade]
  (if (= grade :forgot)
    (s-fail d s r)
    (s-success d s r grade)))

;; High-level API

(defn schedule-next-review
  "Calculate next review parameters based on current state and grade.

   For first review (no current state):
   {:grade :good} => {:stability 3.17 :difficulty 5.28 :interval 3.0 :due-at <date>}

   For subsequent reviews:
   {:stability 3.17 :difficulty 5.28 :last-review-ms <timestamp> :grade :good}
   => {:stability 10.73 :difficulty 5.27 :interval 11.0 :due-at <date>}

   Parameters:
   - stability: current stability (days) - omit for first review
   - difficulty: current difficulty (1-10) - omit for first review
   - last-review-ms: timestamp of last review - omit for first review
   - grade: review grade (:forgot :hard :good :easy)
   - review-time-ms: timestamp of this review (defaults to now)
   - desired-retention: target retention rate (default 0.9)

   Returns map with:
   - stability: new stability (days)
   - difficulty: new difficulty (1-10)
   - interval: interval to next review (days)
   - interval-ms: interval in milliseconds
   - due-at: Date object for next review"
  [{:keys [stability difficulty last-review-ms grade review-time-ms desired-retention]
    :or {review-time-ms #?(:clj (System/currentTimeMillis)
                          :cljs (.getTime (js/Date.)))
         desired-retention DESIRED-RETENTION}}]

  (let [;; First review or subsequent?
        first-review? (nil? stability)

        ;; Calculate new parameters
        new-s (if first-review?
                (initial-stability grade)
                (let [;; Calculate elapsed time in days
                      elapsed-ms (- review-time-ms last-review-ms)
                      elapsed-days (/ elapsed-ms 1000.0 60.0 60.0 24.0)
                      ;; Calculate retrievability at review time
                      r (retrievability elapsed-days stability)]
                  (new-stability difficulty stability r grade)))

        new-d (if first-review?
                (initial-difficulty grade)
                (new-difficulty difficulty grade))

        ;; Calculate optimal interval
        i-raw (interval desired-retention new-s)
        ;; Round and ensure minimum 1 day
        i-days (max 1.0 (Math/round i-raw))
        i-ms (* i-days 24.0 60.0 60.0 1000.0)

        ;; Calculate due date
        due-ms (+ review-time-ms i-ms)
        due-at #?(:clj (java.util.Date. due-ms)
                 :cljs (js/Date. due-ms))]

    {:stability new-s
     :difficulty new-d
     :interval i-days
     :interval-ms i-ms
     :due-at due-at}))
</file>

<file path="occlusion_creator_ui.cljs">
(ns lab.anki.occlusion-creator-ui
  "UI components for image occlusion creator"
  (:require [lab.anki.occlusion-creator :as creator]))

(defn draw-canvas!
  "Draw image and rectangles on canvas"
  [canvas state]
  (when (and canvas (:image-url state))
    (let [ctx (.getContext canvas "2d")
          img (js/Image.)
          {:keys [image-url image-width image-height occlusions current-rect drawing?]} state]
      (set! (.-onload img)
            (fn []
              ;; Set canvas size
              (set! (.-width canvas) image-width)
              (set! (.-height canvas) image-height)

              ;; Draw image
              (.drawImage ctx img 0 0)

              ;; Draw existing occlusions
              (doseq [{:keys [shape answer]} occlusions]
                (let [x (* (:x shape) image-width)
                      y (* (:y shape) image-height)
                      w (* (:w shape) image-width)
                      h (* (:h shape) image-height)]
                  ;; Draw rectangle
                  (set! (.-strokeStyle ctx) "#00ff00")
                  (set! (.-lineWidth ctx) 3)
                  (.strokeRect ctx x y w h)

                  ;; Draw label
                  (set! (.-fillStyle ctx) "rgba(0, 255, 0, 0.7)")
                  (set! (.-font ctx) "14px sans-serif")
                  (.fillText ctx answer (+ x 5) (+ y 20))))

              ;; Draw current rectangle being drawn
              (when (and drawing? current-rect)
                (let [{:keys [start-x start-y current-x current-y]} current-rect
                      x (min start-x current-x)
                      y (min start-y current-y)
                      w (js/Math.abs (- current-x start-x))
                      h (js/Math.abs (- current-y start-y))]
                  (set! (.-strokeStyle ctx) "#ffff00")
                  (set! (.-lineWidth ctx) 2)
                  (set! (.-setLineDash ctx) #js [5 5])
                  (.strokeRect ctx x y w h)
                  (set! (.-setLineDash ctx) #js [])))))
      (set! (.-src img) image-url))))

(defn get-canvas-coords
  "Get canvas-relative mouse coordinates"
  [canvas event]
  (let [rect (.getBoundingClientRect canvas)
        x (- (.-clientX event) (.-left rect))
        y (- (.-clientY event) (.-top rect))]
    {:x x :y y}))

(defn occlusion-canvas
  "Canvas component for drawing occlusions"
  [{:keys [state on-rect-complete]}]
  [:canvas#occlusion-canvas
   {:style {:border "2px solid #333"
            :cursor "crosshair"
            :display "block"}
    :on {:mousedown (fn [e]
                      (when-let [canvas (.-target e)]
                        (let [{:keys [x y]} (get-canvas-coords canvas e)]
                          (swap! creator/!creator-state assoc
                                 :drawing? true
                                 :current-rect {:start-x x :start-y y
                                                :current-x x :current-y y}))))
         :mousemove (fn [e]
                      (when (:drawing? @creator/!creator-state)
                        (when-let [canvas (.-target e)]
                          (let [{:keys [x y]} (get-canvas-coords canvas e)]
                            (swap! creator/!creator-state assoc-in [:current-rect :current-x] x)
                            (swap! creator/!creator-state assoc-in [:current-rect :current-y] y)
                            (draw-canvas! canvas @creator/!creator-state)))))
         :mouseup (fn [e]
                    (when (:drawing? @creator/!creator-state)
                      (let [rect (:current-rect @creator/!creator-state)
                            {:keys [start-x start-y current-x current-y]} rect
                            x (min start-x current-x)
                            y (min start-y current-y)
                            w (js/Math.abs (- current-x start-x))
                            h (js/Math.abs (- current-y start-y))]
                        (swap! creator/!creator-state assoc :drawing? false :current-rect nil)
                        ;; Only add if rectangle has meaningful size
                        (when (and (> w 10) (> h 10))
                          (on-rect-complete x y w h)))))}}])

(defn occlusion-list
  "List of created occlusions with edit/delete"
  [{:keys [occlusions]}]
  (when (seq occlusions)
    [:div.occlusion-list
     [:h3 "Occlusions"]
     [:div
      (for [{:keys [oid answer]} occlusions]
        ^{:key oid}
        [:div.occlusion-item
         {:style {:display "flex" :gap "10px" :margin-bottom "10px" :align-items "center"}}
         [:input {:type "text"
                  :value answer
                  :on {:change (fn [e]
                                 (let [new-answer (-> e .-target .-value)]
                                   (swap! creator/!creator-state update :occlusions
                                          (fn [occs]
                                            (mapv #(if (= oid (:oid %))
                                                     (assoc % :answer new-answer)
                                                     %)
                                                  occs)))))}}]
         [:button {:on {:click (fn [] (creator/remove-occlusion! oid))}}
          "Delete"]])]]))

(defn creator-screen
  "Main image occlusion creator screen"
  [{:keys [state on-save on-cancel]}]
  [:div.creator-screen
   {:style {:padding "20px"}}
   [:h2 "Create Image Occlusion Card"]

   ;; Upload image
   (if-not (:image-url state)
     [:div
      [:p "Upload an image to create occlusions"]
      [:input {:type "file"
               :accept "image/*"
               :on {:change (fn [e]
                              (when-let [file (-> e .-target .-files (aget 0))]
                                (creator/upload-image! file)))}}]]

     ;; Image loaded - show canvas and controls
     [:div
      ;; Mode selector
      [:div {:style {:margin-bottom "20px"}}
       [:label "Mode: "]
       [:select {:value (name (:mode state))
                 :on {:change (fn [e]
                                (let [mode (keyword (-> e .-target .-value))]
                                  (swap! creator/!creator-state assoc :mode mode)))}}
        [:option {:value "hide-all-guess-one"} "Hide All, Guess One"]
        [:option {:value "hide-one-guess-one"} "Hide One, Guess One"]]]

      [:div {:style {:margin-bottom "20px"}}
       [:label "Prompt: "]
       [:input {:type "text"
                :value (:prompt state)
                :style {:width "400px"}
                :on {:change (fn [e]
                               (let [v (-> e .-target .-value)]
                                 (swap! creator/!creator-state assoc :prompt v)))}}]]

      [:p "Draw rectangles on the image by clicking and dragging:"]
      (occlusion-canvas {:state state
                         :on-rect-complete (fn [x y w h]
                                             (creator/add-occlusion! x y w h (str "Region " (inc (count (:occlusions state))))))})

      [:div {:style {:margin-top "20px"}}
       (occlusion-list {:occlusions (:occlusions state)})]

      [:div.creator-actions
       {:style {:margin-top "20px" :display "flex" :gap "10px"}}
       [:button {:disabled (empty? (:occlusions state))
                 :on {:click on-save}}
        "Save Card"]
       [:button {:on {:click on-cancel}}
        "Cancel"]]])

   ;; Redraw canvas after render
   (js/setTimeout
    (fn []
      (when-let [canvas (js/document.getElementById "occlusion-canvas")]
        (draw-canvas! canvas @creator/!creator-state)))
    0)])
</file>

<file path="occlusion_creator.cljs">
(ns lab.anki.occlusion-creator
  "Image occlusion card creator - upload image and draw rectangles")

;; State for the occlusion creator
(defonce !creator-state
  (atom {:image-url nil
         :image-width nil
         :image-height nil
         :occlusions [] ; [{:oid uuid :shape {:x y w h} :answer "label"}]
         :drawing? false
         :current-rect nil ; {:start-x :start-y :current-x :current-y}
         :prompt "What is this region?"
         :mode :hide-all-guess-one})) ; :hide-all-guess-one or :hide-one-guess-one

(defn upload-image!
  "Handle image file upload"
  [file]
  (let [reader (js/FileReader.)]
    (set! (.-onload reader)
          (fn [e]
            (let [url (-> e .-target .-result)
                  img (js/Image.)]
              (set! (.-onload img)
                    (fn []
                      (swap! !creator-state assoc
                             :image-url url
                             :image-width (.-width img)
                             :image-height (.-height img)
                             :occlusions [])))
              (set! (.-src img) url))))
    (.readAsDataURL reader file)))

(defn normalize-rect
  "Convert canvas pixel coordinates to normalized [0,1] coordinates"
  [img-width img-height x y w h]
  {:x (/ x img-width)
   :y (/ y img-height)
   :w (/ w img-width)
   :h (/ h img-height)})

(defn add-occlusion!
  "Add a new occlusion rectangle"
  [x y w h answer]
  (let [{:keys [image-width image-height]} @!creator-state
        shape (normalize-rect image-width image-height x y w h)]
    (swap! !creator-state update :occlusions conj
           {:oid (random-uuid)
            :shape (assoc shape :kind :rect :normalized? true)
            :answer answer})))

(defn remove-occlusion!
  "Remove an occlusion by oid"
  [oid]
  (swap! !creator-state update :occlusions
         (fn [occs] (vec (remove #(= oid (:oid %)) occs)))))

(defn create-occlusion-card
  "Create an image occlusion card from current state"
  []
  (let [{:keys [image-url image-width image-height occlusions prompt mode]} @!creator-state]
    (when (and image-url (seq occlusions))
      {:type :image-occlusion
       :asset {:url image-url
               :width image-width
               :height image-height}
       :prompt prompt
       :mode mode
       :occlusions occlusions})))

(defn reset-creator!
  "Reset creator state"
  []
  (reset! !creator-state {:image-url nil
                          :image-width nil
                          :image-height nil
                          :occlusions []
                          :drawing? false
                          :current-rect nil
                          :prompt "What is this region?"
                          :mode :hide-all-guess-one}))
</file>

<file path="test_occlusion.cljs">
(ns lab.anki.test-occlusion
  "Test helper for creating image occlusion cards"
  (:require [lab.anki.core :as core]))

(defn normalize-rect
  "Convert pixel coordinates to normalized [0,1] coordinates.
  
  Args:
    img-width  - Image width in pixels
    img-height - Image height in pixels
    x, y       - Top-left corner in pixels
    w, h       - Width and height in pixels
  
  Returns map with :x :y :w :h as normalized floats [0,1]"
  [img-width img-height x y w h]
  {:x (/ x img-width)
   :y (/ y img-height)
   :w (/ w img-width)
   :h (/ h img-height)})

(defn make-occlusion
  "Create an occlusion region from pixel coordinates.
  
  Region spec:
    :x :y :w :h - Pixel coordinates (will be normalized)
    :answer     - Text answer for this region
    :img-width  - Image width for normalization (default 400)
    :img-height - Image height for normalization (default 300)"
  [{:keys [x y w h answer img-width img-height]
    :or {img-width 400 img-height 300}}]
  {:oid (random-uuid)
   :shape (assoc (normalize-rect img-width img-height x y w h)
                 :kind :rect
                 :normalized? true)
   :answer answer})

(def default-test-regions
  "Standard test regions for test-regions.png (400x300)"
  [{:x 50 :y 50 :w 100 :h 80 :answer "Region A"}
   {:x 200 :y 50 :w 100 :h 80 :answer "Region B"}
   {:x 50 :y 180 :w 100 :h 80 :answer "Region C"}
   {:x 200 :y 180 :w 100 :h 80 :answer "Region D"}])

(defn create-test-card
  "Create a test image occlusion card.
  
  Options map:
    :url       - Image URL (default /test-images/test-regions.png)
    :width     - Image width (default 400)
    :height    - Image height (default 300)
    :prompt    - Question prompt (default 'What is this region?')
    :regions   - Vector of region specs (default default-test-regions)
  
  Returns map with :card :hash :event"
  ([] (create-test-card {}))
  ([{:keys [url width height prompt regions]
     :or {url "/test-images/test-regions.png"
          width 400
          height 300
          prompt "What is this region?"
          regions default-test-regions}}]
   (let [;; Inject dimensions into each region for normalization
         regions-with-dims (map #(assoc % :img-width width :img-height height) regions)
         card {:type :image-occlusion
               :asset {:url url
                       :width width
                       :height height}
               :prompt prompt
               :occlusions (mapv make-occlusion regions-with-dims)}
         h (core/card-hash card)]
     {:card card
      :hash h
      :event (core/card-created-event h card)})))

(comment
  ;; Test in REPL - default card:
  (def test-data (create-test-card))
  (println "Card hash:" (:hash test-data))
  (println "Event:" (:event test-data))

  ;; Custom card with different image:
  (def custom-data
    (create-test-card
     {:url "/images/anatomy-diagram.png"
      :width 800
      :height 600
      :regions [{:x 100 :y 100 :w 150 :h 150 :answer "Heart"}
                {:x 400 :y 200 :w 150 :h 150 :answer "Lungs"}]}))

  ;; To add to your anki state:
  ;; (swap! lab.anki.ui/!state update :events conj (:event test-data))
  ;; (swap! lab.anki.ui/!state update :state core/apply-event (:event test-data))
  )
</file>

<file path="ui.cljs">
(ns lab.anki.ui
  "Anki clone UI using Replicant"
  (:require [clojure.string :as str]
            [clojure.walk]
            [lab.anki.core :as core]
            [lab.anki.fs :as fs]
            [lab.anki.occlusion-creator :as creator]
            [lab.anki.occlusion-creator-ui :as creator-ui]
            [promesa.core :as p]
            [replicant.dom :as r]))

;; State
(defonce !state (atom {:screen :setup
                       :state {:cards {} :meta {}}
                       :events []
                       :dir-handle nil
                       :show-answer? false
                       :current-card-hash nil
                       :decks [] ; Available decks from files
                       :selected-deck nil ; nil = all decks, string = specific deck
                       :show-stats? false ; Stats modal visibility
                       }))

;; Helpers
(defn time-ago
  "Convert a date to a human-readable 'X ago' string"
  [date]
  (let [now (js/Date.)
        diff-ms (- (.getTime now) (.getTime date))
        diff-sec (/ diff-ms 1000)
        diff-min (/ diff-sec 60)
        diff-hour (/ diff-min 60)
        diff-day (/ diff-hour 24)]
    (cond
      (< diff-min 1) "just now"
      (< diff-min 2) "1 min ago"
      (< diff-hour 1) (str (js/Math.floor diff-min) " mins ago")
      (< diff-hour 2) "1 hour ago"
      (< diff-day 1) (str (js/Math.floor diff-hour) " hours ago")
      (< diff-day 2) "1 day ago"
      :else (str (js/Math.floor diff-day) " days ago"))))

(defn truncate-text
  "Truncate text to max-len with ellipsis"
  [text max-len]
  (if (> (count text) max-len)
    (str (subs text 0 max-len) "...")
    text))

(defn get-card-preview
  "Get preview text for a card"
  [card]
  (case (:type card)
    :qa (truncate-text (:question card) 50)
    :cloze (truncate-text (:template card) 50)
    :image-occlusion (str "Image: " (:alt-text card))
    :image-occlusion/item (str "Occlusion: " (truncate-text (:answer card) 40))
    "Unknown card type"))

;; Components
(defn setup-screen [{:keys [saved-handle]}]
  [:div.setup-screen
   [:h1 "Welcome to Local-First Anki"]
   [:p "To get started, select a folder to store your cards and review data."]
   [:button
    {:on {:click [::select-folder]}}
    (if saved-handle "Select Folder" "Select Folder")]])

(defn rating-buttons []
  [:div.rating-buttons
   (for [[idx rating] (map-indexed vector [:forgot :hard :good :easy])]
     ^{:key rating}
     [:button
      {:on {:click [::rate-card rating]}}
      (str (str/capitalize (name rating)) " (" (inc idx) ")")])])

(defn- determine-occlusion-style
  "Determines the visual style for an occlusion based on review mode and state."
  [occ current-oid mode show-answer?]
  (let [is-current? (= (:oid occ) current-oid)]
    (case [mode is-current? show-answer?]
      ;; In "Hide All" mode: all rects visible, current has focus color, others green
      [:hide-all-guess-one true false] :focused ; Current rect - orange/yellow to show focus
      [:hide-all-guess-one false false] :hidden ; Other rects - green occluded
      [:hide-all-guess-one true true] :revealed ; Current rect - border + answer text
      [:hide-all-guess-one false true] :hidden ; Other rects - STAY green occluded

      ;; In "Hide One" mode: hide current, show others as context
      [:hide-one-guess-one true false] :hidden ; Current rect - green occluded
      [:hide-one-guess-one false false] :context ; Other rects - blue context borders
      [:hide-one-guess-one true true] :revealed ; Current rect - border + answer text
      [:hide-one-guess-one false true] :invisible ; Other rects - disappear

      :invisible))) ; Default to invisible ; Default to invisible

(defn- draw-styled-occlusion!
  "Draws a single occlusion on the canvas according to a specified style."
  [ctx w h occ style]
  (let [shape (:shape occ)
        x (* (:x shape) w)
        y (* (:y shape) h)
        rect-w (* (:w shape) w)
        rect-h (* (:h shape) h)]
    (case style
      :focused
      ;; Focused rect (before answer) - orange/yellow to show which one is being tested
      (do (set! (.-fillStyle ctx) "rgba(255, 165, 0, 1.0)") ; Orange
          (.fillRect ctx x y rect-w rect-h))

      :hidden
      ;; Hidden/occluded rect - solid green
      (do (set! (.-fillStyle ctx) "rgba(0, 255, 0, 1.0)")
          (.fillRect ctx x y rect-w rect-h))

      :revealed
      ;; Revealed rect (after answer) - border + answer text, transparent background
      (do (set! (.-strokeStyle ctx) "#00ff00")
          (set! (.-lineWidth ctx) 3)
          (.strokeRect ctx x y rect-w rect-h)
          (set! (.-fillStyle ctx) "rgba(0, 255, 0, 0.8)")
          (.fillRect ctx x y (min rect-w 200) 25)
          (set! (.-fillStyle ctx) "#000000")
          (set! (.-font ctx) "14px sans-serif")
          (.fillText ctx (:answer occ) (+ x 5) (+ y 17)))

      :context
      ;; Context rect (hide-one mode) - blue border to show as reference
      (do (set! (.-strokeStyle ctx) "#0088ff")
          (set! (.-lineWidth ctx) 2)
          (.strokeRect ctx x y rect-w rect-h))

      :invisible
      nil)))

(defn draw-occlusion-mask!
  "Draw occlusion mask on canvas - supports hide-all-guess-one and hide-one-guess-one modes"
  [canvas card show-answer?]
  (js/console.log "draw-occlusion-mask! called with canvas:" canvas "show-answer?" show-answer?)
  (js/console.log "Card data:" (pr-str card))
  (when canvas
    (let [ctx (.getContext canvas "2d")
          asset (:asset card)
          w (or (:width asset) 400)
          h (or (:height asset) 300)
          image-url (:url asset)]

      ;; Set canvas size
      (set! (.-width canvas) w)
      (set! (.-height canvas) h)

      (if image-url
        ;; Draw uploaded image
        (let [img (js/Image.)]
          (set! (.-onload img)
                (fn []
                  (.drawImage ctx img 0 0 w h)
                  (let [mode (or (:mode card) :hide-one-guess-one)
                        current-oid (:current-oid card)
                        all-occlusions (:occlusions card)]
                    (doseq [occ all-occlusions]
                      (let [style (determine-occlusion-style occ current-oid mode show-answer?)]
                        (draw-styled-occlusion! ctx w h occ style))))))
          (set! (.-src img) image-url))

        ;; Draw noise background (for testing/fallback)
        (do
          (let [image-data (.createImageData ctx w h)
                data (.-data image-data)]
            (dotimes [i (/ (.-length data) 4)]
              (let [val (+ 100 (rand-int 100))
                    idx (* i 4)]
                (aset data idx val)
                (aset data (+ idx 1) val)
                (aset data (+ idx 2) val)
                (aset data (+ idx 3) 255)))
            (.putImageData ctx image-data 0 0))
          (js/console.log "Noise background drawn")
          ;; Draw mask if not revealed
          (when-not show-answer?
            (let [shape (:shape card)
                  x (* (:x shape) w)
                  y (* (:y shape) h)
                  rect-w (* (:w shape) w)
                  rect-h (* (:h shape) h)]
              (js/console.log "Drawing green mask at" x y rect-w rect-h)
              (set! (.-fillStyle ctx) "rgba(0, 255, 0, 1.0)")
              (.fillRect ctx x y rect-w rect-h))))))))

(defn review-card [{:keys [card show-answer?]}]
  (let [{:keys [front back class-name]}
        (case (:type card)
          :qa {:front [:div.question
                       [:h2 "Question"]
                       [:p (:question card)]]
               :back [:div.answer
                      [:h2 "Answer"]
                      [:p (:answer card)]]
               :class-name "qa-card"}

          :cloze (let [template (:template card)
                       deletions (:deletions card)
                       deletion (first deletions)
                       display-text (if show-answer?
                                      template
                                      (str/replace template
                                                   (re-pattern (str "\\[" deletion "\\]"))
                                                   "[...]"))]
                   {:front [:div.cloze-text [:p display-text]]
                    :back nil
                    :class-name "cloze-card"})

          :image-occlusion {:front [:div.image-occlusion
                                    [:img {:src (:image-url card)
                                           :alt (:alt-text card)}]
                                    [:p "Regions: " (str/join ", " (:regions card))]]
                            :back [:div.answer [:p "Check the image!"]]
                            :class-name "image-occlusion-card"}

          :image-occlusion/item
          (let [canvas-id (str "canvas-" (:hash card) "-" (if show-answer? "revealed" "masked"))]
            ;; Schedule canvas drawing after DOM update
            (js/setTimeout
             (fn []
               (when-let [canvas (js/document.getElementById canvas-id)]
                 (draw-occlusion-mask! canvas card show-answer?)))
             0)
            {:front [:div.image-occlusion-item
                     [:h2 (:prompt card)]
                     [:canvas {:id canvas-id}]]
             :back [:div.answer [:p (:answer card)]]
             :class-name "image-occlusion-item-card"})

          ;; Default case for unknown card types
          {:front [:div.unknown-card
                   [:h2 "Unknown Card Type"]
                   [:p (str "Type: " (:type card))]
                   [:pre (pr-str card)]]
           :back nil
           :class-name "unknown-card"})]

    [:div.review-card {:class class-name}
     front
     (when show-answer? back)
     (if show-answer?
       (rating-buttons)
       [:button {:on {:click [::show-answer]}} "Show Answer (Space)"])]))

(defn review-history
  "Display recent review events"
  [{:keys [events state]}]
  (let [review-events (->> events
                           (filter #(= :review (:event/type %)))
                           ;; Only show reviews for cards that still exist
                           (filter (fn [event]
                                     (let [card-hash (get-in event [:event/data :card-hash])]
                                       (contains? (:cards state) card-hash))))
                           (take-last 10)
                           reverse)]
    (when (seq review-events)
      [:div.review-history
       [:h3 "Recent Reviews"]
       [:div.history-list
        (for [event review-events]
          (let [card-hash (get-in event [:event/data :card-hash])
                rating (get-in event [:event/data :rating])
                timestamp (:event/timestamp event)
                card (get-in state [:cards card-hash])]
            ^{:key (str card-hash "-" (.getTime timestamp))}
            [:div.history-item
             [:div.history-card
              [:span.card-preview (get-card-preview card)]
              [:span.rating {:class (str "rating-" (name rating))}
               (str/capitalize (name rating))]]
             [:span.history-time (time-ago timestamp)]]))]])))

(defn review-screen [{:keys [state events show-answer? current-card-hash selected-deck]}]
  (let [;; Filter cards by selected deck if specified
        filtered-cards (if selected-deck
                         (into {} (filter (fn [[_h card]]
                                            (= selected-deck (:deck card)))
                                          (:cards state)))
                         (:cards state))

        ;; Get due cards from filtered set
        due-hashes (->> (core/due-cards state)
                        (filter #(contains? filtered-cards %))
                        vec)

        ;; Use current-card-hash if valid and still due, otherwise first due card
        current-hash (if (and current-card-hash (some #{current-card-hash} due-hashes))
                       current-card-hash
                       (first due-hashes))
        remaining (count due-hashes)]
    (if current-hash
      (let [card (core/card-with-meta state current-hash)]
        [:div.review-screen
         [:div.review-header
          [:p (str "Cards remaining: " remaining)
           (when selected-deck
             (str " (Deck: " selected-deck ")"))]]
         [:div.review-content
          (review-card {:card card :show-answer? show-answer?})]])
      [:div.review-screen
       [:h2 "No cards due!"]
       [:p (if selected-deck
             (str "No cards due in \"" selected-deck "\" deck.")
             "Come back later for more reviews.")]

       (review-history {:events events :state state})])))

(defn stats-modal [{:keys [stats on-close]}]
  [:div.stats-modal-overlay
   {:on {:click [::toggle-stats]}}
   [:div.stats-modal
    {:on {:click (fn [e] (.stopPropagation e))}} ; Prevent close when clicking inside
    [:h2 "Statistics"]
    [:div.stats-content
     [:div.stat-row
      [:span.stat-label "Total Cards:"]
      [:span.stat-value (:total-cards stats)]]
     [:div.stat-row
      [:span.stat-label "Due Now:"]
      [:span.stat-value (:due-now stats)]]
     [:div.stat-row
      [:span.stat-label "New Cards:"]
      [:span.stat-value (:new-cards stats)]]
     [:div.stat-row
      [:span.stat-label "Reviews Today:"]
      [:span.stat-value (:reviews-today stats)]]
     [:div.stat-row
      [:span.stat-label "Total Reviews:"]
      [:span.stat-value (:total-reviews stats)]]
     [:div.stat-row
      [:span.stat-label "Retention Rate:"]
      [:span.stat-value (str (js/Math.round (* 100 (:retention-rate stats))) "%")]]
     [:div.stat-row
      [:span.stat-label "Avg Reviews/Card:"]
      [:span.stat-value (.toFixed (:avg-reviews-per-card stats) 1)]]

     [:h3 "Ratings Breakdown"]
     (let [breakdown (:ratings-breakdown stats)]
       [:div.ratings-breakdown
        (for [rating [:forgot :hard :good :easy]]
          ^{:key rating}
          [:div.stat-row
           [:span.stat-label (str/capitalize (name rating)) ":"]
           [:span.stat-value (get breakdown rating 0)]])])]
    [:button.close-button
     {:on {:click [::toggle-stats]}}
     "Close"]]])

(defn main-app [{:keys [screen state events show-answer? saved-handle current-card-hash decks selected-deck show-stats?]}]
  (let [undo-stack (:undo-stack state)
        redo-stack (:redo-stack state)
        can-undo? (seq undo-stack)
        can-redo? (seq redo-stack)]
    [:div.anki-app
     [:nav
      [:h1 "Local-First Anki"]
      [:p "Edit .md files in your folder to add/modify cards"]
      [:div.nav-buttons
       (when (= screen :review)
         [:div {:style {:display "flex" :gap "10px" :align-items "center"}}
          ;; Deck selector
          (when (seq decks)
            [:select {:value (or selected-deck "")
                      :on {:change [::select-deck :event/target.value]}}
             [:option {:value ""} "All Decks"]
             (for [deck decks]
               ^{:key deck}
               [:option {:value deck} deck])])
          [:button {:on {:click [::toggle-stats]}}
           "Stats"]
          [:button {:on {:click [::create-occlusion]}}
           "Create Occlusion Card"]
          [:button {:on {:click [::select-folder]}}
           "Change Folder"]])
       [:div.undo-redo-buttons
        [:button {:disabled (not can-undo?)
                  :on {:click [::undo]}}
         "Undo"]
        [:button {:disabled (not can-redo?)
                  :on {:click [::redo]}}
         "Redo"]]]]
     [:main
      (case screen
        :setup (setup-screen {:saved-handle saved-handle})
        :review (review-screen {:state state :events events :show-answer? show-answer? :current-card-hash current-card-hash :selected-deck selected-deck})
        :create-occlusion (creator-ui/creator-screen {:state @creator/!creator-state
                                                      :on-save [::save-occlusion-card]
                                                      :on-cancel [::cancel-occlusion]})
        [:div "Unknown screen"])]
     ;; Stats modal overlay
     (when show-stats?
       (stats-modal {:stats (core/compute-stats state events)
                     :on-close [::toggle-stats]}))]))

;; Forward declarations
(declare render!)

;; Event Handlers

(defn load-and-sync-cards!
  "Load cards from .md files (ground truth), sync with event log (metadata only).
   Returns {:state ... :decks [...]}"
  [dir-handle]
  (p/let [md-files (fs/load-cards dir-handle) ; [{:deck "Geography" :filename "..." :content "..."}]
          events (fs/load-log dir-handle)]
    (let [;; Extract unique decks
          all-decks (->> md-files
                         (map :deck)
                         distinct
                         sort
                         vec)

          ;; Parse cards from all files with deck info
          cards-with-decks (->> md-files
                                (mapcat (fn [{:keys [deck content]}]
                                          (->> (str/split content #"\n\n+")
                                               (keep core/parse-card)
                                               (map #(assoc % :deck deck)))))
                                (map (fn [card]
                                       ;; Hash based on content only (not deck)
                                       (let [content-only (dissoc card :deck)
                                             h (core/card-hash content-only)]
                                         {:hash h
                                          :card card})))
                                vec)

          _ (js/console.log "Parsed" (count cards-with-decks) "cards from" (count md-files) "files")
          _ (js/console.log "Found decks:" (pr-str all-decks))

          ;; Build cards map from files (hash -> card)
          cards-from-files (into {} (map (fn [{:keys [hash card]}]
                                           [hash card])
                                         cards-with-decks))

          ;; Get existing metadata from events
          meta-state (core/reduce-events events)
          existing-meta (get meta-state :meta {})

          ;; Find new cards (in files but not in events)
          existing-hashes (set (keys existing-meta))
          new-cards (remove #(contains? existing-hashes (:hash %)) cards-with-decks)

          _ (js/console.log "Found" (count new-cards) "new cards")

          ;; Create events for new cards (hash + deck only, no content)
          new-events (mapv (fn [{:keys [hash card]}]
                             (core/card-created-event hash (:deck card)))
                           new-cards)]

      ;; Save new events to log
      (when (seq new-events)
        (js/console.log "Saving" (count new-events) "new card events")
        (p/do! (fs/append-to-log dir-handle new-events)))

      ;; Rebuild state with file content + event metadata
      (let [all-events (concat events new-events)
            meta-state (core/reduce-events all-events)
            final-state (assoc meta-state :cards cards-from-files)]
        {:state final-state
         :decks all-decks}))))

;; Removed: create-test-occlusion-card - test code no longer needed

(defn interpolate-actions
  "Replace event placeholders with actual values from DOM event"
  [event actions]
  (clojure.walk/postwalk
   (fn [x]
     (case x
       :event/target.value (.. event -target -value)
       :event/target.checked (.. event -target -checked)
       x))
   actions))

(defn handle-event [_replicant-data [action & args]]
  (case action
    ::select-folder
    (p/let [handle (fs/pick-directory)]
      (js/console.log "Folder selected:" handle)
      (fs/save-dir-handle handle)
      (p/let [result (load-and-sync-cards! handle)
              events (fs/load-log handle)
              state (:state result)
              decks (:decks result)]
        (swap! !state assoc
               :dir-handle handle
               :state state
               :events events
               :saved-handle handle
               :screen :review
               :current-card-hash nil
               :decks decks
               :selected-deck nil)
        (js/console.log "Loaded state with" (count (:cards state)) "total cards")
        (js/console.log "Available decks:" (pr-str decks))))

    ::show-answer
    (swap! !state assoc :show-answer? true)

    ::select-deck
    (let [deck-name (first args)]
      (swap! !state assoc :selected-deck (when (seq deck-name) deck-name))
      (js/console.log "Selected deck:" (or deck-name "All Decks")))

    ::rate-card
    (let [{:keys [state events dir-handle current-card-hash]} @!state
          rating (first args)
          due (core/due-cards state)
          review-hash (or current-card-hash (first due))]
      (when (and review-hash dir-handle)
        (js/console.log "Rating card" rating)
        (let [event (core/review-event review-hash rating)
              new-events (conj events event)
              new-meta-state (core/reduce-events new-events)
              new-state (assoc new-meta-state :cards (:cards state))
              next-due (core/due-cards new-state)
              next-hash (first next-due)]
          ;; Fire and forget - write to disk async (no waiting)
          (fs/append-to-log dir-handle [event])
          ;; Update UI immediately
          (swap! !state assoc
                 :state new-state
                 :events new-events
                 :show-answer? false
                 :current-card-hash next-hash)
          (js/console.log "Review complete, remaining:" (count next-due)))))

    ::undo
    (let [{:keys [state dir-handle events]} @!state
          undo-stack (:undo-stack state)]
      (when (and (seq undo-stack) dir-handle)
        (let [target-event-id (last undo-stack)
              target-event (first (filter #(= target-event-id (:event/id %)) events))
              event (core/undo-event target-event-id)
              new-events (conj events event)
              new-meta-state (core/reduce-events new-events)
              new-state (assoc new-meta-state :cards (:cards state))
              affected-hash (when (= :review (:event/type target-event))
                              (get-in target-event [:event/data :card-hash]))
              due-cards (core/due-cards new-state)
              next-hash (if (and affected-hash (some #{affected-hash} due-cards))
                          affected-hash
                          (first due-cards))]
          (js/console.log "Undoing event" target-event-id)
          ;; Fire and forget - write to disk async
          (fs/append-to-log dir-handle [event])
          ;; Update UI immediately
          (swap! !state assoc
                 :state new-state
                 :events new-events
                 :show-answer? false
                 :current-card-hash next-hash)
          (js/console.log "Undo complete, undo stack:" (count (:undo-stack new-state))))))

    ::redo
    (let [{:keys [state dir-handle events]} @!state
          redo-stack (:redo-stack state)]
      (when (and (seq redo-stack) dir-handle)
        (let [target-event-id (last redo-stack)
              target-event (first (filter #(= target-event-id (:event/id %)) events))
              event (core/redo-event target-event-id)
              new-events (conj events event)
              new-meta-state (core/reduce-events new-events)
              new-state (assoc new-meta-state :cards (:cards state))
              due-cards (core/due-cards new-state)
              next-hash (first due-cards)]
          (js/console.log "Redoing event" target-event-id)
          ;; Fire and forget - write to disk async
          (fs/append-to-log dir-handle [event])
          ;; Update UI immediately
          (swap! !state assoc
                 :state new-state
                 :events new-events
                 :show-answer? false
                 :current-card-hash next-hash)
          (js/console.log "Redo complete, redo stack:" (count (:redo-stack new-state))))))

    ::create-occlusion
    (do
      (creator/reset-creator!)
      (swap! !state assoc :screen :create-occlusion))

    ::save-occlusion-card
    (let [{:keys [dir-handle]} @!state
          occlusion-card (creator/create-occlusion-card)]
      (when (and occlusion-card dir-handle)
        (let [occlusions (:occlusions occlusion-card)
              mode (:mode occlusion-card)]
          (js/console.log "Saving" (count occlusions) "occlusion cards in mode:" mode)
          ;; Create individual cards for each occlusion
          (p/let [individual-cards (mapv (fn [occ]
                                           {:type :image-occlusion/item
                                            :asset (:asset occlusion-card)
                                            :prompt (:prompt occlusion-card)
                                            :mode mode
                                            :occlusions occlusions ; ALL occlusions
                                            :current-oid (:oid occ) ; Which one to test
                                            :answer (:answer occ)
                                            :shape (:shape occ)}) ; For backwards compat
                                         occlusions)
                  ;; Save to Occlusions.md file (ground truth)
                  _ (fs/append-occlusion-cards dir-handle individual-cards)
                  ;; Reload all cards from files (picks up new occlusions)
                  {:keys [state decks]} (load-and-sync-cards! dir-handle)
                  ;; Reload events from log
                  events (fs/load-log dir-handle)]
            (swap! !state assoc
                   :state state
                   :events events
                   :decks decks
                   :screen :review
                   :show-answer? false
                   :current-card-hash (first (core/due-cards state)))
            (creator/reset-creator!)
            (js/console.log (count individual-cards) "occlusion cards saved to Occlusions.md")))))

    ::cancel-occlusion
    (do
      (creator/reset-creator!)
      (swap! !state assoc :screen :review))

    ::toggle-stats
    (swap! !state update :show-stats? not)

    (js/console.warn "Unknown action:" action)))

;; Rendering

(defn render! []
  (r/render (js/document.getElementById "root")
            (main-app @!state)))

;; Keyboard shortcuts

(defn handle-keydown [e]
  (let [{:keys [screen show-answer?]} @!state
        key (.-key e)
        ;; Ignore if typing in input/textarea/select
        target (.-target e)
        tag-name (str/lower-case (.-tagName target))
        is-input? (contains? #{"input" "textarea" "select"} tag-name)]

    (when-not is-input?
      (case screen
        :review
        (cond
          ;; Space to reveal answer
          (and (= key " ") (not show-answer?))
          (do (.preventDefault e)
              (handle-event nil [::show-answer])
              (render!))

          ;; Number keys for rating (only when answer is shown)
          (and show-answer? (contains? #{"1" "2" "3" "4"} key))
          (do (.preventDefault e)
              (let [rating (case key
                            "1" :forgot
                            "2" :hard
                            "3" :good
                            "4" :easy)]
                (handle-event nil [::rate-card rating])
                (render!)))

          ;; u for undo
          (and (= key "u") (seq (:undo-stack (:state @!state))))
          (do (.preventDefault e)
              (handle-event nil [::undo])
              (render!))

          ;; r for redo (shift+u conflicts, so use r)
          (and (= key "r") (seq (:redo-stack (:state @!state))))
          (do (.preventDefault e)
              (handle-event nil [::redo])
              (render!)))

        nil))))

;; Initialization

(defn ^:export main []
  (js/console.log "Anki app starting with Replicant...")

  ;; Set up dispatch with interpolation middleware
  (r/set-dispatch!
   (fn [event-data handler-data]
     (when (= :replicant.trigger/dom-event (:replicant/trigger event-data))
       (let [dom-event (:replicant/dom-event event-data)
             enriched-actions (interpolate-actions dom-event handler-data)]
         (handle-event event-data enriched-actions)
         ;; Re-render after handling event
         (render!)))))

  ;; Set up keyboard shortcuts
  (.addEventListener js/document "keydown" handle-keydown)

  (render!)
  (add-watch !state :render (fn [_ _ _ _] (render!)))

  ;; Watch creator state for changes (needed for image occlusion creator)
  (add-watch creator/!creator-state :render (fn [_ _ _ _] (render!)))

  ;; Load debug helpers in development
  (when ^boolean js/goog.DEBUG
    (js/console.log "🔧 Debug mode enabled. Try: checkIntegrity()")
    (set! js/window.checkIntegrity
          (fn []
            (let [s @!state
                  issues (core/check-integrity (:state s) (:events s))]
              (if (empty? issues)
                (do
                  (js/console.log "✅ No integrity issues found")
                  #js {:ok true :issues #js []})
                (do
                  (js/console.log "⚠️  Found" (count issues) "issue(s):")
                  (doseq [issue issues]
                    (let [icon (case (:severity issue)
                                 :error "❌"
                                 :warning "⚠️ "
                                 :info "ℹ️ ")]
                      (js/console.log icon (:message issue))
                      (when (:data issue)
                        (js/console.log "   Data:" (pr-str (:data issue))))))
                  #js {:ok false :issues (clj->js issues)}))))))

  ;; Auto-resume last session if available
  (p/let [saved-handle (fs/load-dir-handle)]
    (if saved-handle
      (do
        (js/console.log "Auto-resuming last session...")
        (p/catch
         (p/let [permission (.requestPermission saved-handle #js {:mode "readwrite"})]
           (if (= permission "granted")
             (p/let [result (load-and-sync-cards! saved-handle)
                     events (fs/load-log saved-handle)
                     state (:state result)
                     decks (:decks result)]
               (swap! !state assoc
                      :dir-handle saved-handle
                      :state state
                      :events events
                      :saved-handle saved-handle
                      :screen :review
                      :decks decks
                      :selected-deck nil)
               (js/console.log "Auto-resumed with" (count (:cards state)) "cards")
               (js/console.log "Available decks:" (pr-str decks)))
             (do
               (js/console.warn "Permission denied, showing setup screen")
               (swap! !state assoc :saved-handle saved-handle))))
         (fn [e]
           (js/console.error "Failed to auto-resume:" (.-message e))
           (swap! !state assoc :saved-handle saved-handle))))
      (js/console.log "No saved session, showing setup screen"))))
</file>

</files>

GOAL: Suggest simplifications that reduce LOC while maintaining all functionality.

Focus on:

1. Data structure simplification - are there redundant fields or overly nested structures?
2. State management - can event sourcing be simpler?
3. Card hashing - is the approach optimal for the use case?

Provide specific refactorings with before/after code.


[2025-10-14T20:36:15] thinking

**Proposing data structure and hashing simplifications**

I’m sketching three clear refactor suggestions: remove redundant `:card-hash` and Date fields in metadata in favor of numeric timestamps, merge event status and undo/redo stacks computations into one pass to avoid repeated traversals, and simplify card hashing by unifying it to a single cross-platform hash approach or using deterministic IDs based on file and index instead of hashing full content. I’m including before/after snippets to illustrate each idea clearly.
**Highlighting hashing stability and event status inefficiencies**

I'm weighing the trade-offs between hashing by content versus using file-plus-index IDs, noting that reordering cards can break ID stability and lose metadata. I’m also spotting an inefficiency in `compute-stats`: it recalculates event statuses repeatedly inside filters, so I plan to suggest restructuring to compute event status once upfront. I’ll finish by preparing a clear, bullet-pointed review with exact before/after code snippets for these points.
[2025-10-14T20:36:21] codex
**Highlighting hashing stability and event status inefficiencies**

I'm weighing the trade-offs between hashing by content versus using file-plus-index IDs, noting that reordering cards can break ID stability and lose metadata. I’m also spotting an inefficiency in `compute-stats`: it recalculates event statuses repeatedly inside filters, so I plan to suggest restructuring to compute event status once upfront. I’ll finish by preparing a clear, bullet-pointed review with exact before/after code snippets for these points.
[2025-10-14T20:36:21] codex

**Core Simplifications**

- `core.cljc:25` `new-card-meta` currently stores `:card-hash`, `:created-at`, and `:due-at` even though the map is keyed by hash and the timestamps are immediately converted back to millis. Removing the redundant hash and keeping everything in millis trims fields and avoids `time/date-from-ms` churn.  
  **Before**
  ```clojure
  (defn new-card-meta [h]
    {:card-hash h
     :created-at (time/date-from-ms (time/now-ms))
     :due-at (time/date-from-ms (time/now-ms))
     :reviews 0})
  ```
  **After**
  ```clojure
  (defn new-card-meta []
    {:created-ms (time/now-ms)
     :due-ms (time/now-ms)
     :reviews 0})
  ```
  Callers then store the hash only as the map key (`(assoc-in state [:meta h] (new-card-meta))`), and `schedule-card` just updates `:due-ms` / `:last-review-ms`. This removes two fields per card, drops several `(.getTime …)` calls, and makes `due-cards` a straight numeric compare:
  ```clojure
  (<= (:due-ms card-meta) (time/now-ms))
  ```

- `core.cljc:206` `reduce-events` walks the log three separate times (`build-event-status-map`, filter for active events, then `build-undo-redo-stacks`). You can collapse that into a single `reduce` that accumulates active review/card-created events, undo/redo stacks, and a status map in one pass—about 20 fewer lines and less work each render.  
  **Before**
  ```clojure
  (defn reduce-events [events]
    (let [event-status (build-event-status-map events)
          active-events (filter ... events)
          {:keys [undo-stack redo-stack]} (build-undo-redo-stacks events event-status)
          base-state (reduce apply-event {:cards {} :meta {}} active-events)]
      (merge base-state {:undo-stack undo-stack :redo-stack redo-stack})))
  ```
  **After**
  ```clojure
  (defn reduce-events [events]
    (let [{:keys [active undo redo status]}
          (reduce
            (fn [{:keys [active undo redo status] :as acc} event]
              (case (:event/type event)
                :undo (let [target (get-in event [:event/data :target-event-id])]
                        (-> acc
                            (assoc-in [:status target] :undone)
                            (update :undo #(vec (remove #{target} %)))
                            (update :redo conj target)))
                :redo (let [target (get-in event [:event/data :target-event-id])]
                        (-> acc
                            (assoc-in [:status target] :active)
                            (update :undo conj target)
                            (update :redo #(vec (remove #{target} %)))))
                ;; fallthrough: review/card-created candidates
                (if (= :active (get status (:event/id event) :active))
                  (update acc :active conj event)
                  acc)))
            {:active [] :undo [] :redo [] :status {}}
            events)
          state (reduce apply-event {:cards {} :meta {}} active)]
      (assoc state
             :undo-stack undo
             :redo-stack redo)))
  ```
  `compute-stats` can now reuse the returned `:status` if you include it in the return map, eliminating the double `build-event-status-map` call there as well.

- `ui.cljs:310` Each generated occlusion card stores the full `:occlusions` vector even though it only needs its own region plus count for redraws. Replace the bulk copy with a lean summary.  
  **Before**
  ```clojure
  {:type :image-occlusion/item
   :asset (:asset occlusion-card)
   :prompt (:prompt occlusion-card)
   :mode mode
   :occlusions occlusions
   :current-oid (:oid occ)
   :answer (:answer occ)
   :shape (:shape occ)}
  ```
  **After**
  ```clojure
  (let [siblings-count (count occlusions)]
    {:type :image-occlusion/item
     :asset (:asset occlusion-card)
     :prompt (:prompt occlusion-card)
     :mode mode
     :occlusion (:shape occ)
     :answer (:answer occ)
     :current-oid (:oid occ)
     :occlusion-count siblings-count})
  ```
  The renderer then grabs `:occlusion` instead of indexing into `:occlusions`. This drops one large vector copy per virtual card (big win when exporting tens of occlusions) while keeping the UI logic intact—if you need the full list to style “other” masks, pass only the minimal shape data you actually use (e.g., `[:x :y :w :h]` pairs).

**Card Hashing**

- `core.cljc:63` `card-hash` uses separate CLJ/CLJS branches and `MessageDigest` allocations. For this app a deterministic hash of the canonical string is enough and much shorter.  
  **Before**
  ```clojure
  #?(:clj (let [digest (java.security.MessageDigest/getInstance "SHA-256") …]
            …)
     :cljs (let [s (pr-str card-data)]
             (str (cljs.core/hash s))))
  ```
  **After**
  ```clojure
  (defn card-hash [card-data]
    (-> card-data
        (update-keys identity) ; ensure map keys are realized
        (pr-str)
        (hash)
        (Long/toUnsignedString 36)))
  ```
  `Long/toUnsignedString` (or plain `str`) gives a consistent identifier across runtimes, spares the Java interop, and cuts the function down to three lines. Because `hash` already normalizes maps by key order, metadata stays stable as long as the card content is unchanged; collisions are vanishingly unlikely for 36-bit space in this small app, and you can keep a guard clause in `load-and-sync-cards!` if you want to detect them.

**Next steps**  
1. Apply the meta/event simplifications first (small footprint, easier to reason about).  
2. Update the occlusion save path and renderer together so they agree on the trimmed shape data.  
3. Run `npm run check` to ensure the CLJC changes compile on both targets and that the UI still renders occlusions correctly.

Tests: not run.
[2025-10-14T20:36:49] tokens used: 31,853
