(ns health
  "Process and build health checks for ClojureScript development.
   Detects common issues: shadow-cljs conflicts, stale caches, environment mismatches."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn detect-environment
  "Detect current runtime environment.
   Returns map with :browser?, :node?, :cljs-repl? flags."
  []
  {:browser? false  ; JVM context, no browser
   :node? (try (boolean (resolve 'clojure.java.shell/sh)) (catch Exception _ false))
   :cljs-repl? false})

(defn check-shadow-conflicts
  "Detect potential shadow-cljs process conflicts.
   Returns vector of issue descriptions."
  []
  (let [issues []
        ;; Check for multiple nREPL ports (indicates multiple shadow processes)
        nrepl-port-files (filter #(.exists %)
                                 [(io/file ".nrepl-port")
                                  (io/file ".shadow-cljs/nrepl.port")])

        ;; Check for stale cache directories
        cache-dir (io/file ".shadow-cljs")
        cache-size (when (.exists cache-dir)
                     (->> (file-seq cache-dir)
                          (filter #(.isFile %))
                          (map #(.length %))
                          (reduce + 0)))]

    (cond-> issues
      ;; Multiple nREPL port files suggest conflicts
      (> (count nrepl-port-files) 1)
      (conj "Multiple nREPL port files detected - possible process conflicts")

      ;; Large cache might indicate corruption
      (and cache-size (> cache-size 100000000)) ; 100MB
      (conj "Large shadow-cljs cache detected (>100MB) - consider clearing")

      ;; Check for stale PIDs in common places
      (.exists (io/file "shadow-cljs.pid"))
      (conj "Stale shadow-cljs.pid file found"))))

(defn preflight-check!
  "Comprehensive preflight check for development environment.
   Prints warnings and returns {:ok? boolean :issues [...]}."
  []
  (let [env (detect-environment)
        shadow-issues (check-shadow-conflicts)
        all-issues (concat shadow-issues)]

    (if (seq all-issues)
      (do
        (println "⚠️ Environment issues detected:")
        (doseq [issue all-issues]
          (println "  -" issue))
        (println "\n💡 Recommended fixes:")
        (println "  - Stop all shadow-cljs processes: npx shadow-cljs stop")
        (println "  - Clear caches: rm -rf .shadow-cljs out target")
        (println "  - Restart: npm run dev")
        {:ok? false :issues all-issues})
      (do
        (println "✅ Environment looks healthy")
        {:ok? true :issues []}))))

(defn cache-stats
  "Get shadow-cljs cache statistics."
  []
  (let [cache-dir (io/file ".shadow-cljs")
        out-dir (io/file "out")
        target-dir (io/file "target")]
    (letfn [(dir-size [dir]
              (when (.exists dir)
                (->> (file-seq dir)
                     (filter #(.isFile %))
                     (map #(.length %))
                     (reduce + 0))))]
      {:shadow-cljs-cache (dir-size cache-dir)
       :out-dir (dir-size out-dir)
       :target-dir (dir-size target-dir)
       :total (+ (or (dir-size cache-dir) 0)
                 (or (dir-size out-dir) 0)
                 (or (dir-size target-dir) 0))})))

(defn clear-caches!
  "Clear all shadow-cljs caches. WARNING: Requires rebuild."
  []
  (println "🗑️  Clearing shadow-cljs caches...")
  (let [result (sh "rm" "-rf" ".shadow-cljs" "out" "target")]
    (if (zero? (:exit result))
      (do
        (println "✅ Caches cleared")
        (println "💡 Run 'npm run dev' to rebuild")
        true)
      (do
        (println "❌ Failed to clear caches:")
        (println (:err result))
        false))))

(defn check-repl-state
  "Check current REPL state and return diagnostics.
   Returns map with namespace counts, loaded libs, warnings."
  []
  (let [all-ns (try (count (all-ns)) (catch Exception _ 0))
        loaded-libs (try (count (loaded-libs)) (catch Exception _ 0))
        cljs-warnings (try
                        (when-let [w (resolve 'cljs.analyzer/*cljs-warnings*)]
                          (count (deref w)))
                        (catch Exception _ 0))
        cache-age (try
                    (let [cache-file (io/file ".shadow-cljs/nrepl.port")]
                      (when (.exists cache-file)
                        (let [modified (.lastModified cache-file)
                              now (System/currentTimeMillis)
                              age-seconds (quot (- now modified) 1000)]
                          age-seconds)))
                    (catch Exception _ nil))]
    {:namespaces all-ns
     :loaded-libs loaded-libs
     :cljs-warnings (or cljs-warnings 0)
     :cache-age-seconds cache-age
     :healthy? (and (> all-ns 0) (= cljs-warnings 0))}))

(comment
  ;; Quick health check
  (preflight-check!)

  ;; Check cache sizes
  (cache-stats)

  ;; Check REPL state
  (check-repl-state)

  ;; Nuclear option: clear everything
  (clear-caches!)
  )