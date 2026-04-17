(ns servers.dev-diagnostics
  "Evo live MCP server — exposes the intent registry + FR catalog to agents.

   Transport: newline-delimited JSON-RPC 2.0 over stdio (MCP 2024-11-05).
   Scope v1: read-only introspection. No nREPL bridge, no dispatch.

   Tools:
     list-intents      — all registered intents + FR citations
     describe-intent   — full Malli spec, doc, allowed-states for one intent
     list-frs          — functional requirement IDs, with filters
     describe-fr       — full metadata for one FR

   Loading plugins.manifest here triggers side-effect registration of every
   editor intent via register-intent!, so @intent/!intents is populated by the
   time -main runs."
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [kernel.intent :as intent]
            [spec.registry :as fr]
            plugins.manifest)
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]))

(defn- log [& args]
  (binding [*out* *err*] (apply println "[evo-mcp]" args)))

(defn- kw [x]
  (cond
    (keyword? x) x
    (string? x) (keyword (str/replace x #"^:" ""))
    :else nil))

;; ── Tool handlers ─────────────────────────────────────────────────────────────

(defn- intent-row [[k cfg]]
  (cond-> {:name (subs (str k) 1)
           :doc (:doc cfg)
           :fr-ids (mapv #(subs (str %) 1) (:fr/ids cfg))}
    (contains? cfg :allowed-states)
    (assoc :allowed-states (mapv #(subs (str %) 1) (:allowed-states cfg)))))

(defn- tool:list-intents [_]
  (let [rows (mapv intent-row (sort-by (comp str key) @intent/!intents))]
    {:count (count rows) :intents rows}))

(defn- tool:describe-intent [{:keys [name]}]
  (let [k (kw name)
        cfg (get @intent/!intents k)]
    (if cfg
      {:name (subs (str k) 1)
       :doc (:doc cfg)
       :spec (with-out-str (pp/pprint (:spec cfg)))
       :fr-ids (mapv #(subs (str %) 1) (:fr/ids cfg))
       :allowed-states (when (contains? cfg :allowed-states)
                         (mapv #(subs (str %) 1) (:allowed-states cfg)))}
      {:error (str "No intent registered for " (pr-str name))
       :available (mapv #(subs (str %) 1) (sort (keys @intent/!intents)))})))

(defn- tool:list-frs [{:keys [priority type status tag]}]
  (let [filters (cond-> {}
                  priority (assoc :priority (kw priority))
                  type     (assoc :type (kw type))
                  status   (assoc :status (kw status))
                  tag      (assoc :tag (kw tag)))
        ids (if (seq filters) (fr/list-frs filters) (fr/list-frs))]
    {:count (count ids)
     :frs (mapv #(subs (str %) 1) (sort ids))}))

(defn- tool:describe-fr [{:keys [id]}]
  (let [k (kw id)
        entry (fr/get-fr k)]
    (if entry
      {:id (subs (str k) 1)
       :entry (with-out-str (pp/pprint entry))}
      {:error (str "No FR registered for " (pr-str id))})))

(def ^:private tools
  {"list-intents"
   {:handler #'tool:list-intents
    :description "List every registered editor intent with FR citations and allowed states."
    :input-schema {:type "object" :properties {} :additionalProperties false}}

   "describe-intent"
   {:handler #'tool:describe-intent
    :description "Return the Malli spec, doc, FR citations, and allowed states for one intent."
    :input-schema {:type "object"
                   :properties {:name {:type "string"
                                       :description "Intent keyword without the leading colon, e.g. 'indent'"}}
                   :required ["name"]
                   :additionalProperties false}}

   "list-frs"
   {:handler #'tool:list-frs
    :description "List Functional Requirement IDs. Optional filters: priority, type, status, tag."
    :input-schema {:type "object"
                   :properties {:priority {:type "string"
                                           :enum ["critical" "high" "medium" "low"]}
                                :type {:type "string"
                                       :enum ["intent-level" "invariant" "scenario"]}
                                :status {:type "string"
                                         :enum ["active" "deprecated" "future"]}
                                :tag {:type "string"
                                      :description "Tag keyword name, e.g. 'navigation'"}}
                   :additionalProperties false}}

   "describe-fr"
   {:handler #'tool:describe-fr
    :description "Return full metadata for one Functional Requirement."
    :input-schema {:type "object"
                   :properties {:id {:type "string"
                                     :description "FR id without leading colon, e.g. 'fr.edit/smart-split'"}}
                   :required ["id"]
                   :additionalProperties false}}})

;; ── JSON-RPC plumbing ─────────────────────────────────────────────────────────

(defn- as-text [x]
  {:content [{:type "text"
              :text (if (string? x) x (with-out-str (pp/pprint x)))}]})

(defn- handle [{:keys [method params id]}]
  (case method
    "initialize"
    {:jsonrpc "2.0" :id id
     :result {:protocolVersion "2024-11-05"
              :capabilities {:tools {}}
              :serverInfo {:name "evo-live" :version "0.1.0"}}}

    "notifications/initialized" nil
    "notifications/cancelled"   nil

    "tools/list"
    {:jsonrpc "2.0" :id id
     :result {:tools (mapv (fn [[n t]]
                             {:name n
                              :description (:description t)
                              :inputSchema (:input-schema t)})
                           tools)}}

    "tools/call"
    (let [{tool-name :name args :arguments} params
          handler (get-in tools [tool-name :handler])]
      (if handler
        (try
          {:jsonrpc "2.0" :id id :result (as-text (handler (or args {})))}
          (catch Throwable t
            (log "tool-error" tool-name "->" (ex-message t))
            {:jsonrpc "2.0" :id id
             :error {:code -32000 :message (or (ex-message t) (str t))}}))
        {:jsonrpc "2.0" :id id
         :error {:code -32601 :message (str "Unknown tool: " tool-name)}}))

    ;; Fall-through: only respond if this was a request (has id).
    (when id
      {:jsonrpc "2.0" :id id
       :error {:code -32601 :message (str "Method not found: " method)}})))

(defn- emit! [^java.io.Writer w msg]
  (.write w (json/write-str msg))
  (.write w "\n")
  (.flush w))

(defn -main [& _]
  (log "starting; loading FR registry...")
  (fr/load-registry!)
  (log (format "ready — %d intents, %d FRs"
               (count @intent/!intents)
               (count (fr/list-frs))))
  (let [in  (BufferedReader. (InputStreamReader. System/in "UTF-8"))
        out (OutputStreamWriter. System/out "UTF-8")]
    (loop []
      (when-let [line (.readLine in)]
        (when-not (str/blank? line)
          (when-let [req (try (json/read-str line :key-fn keyword)
                              (catch Throwable t
                                (log "parse-error" (ex-message t)) nil))]
            (when-let [resp (handle req)]
              (emit! out resp))))
        (recur)))))
