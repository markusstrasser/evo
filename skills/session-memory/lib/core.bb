;; Session Memory Core Library - Babashka preload

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[cheshire.core :as json])

;; =============================================================================
;; Session Discovery
;; =============================================================================

(defn claude-projects-dir []
  (or (System/getenv "CLAUDE_PROJECTS_DIR")
      (str (System/getProperty "user.home") "/.claude/projects")))

(defn current-project-hash []
  (let [cwd (System/getProperty "user.dir")
        hash-name (str/replace cwd "/" "-")]
    hash-name))

(defn project-sessions-dir
  ([] (project-sessions-dir (current-project-hash)))
  ([project-hash]
   (str (claude-projects-dir) "/" project-hash)))

(defn list-session-files
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

(defn read-session-raw [session-id]
  (let [path (str (project-sessions-dir) "/" session-id ".jsonl")]
    (when (.exists (io/file path))
      (->> (io/reader path)
           line-seq
           (map #(json/parse-string % true))
           vec))))

(defn extract-text-content [block]
  (condp = (:type block)
    "text" (:text block)
    "thinking" (str "[thinking: " (subs (:thinking block) 0 (min 100 (count (:thinking block)))) "...]")
    "tool_use" (str "[tool: " (:name block) "]")
    "tool_result" "[tool-result]"
    (str "[" (:type block) "]")))

(defn parse-message-content [content]
  (cond
    (string? content) content
    (vector? content) (->> content
                           (map extract-text-content)
                           (remove nil?)
                           (str/join " "))
    :else (str content)))

(defn extract-messages [session-data & {:keys [role show-tools?] :or {show-tools? false}}]
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

(defn read-session [session-id & {:keys [limit role show-tools?] :or {limit nil role nil show-tools? false}}]
  (when-let [raw (read-session-raw session-id)]
    (cond-> (extract-messages raw :role role :show-tools? show-tools?)
      limit (take limit))))

;; =============================================================================
;; Tool Extraction
;; =============================================================================

(defn extract-tool-uses [session-data]
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

(defn extract-tool-results [session-data]
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

(defn tool-usage-stats [session-id]
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
                                   (double (/ (count (filter :success? uses)) (count uses)))
                                   0.0)}))
           (sort-by :count >)
           vec))))

(defn session-info [session-id]
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
