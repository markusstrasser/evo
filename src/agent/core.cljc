(ns agent.core
  "Simple agent utilities for the evolver app"
  (:require [agent.schemas :as schemas]))

(defn detect-environment
  "Detect current runtime environment to prevent environment mismatches"
  []
  {:browser? (exists? js/window)
   :node? (exists? js/process)
   :store-accessible? (try (some? (resolve 'evolver.state/store)) (catch :default _ false))
   :cljs-repl? (try (some? (resolve 'cljs.repl/*repl-env*)) (catch :default _ false))})

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
      (when (:browser? env)
        (throw (ex-info "Node tests should not run in browser environment"
                        {:current-env env :required :node})))

      :default env)))

(defn safe-command-dispatch
  "Dispatch command with environment and registry validation"
  [store event-data [cmd-name params]]
  (validate-environment-for-operation :store-access)
  (if-let [handler (get (resolve 'evolver.commands/command-registry) cmd-name)]
    (try
      ((resolve 'evolver.commands/dispatch-command) store event-data [cmd-name params])
      (catch :default e
        (throw (ex-info "Command execution failed"
                        {:command cmd-name :params params :error e}))))
    (throw (ex-info "Command not found in registry"
                    {:command cmd-name
                     :available-commands (keys (resolve 'evolver.commands/command-registry))}))))

(defn validate-file-namespace-alignment
  "Validate that namespace matches filename (prevents shadow-cljs compilation errors)"
  [file-path namespace-form]
  (when (and file-path namespace-form)
    (let [file-name (last (clojure.string/split file-path #"/"))
          expected-ns (cond
                        (clojure.string/ends-with? file-name ".cljc")
                        (clojure.string/replace file-name #"\.cljc$" "")

                        (clojure.string/ends-with? file-name ".cljs")
                        (clojure.string/replace file-name #"\.cljs$" "")

                        (clojure.string/ends-with? file-name ".clj")
                        (clojure.string/replace file-name #"\.clj$" "")

                        :else file-name)
          actual-ns (second namespace-form)]
      (when-not (= expected-ns actual-ns)
        (throw (ex-info "Namespace/filename mismatch (shadow-cljs requirement)"
                        {:file file-path
                         :expected-namespace expected-ns
                         :actual-namespace actual-ns
                         :fix "Change namespace to match filename"}))))))

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
      (catch :default e
        (throw (ex-info "Command execution failed"
                        {:command cmd-name :params params :error e}))))
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