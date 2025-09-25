;; Session Persistence for Analysis States
;; Save and restore analysis sessions, configurations, and tool states

(ns agent.session-persistence
  "Session persistence for saving and loading analysis states.

  Features:
  - Save complete analysis sessions
  - Restore previous analysis states
  - Session metadata and tagging
  - Automatic session recovery
  - Session history and management
  - Export/import capabilities"
  (:require [agent.enhanced-explorer :as explorer]
            [agent.configuration :as config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; Session storage
(def ^:private sessions-dir ".agent-sessions")
(def ^:private current-session (atom nil))
(def ^:private session-history (atom []))

;; Session structure
(defn- create-session-snapshot
  "Create a comprehensive session snapshot"
  [session-name description]
  (let [db @(requiring-resolve 'evolver.kernel/db)
        timestamp (System/currentTimeMillis)]
    {:id (str (random-uuid))
     :name session-name
     :description description
     :timestamp timestamp
     :version "1.0"
     :metadata {:user (System/getProperty "user.name")
                :hostname (try (.getHostName (java.net.InetAddress/getLocalHost))
                               (catch Exception _ "unknown"))
                :clojure-version (clojure-version)
                :java-version (System/getProperty "java.version")}

     ;; System state
     :system-state {:node-count (count (:nodes db))
                    :tx-count (count (:tx-log db))
                    :ref-count (count (:references db))
                    :selected-count (count (:selected (:view db)))
                    :error-count (count (filter #(= (:level %) :error) (:log-history db)))
                    :undo-count (count (:undo-stack db))
                    :redo-count (count (:redo-stack db))}

     ;; Configuration
     :configuration {:profile @config/*current-profile*
                     :settings (config/get-config)
                     :overrides @config/*config-overrides*}

     ;; Analysis state
     :analysis-state {:last-analysis-results nil ; Will be populated by analysis functions
                      :active-monitors (keys @(requiring-resolve 'agent.monitoring/*monitors*))
                      :alert-count (count @(requiring-resolve 'agent.monitoring/*alerts*))}

     ;; Tool state
     :tool-state {:usage-history @(requiring-resolve 'agent.context-help/*tool-usage-history*)
                  :pipeline-registry (keys @(requiring-resolve 'agent.tool-composer/*pipeline-registry*))}

     ;; Database snapshot (optional, can be large)
     :database-snapshot (when (config/get-config :session-include-db)
                         {:nodes (:nodes db)
                          :children-by-parent (:children-by-parent db)
                          :references (:references db)
                          :view (:view db)})}))

;; File operations
(defn- ensure-sessions-dir
  "Ensure the sessions directory exists"
  []
  (let [dir (io/file sessions-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn- session-file-path
  "Get the file path for a session"
  [session-name]
  (str sessions-dir "/" (str/replace session-name #"[^a-zA-Z0-9_-]" "_") ".edn"))

(defn- save-session-to-file
  "Save a session to file"
  [session]
  (ensure-sessions-dir)
  (let [file-path (session-file-path (:name session))]
    (try
      (spit file-path (pr-str session))
      (println (explorer/ansi-color :green (str "💾 Session saved: " file-path)))
      true
      (catch Exception e
        (println (explorer/ansi-color :red (str "❌ Failed to save session: " (.getMessage e))))
        false))))

(defn- load-session-from-file
  "Load a session from file"
  [session-name]
  (let [file-path (session-file-path session-name)]
    (when (.exists (io/file file-path))
      (try
        (edn/read-string (slurp file-path))
        (catch Exception e
          (println (explorer/ansi-color :red (str "❌ Failed to load session: " (.getMessage e))))
          nil)))))

;; Session management
(defn save-session
  "Save the current analysis session"
  [session-name & {:keys [description auto-save?]}]
  (let [description (or description (str "Session saved at " (java.util.Date.)))
        session (create-session-snapshot session-name description)]

    ;; Save to file
    (when (save-session-to-file session)
      ;; Update current session
      (reset! current-session session)

      ;; Add to history
      (swap! session-history conj {:name session-name
                                   :timestamp (:timestamp session)
                                   :description description})

      ;; Auto-save configuration if enabled
      (when (or auto-save? (config/get-config :auto-save-sessions))
        (config/save-config))

      (println (explorer/ansi-color :green (str "✅ Session '" session-name "' saved successfully")))
      session)))

(defn load-session
  "Load a saved session"
  [session-name]
  (if-let [session (load-session-from-file session-name)]
    (do
      ;; Restore configuration
      (when (:profile (:configuration session))
        (config/set-profile! (:profile (:configuration session))))

      (when (:settings (:configuration session))
        (doseq [[key value] (:settings (:configuration session))]
          (config/set-config! key value)))

      ;; Set as current session
      (reset! current-session session)

      (println (explorer/ansi-color :green (str "📂 Session '" session-name "' loaded")))
      (println (explorer/format-key-value "Saved" (java.util.Date. (:timestamp session))))
      (println (explorer/format-key-value "Description" (:description session)))

      ;; Show system state comparison
      (let [current-db @(requiring-resolve 'evolver.kernel/db)
            saved-state (:system-state session)
            current-state {:node-count (count (:nodes current-db))
                           :tx-count (count (:tx-log current-db))
                           :ref-count (count (:references current-db))
                           :selected-count (count (:selected (:view current-db)))
                           :error-count (count (filter #(= (:level %) :error) (:log-history current-db)))
                           :undo-count (count (:undo-stack current-db))
                           :redo-count (count (:redo-stack current-db))}]

        (println "\n" (explorer/format-header "🔄 STATE COMPARISON" :level 2 :color :cyan))
        (doseq [[key saved-val] saved-state]
          (let [current-val (get current-state key 0)
                changed? (not= saved-val current-val)
                indicator (if changed? (explorer/ansi-color :yellow "→") "=")]
            (println (str (explorer/format-key-value (name key) (str saved-val " " indicator " " current-val))
                          (when changed? (explorer/ansi-color :yellow " (changed)")))))))

      session)
    (do
      (println (explorer/ansi-color :red (str "❌ Session '" session-name "' not found")))
      nil)))

(defn delete-session
  "Delete a saved session"
  [session-name]
  (let [file-path (session-file-path session-name)]
    (if (.exists (io/file file-path))
      (try
        (io/delete-file file-path)
        (swap! session-history (fn [history] (remove #(= (:name %) session-name) history)))
        (when (= (:name @current-session) session-name)
          (reset! current-session nil))
        (println (explorer/ansi-color :green (str "🗑️  Session '" session-name "' deleted")))
        true
        (catch Exception e
        (println (explorer/ansi-color :red (str "❌ Failed to delete session: " (.getMessage e))))
        false)))
      (do
        (println (explorer/ansi-color :red (str "❌ Session '" session-name "' not found")))
        false))))

(defn list-sessions
  "List all saved sessions"
  []
  (ensure-sessions-dir)
  (let [session-files (.listFiles (io/file sessions-dir))
        sessions (when session-files
                   (sort-by :timestamp >
                            (keep (fn [file]
                                    (when (str/ends-with? (.getName file) ".edn")
                                      (try
                                        (let [session (edn/read-string (slurp file))]
                                          {:name (:name session)
                                           :timestamp (:timestamp session)
                                           :description (:description session)
                                           :file (.getName file)})
                                        (catch Exception _ nil))))
                                  session-files)))]
    (if (seq sessions)
      (do
        (println (explorer/format-header "💾 SAVED SESSIONS" :level 1 :color :cyan))
        (println (explorer/format-table ["Session Name" "Saved At" "Description"]
                                        (mapv (fn [session]
                                                [(:name session)
                                                 (java.util.Date. (:timestamp session))
                                                 (or (:description session) "No description")])
                                              sessions)
                                        :alignments [:left :left :left])))
      (println (explorer/ansi-color :yellow "No saved sessions found")))))

(defn session-info
  "Get detailed information about a session"
  [session-name]
  (if-let [session (load-session-from-file session-name)]
    (do
      (println (explorer/format-header (str "📋 SESSION INFO: " session-name) :level 1 :color :cyan))

      ;; Basic info
      (println (explorer/format-header "📄 BASIC INFORMATION" :level 2 :color :blue))
      (println (explorer/format-key-value "Name" (:name session)))
      (println (explorer/format-key-value "Description" (:description session)))
      (println (explorer/format-key-value "Saved" (java.util.Date. (:timestamp session))))
      (println (explorer/format-key-value "Version" (:version session)))

      ;; Metadata
      (when (:metadata session)
        (println "\n" (explorer/format-header "🏷️ METADATA" :level 2 :color :green))
        (doseq [[key value] (:metadata session)]
          (println (explorer/format-key-value (name key) value))))

      ;; System state
      (when (:system-state session)
        (println "\n" (explorer/format-header "📊 SYSTEM STATE" :level 2 :color :yellow))
        (doseq [[key value] (:system-state session)]
          (println (explorer/format-key-value (name key) value))))

      ;; Configuration
      (when (:configuration session)
        (println "\n" (explorer/format-header "⚙️ CONFIGURATION" :level 2 :color :magenta))
        (println (explorer/format-key-value "Profile" (:profile (:configuration session))))
        (println (explorer/format-key-value "Settings Count" (count (:settings (:configuration session))))))

      session)
    (do
      (println (explorer/ansi-color :red (str "❌ Session '" session-name "' not found")))
      nil)))

;; Auto-save functionality
(defn auto-save-current-session
  "Auto-save the current session with a timestamped name"
  []
  (when (config/get-config :auto-save-sessions)
    (let [timestamp (.format (java.text.SimpleDateFormat. "yyyy-MM-dd_HH-mm-ss")
                             (java.util.Date.))
          session-name (str "auto-save-" timestamp)]
      (save-session session-name :description "Automatic session save" :auto-save? false))))

;; Session comparison and diffing
(defn compare-sessions
  "Compare two saved sessions"
  [session1-name session2-name]
  (let [session1 (load-session-from-file session1-name)
        session2 (load-session-from-file session2-name)]
    (if (and session1 session2)
      (do
        (println (explorer/format-header (str "🔍 COMPARING SESSIONS") :level 1 :color :cyan))
        (println (str (explorer/ansi-color :blue session1-name) " vs " (explorer/ansi-color :green session2-name)))

        ;; Time comparison
        (let [time-diff (- (:timestamp session2) (:timestamp session1))
              time-diff-str (if (pos? time-diff)
                              (str "+" (format "%.1f" (/ time-diff 1000.0)) "s")
                              (str (format "%.1f" (/ time-diff 1000.0)) "s"))]
          (println (explorer/format-key-value "Time Difference" time-diff-str)))

        ;; System state comparison
        (let [state1 (:system-state session1)
              state2 (:system-state session2)]
          (println "\n" (explorer/format-header "📊 SYSTEM STATE CHANGES" :level 2 :color :yellow))
          (doseq [key (keys state1)]
            (let [val1 (get state1 key 0)
                  val2 (get state2 key 0)
                  diff (- val2 val1)
                  changed? (not= val1 val2)]
              (println (str (explorer/format-key-value (name key)
                                                      (str val1 " → " val2
                                                           (when (not= diff 0)
                                                             (str " (" (if (pos? diff) "+" "") diff ")"))))
                            (when changed?
                              (if (pos? diff)
                                (explorer/ansi-color :green " ↑")
                                (explorer/ansi-color :red " ↓"))))))))

        {:session1 session1
         :session2 session2
         :time-diff (- (:timestamp session2) (:timestamp session1))})
      (do
        (when-not session1
          (println (explorer/ansi-color :red (str "❌ Session '" session1-name "' not found"))))
        (when-not session2
          (println (explorer/ansi-color :red (str "❌ Session '" session2-name "' not found"))))
        nil))))

;; Session export/import
(defn export-session
  "Export a session to a portable format"
  [session-name file-path]
  (if-let [session (load-session-from-file session-name)]
    (try
      (spit file-path (pr-str session))
      (println (explorer/ansi-color :green (str "📤 Session exported to: " file-path)))
      true
      (catch Exception e
        (println (explorer/ansi-color :red (str "❌ Export failed: " (.getMessage e))))
        false))
    (do
      (println (explorer/ansi-color :red (str "❌ Session '" session-name "' not found")))
      false)))

(defn import-session
  "Import a session from a file"
  [file-path new-name]
  (if (.exists (io/file file-path))
    (try
      (let [session (edn/read-string (slurp file-path))
            imported-session (assoc session :name new-name :timestamp (System/currentTimeMillis))]
        (save-session-to-file imported-session)
        (println (explorer/ansi-color :green (str "📥 Session imported as: " new-name)))
        imported-session)
      (catch Exception e
        (println (explorer/ansi-color :red (str "❌ Import failed: " (.getMessage e))))
        nil))
    (do
      (println (explorer/ansi-color :red (str "❌ File not found: " file-path)))
      nil)))

;; Session recovery
(defn recover-last-session
  "Recover the most recently saved session"
  []
  (let [sessions (sort-by :timestamp > @session-history)]
    (if (seq sessions)
      (let [last-session (first sessions)]
        (println (explorer/ansi-color :blue (str "🔄 Recovering last session: " (:name last-session))))
        (load-session (:name last-session)))
      (do
        (println (explorer/ansi-color :yellow "⚠️  No previous sessions to recover"))
        nil))))

(defn cleanup-old-sessions
  "Clean up sessions older than specified days"
  [days]
  (let [cutoff-time (- (System/currentTimeMillis) (* days 24 60 60 1000))
        old-sessions (filter #(< (:timestamp %) cutoff-time) @session-history)]
    (if (seq old-sessions)
      (do
        (doseq [session old-sessions]
          (delete-session (:name session)))
        (println (explorer/ansi-color :green (str "🧹 Cleaned up " (count old-sessions) " old sessions"))))
      (println (explorer/ansi-color :yellow "No old sessions to clean up")))))

;; Current session management
(defn get-current-session
  "Get information about the current session"
  []
  (if @current-session
    (do
      (println (explorer/format-header "🎯 CURRENT SESSION" :level 1 :color :cyan))
      (println (explorer/format-key-value "Name" (:name @current-session)))
      (println (explorer/format-key-value "Description" (:description @current-session)))
      (println (explorer/format-key-value "Started" (java.util.Date. (:timestamp @current-session))))
      @current-session)
    (do
      (println (explorer/ansi-color :yellow "⚠️  No active session"))
      nil)))

(defn end-session
  "End the current session (optionally save first)"
  [& {:keys [save-first?]}]
  (when (and save-first? @current-session)
    (save-session (:name @current-session) :description (str "Session ended - " (:description @current-session))))
  (reset! current-session nil)
  (println (explorer/ansi-color :green "👋 Session ended")))

;; Export main functions
(def save save-session)
(def load load-session)
(def delete delete-session)
(def list list-sessions)
(def info session-info)
(def compare compare-sessions)
(def current get-current-session)
(def end end-session)
(def export export-session)
(def import import-session)
(def recover recover-last-session)
(def cleanup cleanup-old-sessions)