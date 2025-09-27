(ns agent.core
  "Simple agent utilities for the evolver app"
  (:require [agent.schemas :as schemas]
            [malli.core :as m]
            [clojure.data :as data]
            [clojure.set :as set]
            #?(:clj [clojure.java.io :as io]
               :cljs [clojure.string :as str])
            #?(:clj [clojure.string :as str])))

(defn detect-environment
  "Detect current runtime environment to prevent environment mismatches"
  []
  {:browser? #?(:cljs (try (boolean js/window) (catch :default _ false))
                :clj false)
   :node? #?(:cljs (try (boolean js/process) (catch :default _ false))
             :clj (try (boolean (resolve 'clojure.java.shell/sh)) (catch Exception _ false)))
   :store-accessible? (try (some? (resolve 'evolver.protocol-sketch/store)) (catch #?(:cljs :default :clj Exception) _ false))
   :cljs-repl? #?(:cljs (try (some? (resolve 'cljs.repl/*repl-env*)) (catch :default _ false))
                  :clj false)})

(defn validate-dev-environment
  "Comprehensive development environment validation"
  []
  (let [env (detect-environment)
        issues []]
    (cond-> issues
      ;; Check for browser connectivity for ClojureScript development
      (and (:cljs-repl? env) (not (:browser? env)))
      (conj "ClojureScript REPL detected but browser not connected. Open http://localhost:8080")

      ;; Check for store accessibility in browser context
      (and (:browser? env) (not (:store-accessible? env)))
      (conj "Browser detected but store not accessible. Ensure app is loaded")

      ;; Warn about node context for UI operations
      (and (:node? env) (not (:browser? env)))
      (conj "Node.js environment detected. Browser required for UI operations"))))

(defn check-shadow-cljs-conflicts
  "Detect potential shadow-cljs process conflicts (ClojureScript only)"
  []
  #?(:cljs
     (let [issues []]
       ;; In browser, we can check for compilation issues indirectly
       (cond-> issues
         ;; Check if hot reload seems broken (this is heuristic)
         (try
           (let [build-id js/shadow.cljs.devtools.client.env.build-id]
             (and build-id (< (count (str build-id)) 5))) ; Normal build IDs are longer
           (catch :default _ false))
         (conj "Possible compilation/hot-reload issues. Check for process conflicts")

         ;; Check for multiple nREPL connections (another heuristic)
         false ; placeholder - hard to detect from browser
         (conj "Multiple development processes may be running")))
     :clj []))

(defn validate-environment-for-operation
  "Validate that current environment supports the requested operation"
  [operation-type]
  (let [env (detect-environment)]
    (case operation-type
      :dom-manipulation
      (when-not (:browser? env)
        (throw (ex-info "DOM operations require browser environment"
                        {:current-env env :required :browser})))

      :store-access
      (when-not (:store-accessible? env)
        (throw (ex-info "Store access requires browser environment with loaded app"
                        {:current-env env :required :store-accessible})))

      :node-test
      (do
        (when (:browser? env)
          (throw (ex-info "Node tests should not run in browser environment"
                          {:current-env env :required :node})))
        env)

      ;; For any other operation type, just return the environment
      env)))

(defn safe-command-dispatch
  "Dispatch command with environment and registry validation"
  {:malli/schema [:=> [:cat any? map? [:tuple keyword? map?]] any?]}
  [store event-data [cmd-name params]]
  (validate-environment-for-operation :store-access)
  (if-let [handler (get (resolve 'evolver.registry/registry) cmd-name)]
    (try
      ((resolve 'evolver.protocol-sketch/dispatch!) store event-data [cmd-name params])
      #?(:clj (catch Exception e
                (throw (ex-info "Command execution failed"
                                {:command cmd-name :params params :error e})))
         :cljs (catch :default e
                 (throw (ex-info "Command execution failed"
                                 {:command cmd-name :params params :error e})))))
    (throw (ex-info "Command not found in registry"
                    {:command cmd-name
                     :available-commands (keys (resolve 'evolver.registry/registry))}))))

(defn validate-file-namespace-alignment
  "Validate that namespace matches filename (prevents shadow-cljs compilation errors)"
  [file-path namespace-form]
  (when (and file-path namespace-form)
    (let [;; Extract path relative to src/ and convert to namespace
          relative-path (if (str/includes? file-path "src/")
                          (subs file-path (+ (str/index-of file-path "src/") 4))
                          file-path)
          ;; Remove file extension
          expected-ns-str (cond
                            (str/ends-with? relative-path ".cljc")
                            (str/replace relative-path #"\.cljc$" "")

                            (str/ends-with? relative-path ".cljs")
                            (str/replace relative-path #"\.cljs$" "")

                            (str/ends-with? relative-path ".clj")
                            (str/replace relative-path #"\.clj$" "")

                            :else relative-path)
          ;; Convert path separators to dots
          expected-ns (symbol (str/replace expected-ns-str #"/" "."))
          actual-ns (second namespace-form)]
      (when-not (= expected-ns actual-ns)
        (throw (ex-info "Namespace/filename mismatch (shadow-cljs requirement)"
                        {:file file-path
                         :expected-namespace expected-ns
                         :actual-namespace actual-ns
                         :fix "Change namespace to match filename"}))))))

(defn validate-data-access-pattern
  "Validates that code uses CLJS data access patterns, not JS property access"
  [code-string]
  (let [js-patterns ["\\.state\\." "\\.view\\." "\\.selected\\." "\\.nodes\\."]
        cljs-patterns ["\\(:state" "\\(:view" "\\(:selected" "\\(:nodes"]
        js-matches (some #(re-find (re-pattern %) code-string) js-patterns)
        cljs-matches (some #(re-find (re-pattern %) code-string) cljs-patterns)]
    (cond
      (and js-matches (not cljs-matches))
      (throw (ex-info "JS-style property access detected in CLJS code"
                      {:code code-string
                       :js-patterns-found js-patterns
                       :suggestion "Use CLJS accessors like (:key map) instead of .key syntax"}))

      (and js-matches cljs-matches)
      (println "Warning: Mixed JS and CLJS data access patterns detected")

      :else true)))

(defn safe-name
  "Nil-safe version of name function"
  [x]
  (when (some? x)
    (name x)))

(defn safe-first
  "Nil-safe version of first function"
  [coll]
  (when (seq coll)
    (first coll)))

(defn safe-get-in
  "Nil-safe version of get-in with better error messages"
  [m ks & [not-found]]
  (try
    (get-in m ks not-found)
    #?(:clj (catch Exception e
              (throw (ex-info "get-in failed"
                              {:map m :keys ks :error e
                               :suggestion "Check that map is not nil and keys exist"})))
       :cljs (catch :default e
               (throw (ex-info "get-in failed"
                               {:map m :keys ks :error e
                                :suggestion "Check that map is not nil and keys exist"}))))))

(defn validate-replicant-action-vector
  "Validates replicant action vector format: [:command-name {:params}]"
  [action-vector]
  (when action-vector
    (cond
      (not (vector? action-vector))
      (throw (ex-info "Replicant action must be a vector"
                      {:action action-vector :expected-format "[:command-name {:params}]"}))

      (< (count action-vector) 1)
      (throw (ex-info "Replicant action vector must have at least a command name"
                      {:action action-vector :expected-format "[:command-name {:params}]"}))

      (not (keyword? (first action-vector)))
      (throw (ex-info "First element of replicant action must be a keyword (command name)"
                      {:action action-vector :expected-format "[:command-name {:params}]"}))

      (and (> (count action-vector) 1) (not (map? (second action-vector))))
      (throw (ex-info "Second element of replicant action must be a parameter map"
                      {:action action-vector :expected-format "[:command-name {:params}]"}))

      :else true)))

(defn detect-cache-corruption
  "Attempts to detect cache corruption by checking for common symptoms"
  []
  #?(:clj
     ;; JVM version with file system access
     (let [cache-dir (io/file ".shadow-cljs")
           symptoms {:cache-dir-exists (.exists cache-dir)
                     :cache-dir-size (when (.exists cache-dir)
                                       (->> (file-seq cache-dir)
                                            (filter #(.isFile %))
                                            (map #(.length %))
                                            (reduce + 0)))}]
       (when (and (:cache-dir-exists symptoms)
                  (> (:cache-dir-size symptoms 0) 100000000)) ; 100MB
         {:corruption-detected true
          :symptoms symptoms
          :remediation "Run: npx shadow-cljs stop && rm -rf .shadow-cljs out target"}))

     :cljs
     ;; CLJS version - simplified cache detection
     {:corruption-detected false
      :symptoms {:note "Cache detection not available in CLJS runtime"}
      :remediation "If experiencing issues, run: npx shadow-cljs stop && rm -rf .shadow-cljs out target"}))

(defn validate-build-target-compatibility
  "Validates that .cljc files are compatible with target environments"
  [file-path target]
  #?(:clj
     ;; JVM version with file access
     (when (and (str/ends-with? file-path ".cljc")
                (= target :node-test))
       (let [content (slurp file-path)]
         (when (or (re-find #"js/document" content)
                   (re-find #"js/window" content)
                   (re-find #"js/console" content))
           (throw (ex-info ".cljc file contains browser-only APIs incompatible with node-test target"
                           {:file file-path
                            :target target
                            :suggestion "Move browser-specific code to .cljs files or use reader conditionals"})))))

     :cljs
     ;; CLJS version - skip validation (can't read files)
     nil))

(defn validate-operation-schema
  "Validate operation data against expected schema"
  [operation-data schema-key]
  (let [schema (get schemas/registry schema-key)]
    (when schema
      (let [valid? (schemas/validate schema operation-data)]
        (when-not valid?
          (let [errors (schemas/humanize (schemas/explain schema operation-data))]
            (throw (ex-info "Schema validation failed"
                            {:schema schema-key
                             :data operation-data
                             :errors errors}))))))
    operation-data))

(defn validate-command-params
  "Validate command parameters before dispatch"
  [cmd-name params]
  (case cmd-name
    :select-node
    (validate-operation-schema {:node-id (:node-id params)} :select-node-params)

    :hover-node
    (validate-operation-schema {:node-id (:node-id params)} :hover-node-params)

    :navigate-sequential
    (validate-operation-schema {:direction (:direction params)} :navigation-params)

    :navigate-sibling
    (validate-operation-schema {:direction (:direction params)} :navigation-params)

    :move-block
    (validate-operation-schema {:direction (:direction params)} :navigation-params)

    ;; Default: no specific validation
    params))

(defn safe-schema-validated-dispatch
  "Dispatch with full validation: environment, registry, and schema"
  [store event-data [cmd-name params]]
  (validate-environment-for-operation :store-access)
  (validate-command-params cmd-name params)
  (if-let [handler (get (resolve 'evolver.registry/registry) cmd-name)]
    (try
      ((resolve 'evolver.protocol-sketch/dispatch!) store event-data [cmd-name params])
      #?(:clj (catch Exception e
                (throw (ex-info "Command execution failed"
                                {:command cmd-name :params params :error e})))
         :cljs (catch :default e
                 (throw (ex-info "Command execution failed"
                                 {:command cmd-name :params params :error e})))))
    (throw (ex-info "Command not found in registry"
                    {:command cmd-name
                     :available-commands (keys (resolve 'evolver.registry/registry))}))))

;; Schema validation

;; Schema validation
(def validate-transaction schemas/validate-transaction)

(defn check-development-environment
  "Comprehensive development environment health check"
  []
  (let [env (detect-environment)
        env-issues (validate-dev-environment)
        shadow-issues (check-shadow-cljs-conflicts)
        all-issues (concat env-issues shadow-issues)]
    {:environment env
     :issues all-issues
     :healthy? (empty? all-issues)
     :recommendations
     (cond
       (not (:browser? env))
       ["Open browser at http://localhost:8080" "Connect ClojureScript REPL"]

       (not (:store-accessible? env))
       ["Ensure app is fully loaded" "Check browser console for errors"]

       (seq shadow-issues)
       ["Run 'npm run check-env' to detect process conflicts"
        "Use 'npm dev' instead of manual shadow-cljs commands"]

       :else
       ["Environment looks healthy" "Ready for development"])}))

(defn safe-repl-connect
  "Safely validate environment for ClojureScript REPL connection"
  []
  #?(:cljs
     (let [env (detect-environment)]
       (cond
         (not (:browser? env))
         (do (js/console.error "❌ Browser not detected. Open http://localhost:8080 first")
             {:success false :issue "no-browser"})

         (not (exists? js/shadow))
         (do (js/console.error "❌ Shadow-cljs not available. Ensure shadow-cljs watch is running")
             {:success false :issue "no-shadow"})

         :else
         (do (js/console.log "✅ Environment ready for ClojureScript REPL")
             (js/console.log "💡 Connect with: (shadow.cljs.devtools.api/repl :frontend)")
             {:success true :message "Environment validated - ready for REPL connection"})))
     :clj
     (do (println "⚠️ safe-repl-connect validation only works in ClojureScript context")
         {:success false :issue "not-cljs"})))

(defn list-known-schemas
  "List known functions with Malli schemas (simplified approach)"
  []
  {"Known schema-enabled functions:"
   ["evolver.kernel/add-reference - validates db and reference params"
    "evolver.kernel/remove-reference - validates db and reference params"
    "agent.core/safe-command-dispatch - validates store, event data, and command"]
   "Usage:" "Use (meta #'function-name) to inspect schemas directly"
   "Example:" "(meta #'evolver.kernel/add-reference)"})

(defn quick-schema-check
  "Quick check of schema availability for key functions"
  []
  (let [checks [["evolver.kernel/add-reference"
                 (try
                   (when-let [fn-var (resolve 'evolver.kernel/add-reference)]
                     (some? (get (meta fn-var) :malli/schema)))
                   (catch #?(:cljs :default :clj Exception) _ false))]
                ["evolver.kernel/remove-reference"
                 (try
                   (when-let [fn-var (resolve 'evolver.kernel/remove-reference)]
                     (some? (get (meta fn-var) :malli/schema)))
                   (catch #?(:cljs :default :clj Exception) _ false))]]]
    (into {} checks)))

(defn validate-call
  "Manually validate function call with its Malli schema - requires (require '[malli.core :as m])"
  [fn-var & args]
  (if-let [schema (get (meta fn-var) :malli/schema)]
    (let [input-schema (second schema)] ; [:=> [:cat ...] ...]
      (if (= 2 (count schema))
        {:valid? true :message "Schema has no input validation"}
        (try
          {:valid? (m/validate input-schema (vec args))
           :schema input-schema
           :args args}
          (catch #?(:cljs :default :clj Exception) e
            {:error "malli.core not available - require it first" :exception e}))))
    {:error "No Malli schema found for function"}))

;; Selection State Validation (for the specific triple-field issue)
(defn validate-selection-consistency
  "Validates that selection, selection-set, and cursor are consistent"
  [view-state]
  (let [{:keys [selection selection-set cursor]} view-state
        selection-vec (or selection [])
        selection-s (or selection-set #{})
        cursor-id cursor]
    {:valid? (and (= (set selection-vec) selection-s)
                  (or (nil? cursor-id) (contains? selection-s cursor-id)))
     :issues (cond-> []
               (not= (set selection-vec) selection-s)
               (conj {:type :selection-mismatch
                      :selection-vector selection-vec
                      :selection-set selection-s
                      :diff {:only-in-vector (set/difference (set selection-vec) selection-s)
                             :only-in-set (set/difference selection-s (set selection-vec))}})

               (and cursor-id (not (contains? selection-s cursor-id)))
               (conj {:type :cursor-not-selected
                      :cursor cursor-id
                      :selection-set selection-s}))
     :selection-vector selection-vec
     :selection-set selection-s
     :cursor cursor-id}))

;; Mock Event Utilities for Testing
(defn create-mock-dom-event
  "Creates a mock DOM event for testing UI commands"
  [& {:keys [type target modifiers]
      :or {type "click" target nil modifiers #{}}}]
  #?(:cljs
     (let [event #js {:type type
                      :target target
                      :ctrlKey (contains? modifiers :ctrl)
                      :shiftKey (contains? modifiers :shift)
                      :metaKey (contains? modifiers :meta)
                      :altKey (contains? modifiers :alt)
                      :preventDefault (fn [])
                      :stopPropagation (fn [])}]
       event)
     :clj
     {:type type
      :target target
      :modifiers modifiers
      :preventDefault (fn [])
      :stopPropagation (fn [])}))

(defn create-mock-target
  "Creates a mock DOM target element"
  [node-id & {:keys [dataset] :or {dataset {}}}]
  #?(:cljs
     (let [merged-dataset (merge {"nodeId" node-id} dataset)]
       #js {:dataset (clj->js merged-dataset)
            :id node-id})
     :clj
     {:dataset (merge {"nodeId" node-id} dataset)
      :id node-id}))

;; Navigation Validation
(defn validate-navigation-prerequisites
  "Validates that navigation commands have required context"
  [db operation-type & {:keys [target-id]}]
  (let [cursor (get-in db [:view :cursor])
        selection (get-in db [:view :selection] [])
        selection-set (get-in db [:view :selection-set] #{})]
    (case operation-type
      (:nav-up :nav-down :nav-in :nav-out)
      (when (nil? cursor)
        {:warning :missing-cursor
         :operation-type operation-type
         :suggestion "Navigation requires :cursor field in :view state"
         :current-selection selection})

      (:select-node :toggle-selection)
      (when-not target-id
        {:error :missing-target-id
         :operation-type operation-type
         :suggestion "Selection operations require :target-id parameter"})

      nil))) ; No validation needed for other operations

;; Unified Test Context Creator
(defn create-test-context
  "Creates a complete test context that mirrors UI behavior"
  [initial-state & {:keys [selection cursor] :or {selection [] cursor nil}}]
  (let [consistent-selection-set (set selection)
        final-cursor (or cursor (first selection))]
    (-> initial-state
        (assoc-in [:view :selection] selection)
        (assoc-in [:view :selection-set] consistent-selection-set)
        (assoc-in [:view :cursor] final-cursor))))

;; Command Execution Tracer
(def ^:dynamic *command-trace* (atom []))

(defn trace-command-execution
  "Traces command execution from dispatch to state change"
  [command-name params before-state after-state]
  (let [trace-entry {:timestamp #?(:cljs (js/Date.now) :clj (System/currentTimeMillis))
                     :command command-name
                     :params params
                     :state-changes (when (and before-state after-state)
                                      (let [[removed added _] (data/diff before-state after-state)]
                                        {:removed removed
                                         :added added
                                         :summary (str "Changed: "
                                                       (count (tree-seq coll? seq removed)) " removed, "
                                                       (count (tree-seq coll? seq added)) " added")}))}]
    (swap! *command-trace* conj trace-entry)
    ;; Keep only last 20 traces to prevent memory bloat
    (when (> (count @*command-trace*) 20)
      (swap! *command-trace* #(vec (take-last 20 %))))
    trace-entry))

(defn get-command-trace
  "Get recent command execution trace"
  []
  @*command-trace*)

(defn clear-command-trace
  "Clear command execution trace"
  []
  (reset! *command-trace* []))

(defn traced-dispatch
  "Dispatch command with execution tracing"
  [store event-data [cmd-name params]]
  (let [before-state @store
        result (safe-command-dispatch store event-data [cmd-name params])
        after-state @store]
    (trace-command-execution cmd-name params before-state after-state)
    result))

(defn help
  "Show available agent functions"
  []
  (println "Agent utilities:")
  (println "  (detect-environment) - Check current runtime environment")
  (println "  (validate-dev-environment) - Validate development setup")
  (println "  (check-development-environment) - Full environment health check")
  (println "  (safe-repl-connect) - Validate environment for ClojureScript REPL")
  (println "  (list-known-schemas) - Show all known Malli schema functions")
  (println "  (quick-schema-check) - Check schema availability for key functions")
  (println "  (validate-call #'fn-var arg1 arg2) - Manually test function schemas")
  (println "  (validate-transaction tx) - Validate transaction format")
  (println "")
  (println "DX Tools (fixes from agent session failures):")
  (println "  (validate-selection-consistency view-state) - Check selection/cursor consistency")
  (println "  (create-mock-dom-event) - Create mock events for testing UI commands")
  (println "  (create-mock-target node-id) - Create mock DOM targets")
  (println "  (validate-navigation-prerequisites db op-type) - Check if navigation will work")
  (println "  (create-test-context state :selection [] :cursor nil) - Create UI-like test state")
  (println "")
  (println "Command tracing:")
  (println "  (traced-dispatch store event [cmd params]) - Dispatch with execution tracing")
  (println "  (get-command-trace) - View recent command execution trace")
  (println "  (clear-command-trace) - Clear command execution trace")
  (println "")
  (println "Store inspection (agent.store-inspector):")
  (println "  (track-watch-update :store-key) - Monitor for watch loops")
  (println "  (clear-watch-tracking) - Clear watch loop detection history")
  (println "")
  (println "Environment checks:")
  (println "  Run 'npm run check-env' to check for shadow-cljs conflicts"))