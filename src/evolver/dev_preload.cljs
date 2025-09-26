(ns evolver.dev-preload
  {:dev/always true} ; Ensures the compiler never caches this file
  (:require
   [evolver.core :as core] ; IMPORTANT: Require app's main entry namespace
   [malli.core :as m]
   [malli.error :as me]))

(js/console.log "🔍 Starting Malli function validation helpers...")

;; Helper function to validate function calls manually
(defn validate-fn-call [fn-var args]
  (if-let [schema (get (meta fn-var) :malli/schema)]
    (let [input-schema (second schema)]
      (if (m/validate input-schema args)
        true
        (let [error (m/explain input-schema args)]
          (js/console.error "❌ Schema validation failed for" (str (:name (meta fn-var))) ":"
                            (me/humanize error))
          false)))
    (do
      (js/console.warn "⚠️ No schema found for function" (str (:name (meta fn-var))))
      true)))

;; Enhanced function wrapper that provides validation
(defn wrap-with-validation [fn-var]
  (if-let [schema (get (meta fn-var) :malli/schema)]
    (let [original-fn @fn-var
          fn-name (:name (meta fn-var))]
      (fn [& args]
        (let [input-schema (second schema)
              result-schema (nth schema 2)]
          (if (m/validate input-schema args)
            (let [result (apply original-fn args)]
              (if (m/validate result-schema result)
                result
                (do
                  (js/console.error "❌ Return value validation failed for" (str fn-name) ":"
                                    (me/humanize (m/explain result-schema result)))
                  result)))
            (let [error (m/explain input-schema args)]
              (js/console.error "❌ Argument validation failed for" (str fn-name) ":"
                                (me/humanize error))
              (throw (ex-info (str "Invalid arguments to " fn-name)
                              {:function fn-name :args args :error (me/humanize error)})))))))
    @fn-var))

;; Global validation helper accessible from browser console
(defn ^:export validateCall [fn-name & args]
  (try
    (if-let [fn-var (resolve (symbol fn-name))]
      (validate-fn-call fn-var (vec args))
      (js/console.error "Function not found:" fn-name))
    (catch :default e
      (js/console.error "Validation error:" e))))

;; Make validation helper available globally
(set! (.-evo js/window)
      (js-obj
       "validateCall" validateCall
       "inspectStore" (fn [] (js/console.log "Store:" @core/store))
       "checkIntegrity" (fn [] (js/console.log "Reference integrity check - feature to be implemented"))
       "performance" (fn [] (js/console.log "Performance metrics - feature to be implemented"))))

(js/console.log "🔍 Malli validation helpers loaded!")
(js/console.log "💡 Use evo.validateCall('function-name', arg1, arg2) in browser console to test validation")