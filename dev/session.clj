(ns session
  "REPL session management utilities"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defn save-session!
  "Save current REPL session state to dev/.repl-session.edn"
  []
  (let [session-data {:loaded-namespaces (map str (all-ns))
                      :current-ns (str *ns*)
                      :timestamp (java.util.Date.)
                      :jvm-props (into {} (System/getProperties))}]
    (io/make-parents "dev/.repl-session.edn")
    (spit "dev/.repl-session.edn" (pr-str session-data))
    (println "💾 Session saved to dev/.repl-session.edn")))

(defn restore-session!
  "Restore REPL session from saved state"
  []
  (when (.exists (io/file "dev/.repl-session.edn"))
    (try
      (let [{:keys [loaded-namespaces current-ns]} (edn/read-string (slurp "dev/.repl-session.edn"))]
        (println "🔄 Restoring session...")
        (doseq [ns-name loaded-namespaces]
          (try 
            (require (symbol ns-name) :reload)
            (print ".")
            (catch Exception e 
              (printf "⚠️  %s failed to reload: %s\n" ns-name (.getMessage e)))))
        (when current-ns
          (in-ns (symbol current-ns)))
        (println "\n✅ Session restored"))
      (catch Exception e
        (printf "❌ Failed to restore session: %s\n" (.getMessage e))))))

(defn quick-health-check!
  "Quick REPL health check"
  []
  (println "🏥 REPL Health Check:")
  (println "  JVM:" (System/getProperty "java.version"))
  (println "  Clojure:" (clojure-version))
  (println "  Namespaces loaded:" (count (all-ns)))
  (println "  Current ns:" *ns*)
  (try
    (require '[core.db :as db] :reload)
    (assert (:ok? (db/validate (db/empty-db))))
    (println "  Core modules: ✓")
    (catch Exception e
      (println "  Core modules: ❌" (.getMessage e)))))

(comment
  (save-session!)
  (restore-session!)
  (quick-health-check!))
