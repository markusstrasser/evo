(ns servers.dev-diagnostics
  "Evo live MCP server — exposes the intent registry + FR catalog to agents.

   Transport: newline-delimited JSON-RPC 2.0 over stdio (MCP 2024-11-05).
   Scope:
     v1 (JVM-local, no browser required):
       list-intents, describe-intent, list-frs, describe-fr
     v2 (bridges to shadow-cljs :55449 / :blocks-ui — browser must be attached):
       query-db, snapshot-db, dispatch-intent, eval-cljs

   Loading plugins.manifest here triggers side-effect registration of every
   editor intent via register-intent!, so @intent/!intents is populated by the
   time -main runs. v2 tools validate intents against this same registry on
   the JVM side before sending them to the CLJS runtime — so `describe-intent`
   and `dispatch-intent` agree on what a valid intent looks like."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [kernel.intent :as intent]
            [servers.nrepl-bridge :as bridge]
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

;; ── v2: live browser bridge ───────────────────────────────────────────────────
;; These tools round-trip through shadow-cljs nREPL into the :blocks-ui runtime.
;; If no browser is attached, bridge returns {:ok? false :error ...} — we surface
;; that verbatim so the agent can tell the difference between "no editor open"
;; and "bad intent".

(def ^:private query-db-cljs
  ;; Returns a small top-level sketch, never the full db. Agents that want
  ;; deeper probing can use eval-cljs with a targeted expression.
  "(let [db @shell.editor/!db
         vs @shell.view-state/!view-state
         sketch (fn [v]
                  (cond
                    (map? v) {:map-keys (vec (keys v)) :size (count v)}
                    (coll? v) {:count (count v) :sample (vec (take 3 v))}
                    :else v))]
     {:db/hash (hash db)
      :db/top-level (into {} (map (fn [[k v]] [k (sketch v)]) db))
      :view-state/mode (:mode vs)
      :view-state/selection (:selection vs)
      :view-state/top-level (into {} (map (fn [[k v]] [k (sketch v)]) vs))})")

(defn- tool:query-db [_]
  (bridge/cljs-eval query-db-cljs))

(defn- tool:snapshot-db [_]
  (bridge/cljs-eval
    "{:db/hash (hash @shell.editor/!db)
      :view-state/hash (hash @shell.view-state/!view-state)}"))

(defn- tool:dispatch-intent [{:keys [intent]}]
  (let [parsed (try (edn/read-string intent)
                    (catch Throwable t
                      (throw (ex-info (str "Invalid EDN: " (ex-message t))
                                      {:intent-string intent}))))]
    (intent/validate-intent! parsed)
    (let [code (format
                 (str "(let [before (hash @shell.editor/!db)] "
                      "  (shell.executor/apply-intent! shell.editor/!db %s \"MCP\") "
                      "  {:before-hash before "
                      "   :after-hash (hash @shell.editor/!db) "
                      "   :changed? (not= before (hash @shell.editor/!db))})")
                 (pr-str parsed))]
      (bridge/cljs-eval code))))

(defn- tool:eval-cljs [{:keys [code]}]
  (bridge/cljs-eval code))

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
                   :additionalProperties false}}

   ;; ── v2 live-editor tools ──────────────────────────────────────────────────

   "query-db"
   {:handler #'tool:query-db
    :description "Snapshot the live editor DB + view-state from the browser runtime. Returns a top-level shape sketch plus hashes — not the full document (use eval-cljs for deep probes)."
    :input-schema {:type "object" :properties {} :additionalProperties false}}

   "snapshot-db"
   {:handler #'tool:snapshot-db
    :description "Cheap hash-only snapshot of db + view-state. Use for change detection across dispatch-intent calls."
    :input-schema {:type "object" :properties {} :additionalProperties false}}

   "dispatch-intent"
   {:handler #'tool:dispatch-intent
    :description "Validate an intent (EDN) against its registered Malli spec on the JVM, then apply it to the live editor via shell.executor/apply-intent!. Returns before/after hashes so the agent can confirm the dispatch caused a change."
    :input-schema {:type "object"
                   :properties {:intent {:type "string"
                                         :description "EDN string for the intent map, e.g. '{:type :indent :id \"abc\"}'"}}
                   :required ["intent"]
                   :additionalProperties false}}

   "eval-cljs"
   {:handler #'tool:eval-cljs
    :description "Evaluate an arbitrary CLJS expression in the browser runtime via shadow.cljs.devtools.api/cljs-eval. Escape hatch for deep inspection; prefer the typed tools above when possible."
    :input-schema {:type "object"
                   :properties {:code {:type "string"
                                       :description "CLJS source to evaluate in the :blocks-ui runtime"}}
                   :required ["code"]
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
