(ns session-memory.session
  "Core session reading and parsing for Claude Code sessions"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; =============================================================================
;; Session Discovery
;; =============================================================================

(defn claude-projects-dir
  "Get Claude Code projects directory"
  []
  (or (System/getenv "CLAUDE_PROJECTS_DIR")
      (str (System/getProperty "user.home") "/.claude/projects")))

(defn current-project-hash
  "Get project hash for current working directory"
  []
  (let [cwd (System/getProperty "user.dir")
        hash-name (-> cwd
                      (str/replace "/" "-")
                      (str/replace-first "-" ""))]
    hash-name))

(defn project-sessions-dir
  "Get sessions directory for current or specified project"
  ([] (project-sessions-dir (current-project-hash)))
  ([project-hash]
   (str (claude-projects-dir) "/" project-hash)))

(defn list-session-files
  "List all session JSONL files for project, sorted by modification time"
  ([] (list-session-files (current-project-hash)))
  ([project-hash]
   (let [dir (io/file (project-sessions-dir project-hash))]
     (if (.exists dir)
       (->> (.listFiles dir)
            (filter #(str/ends-with? (.getName %) ".jsonl"))
            (sort-by #(.lastModified %) >)
            (map #(.getAbsolutePath %)))
       []))))

(defn list-sessions
  "List sessions with metadata"
  ([] (list-sessions {}))
  ([{:keys [all? limit] :or {all? false limit nil}}]
   (let [projects (if all?
                    (->> (io/file (claude-projects-dir))
                         .listFiles
                         (filter #(.isDirectory %))
                         (map #(.getName %)))
                    [(current-project-hash)])
         sessions (->> projects
                       (mapcat (fn [project]
                                 (map (fn [path]
                                        {:id (-> path io/file .getName (str/replace #"\.jsonl$" ""))
                                         :project project
                                         :path path
                                         :modified (.lastModified (io/file path))
                                         :size (.length (io/file path))})
                                      (list-session-files project))))
                       (sort-by :modified >))]
     (if limit
       (take limit sessions)
       sessions))))

;; =============================================================================
;; Session Reading
;; =============================================================================

(defn read-session-raw
  "Read raw JSONL session file, returning vector of parsed JSON objects"
  [session-id]
  (let [path (str (project-sessions-dir) "/" session-id ".jsonl")]
    (when (.exists (io/file path))
      (->> (io/reader path)
           line-seq
           (map #(json/parse-string % true))
           vec))))

(defn extract-text-content
  "Extract text from content block"
  [block]
  (condp = (:type block)
    "text" (:text block)
    "thinking" (str "[thinking: " (subs (:thinking block) 0 (min 100 (count (:thinking block)))) "...]")
    "tool_use" (str "[tool: " (:name block) "]")
    "tool_result" "[tool-result]"
    (str "[" (:type block) "]")))

(defn parse-message-content
  "Parse message content blocks into readable text"
  [content]
  (cond
    (string? content) content
    (vector? content) (->> content
                           (map extract-text-content)
                           (remove nil?)
                           (str/join " "))
    :else (str content)))

(defn extract-messages
  "Extract user/assistant messages from raw session data"
  [session-data & {:keys [role show-tools?] :or {show-tools? false}}]
  (->> session-data
       (filter #(contains? #{"user" "assistant"} (:type %)))
       (map (fn [{:keys [type message uuid parentUuid timestamp]}]
              (let [msg-role (:role message)
                    content (:content message)]
                {:uuid uuid
                 :parent parentUuid
                 :role msg-role
                 :timestamp timestamp
                 :content (if (and (vector? content) (not show-tools?))
                           (->> content
                                (filter #(= (:type %) "text"))
                                (map :text)
                                (str/join " "))
                           (parse-message-content content))})))
       (filter (fn [msg]
                 (if role
                   (= (:role msg) (name role))
                   true)))))

(defn read-session
  "Read session and return structured messages"
  [session-id & {:keys [limit role show-tools?] :or {limit nil role nil show-tools? false}}]
  (when-let [raw (read-session-raw session-id)]
    (cond-> (extract-messages raw :role role :show-tools? show-tools?)
      limit (take limit))))

;; =============================================================================
;; Tool Extraction
;; =============================================================================

(defn extract-tool-uses
  "Extract all tool uses from session"
  [session-data]
  (->> session-data
       (filter #(= "assistant" (:type %)))
       (mapcat (fn [{:keys [message timestamp uuid]}]
                 (let [content (:content message)]
                   (when (vector? content)
                     (->> content
                          (filter #(= "tool_use" (:type %)))
                          (map (fn [tool]
                                 {:tool-name (:name tool)
                                  :tool-input (:input tool)
                                  :tool-id (:id tool)
                                  :message-uuid uuid
                                  :timestamp timestamp})))))))
       (remove nil?)))

(defn extract-tool-results
  "Extract all tool results from session"
  [session-data]
  (->> session-data
       (filter #(= "user" (:type %)))
       (mapcat (fn [{:keys [message timestamp uuid parentUuid]}]
                 (let [content (:content message)]
                   (when (vector? content)
                     (->> content
                          (filter #(= "tool_result" (:type %)))
                          (map (fn [result]
                                 {:tool-use-id (:tool_use_id result)
                                  :content (:content result)
                                  :is-error (get result :is_error false)
                                  :message-uuid uuid
                                  :parent-uuid parentUuid
                                  :timestamp timestamp})))))))
       (remove nil?)))

(defn tool-usage-stats
  "Get tool usage statistics for session"
  [session-id]
  (when-let [raw (read-session-raw session-id)]
    (let [tool-uses (extract-tool-uses raw)
          tool-results (extract-tool-results raw)
          results-by-id (group-by :tool-use-id tool-results)]
      (->> tool-uses
           (map (fn [{:keys [tool-name tool-id] :as use}]
                  (let [result (first (get results-by-id tool-id))
                        success? (and result (not (:is-error result)))]
                    {:tool tool-name
                     :success? success?
                     :use use
                     :result result})))
           (group-by :tool)
           (map (fn [[tool uses]]
                  {:tool tool
                   :count (count uses)
                   :success (count (filter :success? uses))
                   :failed (count (remove :success? uses))
                   :success-rate (if (pos? (count uses))
                                   (/ (count (filter :success? uses)) (count uses))
                                   0.0)}))
           (sort-by :count >)
           vec))))

;; =============================================================================
;; Message Threading
;; =============================================================================

(defn build-thread-tree
  "Build conversation tree from messages"
  [messages]
  (let [by-uuid (into {} (map (fn [m] [(:uuid m) m]) messages))
        roots (filter #(nil? (:parent %)) messages)]
    (letfn [(build-node [msg]
              (let [children (->> messages
                                  (filter #(= (:parent %) (:uuid msg)))
                                  (map build-node))]
                (if (seq children)
                  (assoc msg :children children)
                  msg)))]
      (map build-node roots))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn session-info
  "Get session metadata"
  [session-id]
  (when-let [raw (read-session-raw session-id)]
    (let [messages (extract-messages raw)
          tools (extract-tool-uses raw)]
      {:id session-id
       :message-count (count messages)
       :user-messages (count (filter #(= (:role %) "user") messages))
       :assistant-messages (count (filter #(= (:role %) "assistant") messages))
       :tool-uses (count tools)
       :unique-tools (count (distinct (map :tool-name tools)))
       :first-message (-> messages first :timestamp)
       :last-message (-> messages last :timestamp)})))

(defn messages
  "Get messages from session with optional filtering"
  [session-id & opts]
  (apply read-session session-id opts))

(defn tools
  "Get tool usage from session"
  [session-id]
  (when-let [raw (read-session-raw session-id)]
    (extract-tool-uses raw)))

(defn thread-tree
  "Get conversation tree for session"
  [session-id]
  (when-let [msgs (read-session session-id)]
    (build-thread-tree msgs)))

(comment
  ;; Examples
  (list-sessions)
  (list-sessions {:all? true :limit 5})

  (session-info "0c7b3880-e100-49c2-983b-1aa4ff2bb82e")

  (take 3 (messages "0c7b3880-e100-49c2-983b-1aa4ff2bb82e"))
  (take 3 (messages "0c7b3880-e100-49c2-983b-1aa4ff2bb82e" :role "user"))

  (tool-usage-stats "0c7b3880-e100-49c2-983b-1aa4ff2bb82e")

  (thread-tree "0c7b3880-e100-49c2-983b-1aa4ff2bb82e")
  )
