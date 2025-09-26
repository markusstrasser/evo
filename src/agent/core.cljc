(ns agent.core
  "Simple agent utilities for the evolver app"
  (:require [agent.schemas :as schemas]))

(defn detect-environment
  "Detect current runtime environment to prevent environment mismatches"
  []
  {:browser? #?(:cljs (some? js/window) :clj false)
   :node? #?(:cljs (some? js/process) :clj true)
   :store-accessible? (try (some? (resolve 'evolver.state/store)) #?(:clj (catch Exception _ false) :cljs (catch :default _ false)))
   :cljs-repl? #?(:cljs (try (some? (resolve 'cljs.repl/*repl-env*)) (catch :default _ false)) :clj false)})

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
  [store event-data [cmd-name params]]
  (validate-environment-for-operation :store-access)
  (if-let [handler (get (resolve 'evolver.commands/command-registry) cmd-name)]
    (try
      ((resolve 'evolver.commands/dispatch-command) store event-data [cmd-name params])
      #?(:clj (catch Exception e
                (throw (ex-info "Command execution failed"
                                {:command cmd-name :params params :error e})))
         :cljs (catch :default e
                 (throw (ex-info "Command execution failed"
                                 {:command cmd-name :params params :error e}))))
      )
    (throw (ex-info "Command not found in registry"
                    {:command cmd-name
                     :available-commands (keys (resolve 'evolver.commands/command-registry))}))))

(defn validate-file-namespace-alignment
  "Validate that namespace matches filename (prevents shadow-cljs compilation errors)"
  [file-path namespace-form]
  (when (and file-path namespace-form)
    (let [;; Extract path relative to src/ and convert to namespace
          relative-path (if (clojure.string/includes? file-path "src/")
                          (subs file-path (+ (clojure.string/index-of file-path "src/") 4))
                          file-path)
          ;; Remove file extension
          expected-ns-str (cond
                            (clojure.string/ends-with? relative-path ".cljc")
                            (clojure.string/replace relative-path #"\.cljc$" "")

                            (clojure.string/ends-with? relative-path ".cljs")
                            (clojure.string/replace relative-path #"\.cljs$" "")

                            (clojure.string/ends-with? relative-path ".clj")
                            (clojure.string/replace relative-path #"\.clj$" "")

                            :else relative-path)
          ;; Convert path separators to dots
          expected-ns (symbol (clojure.string/replace expected-ns-str #"/" "."))
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
  (let [cache-dir (clojure.java.io/file ".shadow-cljs")
        out-dir (clojure.java.io/file "out")
        target-dir (clojure.java.io/file "target")
        symptoms {:cache-dir-exists (.exists cache-dir)
                  :out-dir-exists (.exists out-dir)
                  :target-dir-exists (.exists target-dir)
                  :cache-dir-size (when (.exists cache-dir)
                                    (->> (file-seq cache-dir)
                                         (filter #(.isFile %))
                                         (map #(.length %))
                                         (reduce + 0)))
                  :compilation-errors (try
                                        (require '[cljs.compiler])
                                        false
                                        (catch Exception _ true))}]
    (when (or (:compilation-errors symptoms)
              (and (:cache-dir-exists symptoms) (> (:cache-dir-size symptoms 0) 100000000))) ; 100MB
      {:corruption-detected true
       :symptoms symptoms
       :remediation "Run: npx shadow-cljs stop && rm -rf .shadow-cljs out target"})))

(defn validate-build-target-compatibility
  "Validates that .cljc files are compatible with target environments"
  [file-path target]
  (when (and (clojure.string/ends-with? file-path ".cljc")
             (= target :node-test))
    (let [content (slurp file-path)]
      (when (or (re-find #"js/document" content)
                (re-find #"js/window" content)
                (re-find #"js/console" content))
        (throw (ex-info ".cljc file contains browser-only APIs incompatible with node-test target"
                        {:file file-path
                         :target target
                         :suggestion "Move browser-specific code to .cljs files or use reader conditionals"}))))))

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
  (if-let [handler (get (resolve 'evolver.commands/command-registry) cmd-name)]
    (try
      ((resolve 'evolver.commands/dispatch-command) store event-data [cmd-name params])
      #?(:clj (catch Exception e
                (throw (ex-info "Command execution failed"
                                {:command cmd-name :params params :error e})))
         :cljs (catch :default e
                 (throw (ex-info "Command execution failed"
                                 {:command cmd-name :params params :error e}))))
      )
    (throw (ex-info "Command not found in registry"
                    {:command cmd-name
                     :available-commands (keys (resolve 'evolver.commands/command-registry))}))))

;; Schema validation

;; Schema validation
(def validate-transaction schemas/validate-transaction)

(defn help
  "Show available agent functions"
  []
  (println "Agent utilities:")
  (println "  (validate-transaction tx) - Validate transaction format"))