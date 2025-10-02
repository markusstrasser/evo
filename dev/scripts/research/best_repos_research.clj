(ns scripts.research.best-repos-research
  (:require [scripts.utils.files :as files]
            [scripts.utils.shell :as shell]
            [scripts.utils.json :as json]
            [scripts.utils.http :as http]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(def default-question
  "How does this project implement its core data structures and operations? Focus on: 1) Core abstractions 2) Extensibility patterns 3) State management 4) Derived/computed values")

(def repos
  {"datascript" {:path "datascript" :subdir "src/datascript" :include "*.cljc,*.clj"}
   "reitit" {:path "reitit" :subdir "modules/reitit-core/src" :include "*.cljc,*.clj"}
   "re-frame" {:path "re-frame" :subdir "src/re_frame" :include "*.cljc,*.clj"}
   "meander" {:path "meander" :subdir "src/meander" :include "*.cljc,*.clj"}
   "specter" {:path "specter" :subdir "src/clj/com/rpl/specter" :include "*.cljc,*.clj"}})

(def models ["gemini" "codex" "grok"])

(defn project-root []
  (let [script-dir (str (fs/parent (fs/real-path *file*)))]
    (str (fs/parent script-dir))))

(defn best-dir []
  (str (fs/home) "/Projects/best"))

(defn cache-dir []
  (str (project-root) "/.repomix-cache"))

(defn output-dir []
  (let [timestamp (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd-HH-mm")
        now (java.time.LocalDateTime/now)]
    (str (project-root) "/docs/research/results/" (.format timestamp now))))

(defn overview-file []
  ;; Use auto-generated overview instead of dated snapshot
  (let [auto-overview (str (project-root) "/AUTO-SOURCE-OVERVIEW.md")
        fallback (str (project-root) "/AUTO-PROJECT-OVERVIEW.md")]
    (if (files/exists? auto-overview)
      auto-overview
      fallback)))

(defn log [color msg]
  (let [colors {:red "\033[0;31m"
                :green "\033[0;32m"
                :yellow "\033[1;33m"
                :blue "\033[0;36m"
                :reset "\033[0m"}]
    (println (str (colors color) msg (colors :reset)))))

(defn md5 [s]
  (let [digest (java.security.MessageDigest/getInstance "MD5")
        bytes (.digest digest (.getBytes s))]
    (apply str (map #(format "%02x" %) bytes))))

(defn get-repomix-context [repo-name {:keys [path subdir include]}]
  (let [repo-path (str (best-dir) "/" path)
        full-path (str repo-path "/" subdir)
        cache-key (md5 (str full-path ":" include))
        cache-file (str (cache-dir) "/" repo-name "-" cache-key ".txt")]

    (if (files/exists? cache-file)
      (do
        (log :blue (str "  Using cached repomix for " repo-name "..."))
        (files/read-file cache-file))
      (do
        (log :blue (str "  Generating repomix for " repo-name "..."))
        (let [result (shell/sh ["repomix" full-path
                                "--include" include
                                "--copy"
                                "--output" "/dev/null"])]
          (if (zero? (:exit result))
            (let [content (shell/sh ["pbpaste"])]
              (when (zero? (:exit content))
                (files/mkdir-p (cache-dir))
                (files/write-file cache-file (:out content))
                (log :blue (str "  Cached to: .repomix-cache/" (fs/file-name cache-file)))
                (:out content)))
            (do
              (log :red (str "  Error generating repomix: " (:err result)))
              "Error generating repomix")))))))

(defn build-prompt [overview-content repo-context repo-name research-question]
  (str "<evo-context>\n"
       "PROJECT OVERVIEW (EVO - our project):\n\n"
       overview-content "\n"
       "</evo-context>\n\n"
       "<target-repository>\n"
       "TARGET FOR ANALYSIS (" repo-name "):\n\n"
       repo-context "\n"
       "</target-repository>\n\n"
       "<research-question>\n"
       research-question "\n\n"
       "Analyze " repo-name "'s approach and extract patterns that could inspire EVO's architecture.\n"
       "Be specific with code examples. Focus on simplicity, readability, and elegance.\n"
       "</research-question>"))

(defn query-model [model prompt output-file repo-name]
  (log :blue (str "  Querying " model " for " repo-name "..."))
  (try
    (let [result (case model
                   "gemini"
                   (shell/sh ["bash" "-c"
                              (str "echo " (pr-str prompt) " | gemini --allowed-mcp-server-names \"\" -y > " output-file " 2>&1")])

                   "codex"
                   (shell/sh ["bash" "-c"
                              (str "echo " (pr-str prompt) " | codex exec -m gpt-5-codex -c model_reasoning_effort=\"high\" - > " output-file " 2>&1")])

                   "grok"
                   (shell/sh ["bash" "-c"
                              (str "echo " (pr-str prompt) " | " (project-root) "/scripts/grok -m grok-4-latest > " output-file " 2>&1")]))]

      (if (zero? (:exit result))
        (log :green (str "✓ " repo-name "-" model))
        (log :red (str "✗ " repo-name "-" model " FAILED"))))
    (catch Exception e
      (log :red (str "✗ " repo-name "-" model " ERROR: " (.getMessage e))))))

(defn validate-output [output-dir]
  (log :blue "Validating results...")
  (let [files (files/list-files output-dir {:glob "*.md"})
        small-files (filter #(< (files/file-size %) 1024) files)]
    (if (empty? small-files)
      (log :green "✓ All files look valid (>1KB)")
      (do
        (log :yellow "⚠ Warning: Small files detected (may be errors):")
        (doseq [f small-files]
          (log :yellow (str "  " (fs/file-name f) " (" (files/file-size f) " bytes)")))))))

(defn -main [& [question]]
  (let [research-question (or question default-question)
        out-dir (output-dir)
        overview (overview-file)]

    ;; Validate prerequisites
    (when-not (files/exists? overview)
      (log :red (str "Error: No overview file found. Run: npm run docs:overview"))
      (System/exit 1))

    (log :green "=== Best-of Repos Research ===")
    (println)
    (log :blue "Question:")
    (println (str "  " research-question))
    (println)

    ;; Create output and cache directories
    (files/mkdir-p out-dir)
    (files/mkdir-p (cache-dir))

    (log :blue "Repositories:")
    (doseq [[repo-name _] repos]
      (println (str "  - " repo-name)))
    (println)

    (log :blue "Models:")
    (doseq [model models]
      (println (str "  - " model)))
    (println)

    ;; Read overview
    (let [overview-content (files/read-file overview)]

      (log :green (str "Starting " (* (count repos) (count models)) " parallel queries..."))
      (println)

      ;; Process each repo
      (let [futures
            (doall
             (for [[repo-name repo-config] repos]
               (let [repo-path (str (best-dir) "/" (:path repo-config))]
                 (if-not (files/exists? repo-path)
                   (do
                     (log :yellow (str "Warning: Repository not found: " repo-path))
                     (log :yellow (str "Skipping " repo-name))
                     nil)
                   (do
                     (log :blue (str "Processing: " repo-name))
                     (println (str "  Path: " repo-path "/" (:subdir repo-config)))
                     (println)

                     ;; Get repomix context (cached or fresh)
                     (let [repo-context (get-repomix-context repo-name repo-config)
                           full-prompt (build-prompt overview-content repo-context repo-name research-question)]

                       ;; Query each model in parallel
                       (doall
                        (for [model models]
                          (future
                            (let [output-file (str out-dir "/" repo-name "-" model ".md")]
                              (query-model model full-prompt output-file repo-name)
                              output-file))))))))))]

        ;; Wait for all futures to complete
        (log :blue "Waiting for all queries to complete...")
        (log :yellow "Note: Codex queries may take 2-5 minutes each")
        (println)

        (doseq [repo-futures (remove nil? futures)]
          (doseq [f repo-futures]
            @f))

        (log :green "✓ All queries completed")
        (println))

      ;; Validate output
      (validate-output out-dir)
      (println)

      ;; Summary
      (log :green "=== Results ===")
      (println)
      (println (str "Output directory: " out-dir))
      (println)
      (println "Files:")
      (doseq [file (files/list-files out-dir {:glob "*.md"})]
        (let [size (files/file-size file)
              size-str (cond
                         (> size (* 1024 1024)) (str (quot size (* 1024 1024)) "M")
                         (> size 1024) (str (quot size 1024) "K")
                         :else (str size "B"))]
          (println (str "  " size-str "  " (fs/file-name file)))))
      (println)
      (log :green "Done!"))))
