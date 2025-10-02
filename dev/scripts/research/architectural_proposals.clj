(ns scripts.research.architectural-proposals
  (:require [scripts.utils.files :as files]
            [scripts.utils.shell :as shell]
            [scripts.utils.json :as json]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]))

(def providers ["gemini" "codex" "grok"])

(defn project-root []
  (let [script-dir (str (fs/parent (fs/real-path *file*)))]
    (str (fs/parent script-dir))))

(defn output-dir [custom-dir]
  (or custom-dir
      (let [timestamp (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd-HH-mm")
            now (java.time.LocalDateTime/now)]
        (str (project-root) "/docs/research/proposals/" (.format timestamp now)))))

(defn overview-file []
  ;; Prefer AUTO-PROJECT-OVERVIEW.md for architectural proposals (avoids implementation bias)
  (let [auto-project (str (project-root) "/AUTO-PROJECT-OVERVIEW.md")
        auto-source (str (project-root) "/AUTO-SOURCE-OVERVIEW.md")]
    (if (files/exists? auto-project)
      auto-project
      auto-source)))

(defn questions-file []
  (str (project-root) "/docs/research/10-research/architectural-questions.edn"))

(defn ranker-prompt-file []
  (str (project-root) "/docs/research/proposal-ranker-prompt.md"))

(defn log [color msg]
  (let [colors {:red "\033[0;31m"
                :green "\033[0;32m"
                :yellow "\033[1;33m"
                :blue "\033[0;34m"
                :reset "\033[0m"}]
    (println (str (colors color) msg (colors :reset)))))

(defn check-prerequisites []
  (let [overview (overview-file)
        questions (questions-file)
        ranker (ranker-prompt-file)]

    (when-not (files/exists? overview)
      (log :red (str "Error: Overview file not found. Run: npm run docs:overview"))
      (System/exit 1))

    (when-not (files/exists? questions)
      (log :red (str "Error: Questions file not found: " questions))
      (System/exit 1))

    (when-not (files/exists? ranker)
      (log :red (str "Error: Ranker prompt not found: " ranker))
      (System/exit 1))

    ;; Check API keys
    (let [missing-keys (cond-> []
                         (str/blank? (System/getenv "GROK_API_KEY"))
                         (conj "GROK_API_KEY"))]
      (when (seq missing-keys)
        (log :yellow (str "Warning: Missing API keys: " (str/join ", " missing-keys)))
        (log :yellow "Some providers will be skipped.")
        (println)))))

(defn load-questions []
  (let [questions-edn (edn/read-string (files/read-file (questions-file)))]
    (get questions-edn :questions)))

(defn build-proposal-prompt [overview-content question]
  (str "<context>\n"
       "PROJECT OVERVIEW:\n\n"
       overview-content "\n"
       "</context>\n\n"
       "<question>\n"
       "ARCHITECTURAL QUESTION:\n\n"
       (:prompt question) "\n\n"
       "Provide a concrete architectural proposal with:\n"
       "1. Core idea (1-2 paragraphs)\n"
       "2. Key benefits (specific to simplicity, readability, debuggability, expressiveness)\n"
       "3. Implementation sketch (pseudocode or structure)\n"
       "4. Tradeoffs and risks\n"
       "5. How it improves developer experience (REPL, debugging, testing)\n\n"
       "Be specific and concrete. Focus on architectural elegance over performance.\n"
       "</question>"))

(defn query-provider [provider prompt output-file question-id]
  (let [project-root-val (project-root)]
    (try
      (log :blue (str "  Querying " provider " for " question-id "..."))
      (let [result (case provider
                     "gemini"
                     (shell/sh ["bash" "-c"
                                (str "echo " (pr-str prompt) " | gemini --allowed-mcp-server-names \"\" -y > " output-file " 2>&1")])

                     "codex"
                     (shell/sh ["bash" "-c"
                                (str "echo " (pr-str prompt) " | codex exec -m gpt-5-codex -c model_reasoning_effort=\"high\" - > " output-file " 2>&1")])

                     "grok"
                     (if (str/blank? (System/getenv "GROK_API_KEY"))
                       {:exit 1 :out "" :err "No API key"}
                       (shell/sh ["bash" "-c"
                                  (str "echo " (pr-str prompt) " | " project-root-val "/scripts/grok -m grok-4-latest > " output-file " 2>&1")])))]

        (if (zero? (:exit result))
          (log :green (str "✓ " provider "-" question-id))
          (log :red (str "✗ " provider "-" question-id " FAILED"))))
      (catch Exception e
        (log :red (str "✗ " provider "-" question-id " ERROR: " (.getMessage e)))))))

(defn strip-codex-reasoning
  "Removes Codex reasoning block from proposal text.
   Looks for the end of reasoning marker and returns content after it."
  [text]
  (if (str/includes? text "</Thoughts>")
    ;; Find the end of reasoning block and take everything after
    (let [parts (str/split text #"</Thoughts>" 2)]
      (if (= 2 (count parts))
        (str/trim (second parts))
        text))
    text))

(defn collect-proposals [out-dir questions]
  (log :blue "=== Collecting Proposals for Ranking ===")
  (println)

  (let [all-proposals (atom "")
        proposal-num (atom 1)]

    (doseq [question questions]
      (let [question-id (name (:id question))
            question-focus (:focus question)]

        (doseq [provider providers]
          (let [proposal-file (str out-dir "/proposals/" provider "-" question-id ".md")]

            (if (files/exists? proposal-file)
              (let [file-size (files/file-size proposal-file)]

                (if (> file-size 100)
                  (let [raw-text (files/read-file proposal-file)
                        ;; Strip codex reasoning if present
                        proposal-text (if (= provider "codex")
                                        (strip-codex-reasoning raw-text)
                                        raw-text)]

                    (swap! all-proposals str
                           "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                           "PROPOSAL #" @proposal-num ": " provider " - " question-focus "\n"
                           "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                           proposal-text "\n\n")

                    (swap! proposal-num inc)
                    (log :green (str "  ✓ Proposal #" (dec @proposal-num) ": " provider "-" question-id)))

                  (log :yellow (str "  ⊘ Skipped (empty): " provider "-" question-id))))

              (log :red (str "  ✗ Missing: " provider "-" question-id)))))))

    (println)
    (log :green (str "Collected " (dec @proposal-num) " valid proposals"))
    (println)

    @all-proposals))

(defn generate-rankings [out-dir all-proposals overview-content]
  (log :blue "=== Generating Rankings ===")
  (println)

  (let [ranker-context (files/read-file (ranker-prompt-file))
        rank-prompt (str ranker-context "\n\n"
                         "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                         "PROJECT OVERVIEW\n"
                         "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                         overview-content "\n\n"
                         "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                         "PROPOSALS TO RANK\n"
                         "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                         all-proposals)]

    ;; Codex ranking
    (log :yellow "Ranking with Codex...")
    (let [result (shell/sh ["bash" "-c"
                            (str "echo " (pr-str rank-prompt)
                                 " | codex exec -m gpt-5-codex -c model_reasoning_effort=\"high\" --full-auto - > "
                                 out-dir "/rankings/codex-ranking.md 2>&1")])]
      (if (zero? (:exit result))
        (log :green "  ✓ Codex ranking complete")
        (log :red "  ✗ Codex ranking FAILED")))

    ;; Grok ranking (if API key available)
    (if-not (str/blank? (System/getenv "GROK_API_KEY"))
      (do
        (log :yellow "Ranking with Grok...")
        (let [result (shell/sh ["bash" "-c"
                                (str "echo " (pr-str rank-prompt)
                                     " | " (project-root) "/scripts/grok -m grok-4-latest > "
                                     out-dir "/rankings/grok-ranking.md 2>&1")])]
          (if (zero? (:exit result))
            (log :green "  ✓ Grok ranking complete")
            (log :red "  ✗ Grok ranking FAILED"))))
      (log :yellow "  ⊘ Grok ranking skipped (no API key)"))

    (println)
    (log :green "✓ Rankings complete")
    (println)))

(defn print-summary [out-dir]
  (log :blue "=== Summary ===")
  (println)
  (println (str "Proposals directory:  " out-dir "/proposals/"))
  (println (str "Rankings directory:   " out-dir "/rankings/"))
  (println)
  (println "Generated files:")

  (doseq [file (files/list-files (str out-dir "/proposals") {:glob "*.md"})]
    (let [size (files/file-size file)
          size-str (cond
                     (> size (* 1024 1024)) (str (quot size (* 1024 1024)) "M")
                     (> size 1024) (str (quot size 1024) "K")
                     :else (str size "B"))]
      (println (str "  " (fs/file-name file) " (" size-str ")"))))

  (println)

  (doseq [file (files/list-files (str out-dir "/rankings") {:glob "*.md"})]
    (let [size (files/file-size file)
          size-str (cond
                     (> size (* 1024 1024)) (str (quot size (* 1024 1024)) "M")
                     (> size 1024) (str (quot size 1024) "K")
                     :else (str size "B"))]
      (println (str "  " (fs/file-name file) " (" size-str ")"))))

  (println)
  (log :green "✓ Complete!")
  (println)
  (println "Next steps:")
  (println (str "  1. Review rankings: cat " out-dir "/rankings/*.md"))
  (println (str "  2. Read top proposals: bat " out-dir "/proposals/{codex,gemini,grok}-*.md"))
  (println (str "  3. Compare rankings: diff " out-dir "/rankings/codex-ranking.md " out-dir "/rankings/grok-ranking.md")))

(defn -main [& [custom-output-dir]]
  (log :blue "=== Architectural Proposal Generation ===")
  (println)

  ;; Check prerequisites
  (check-prerequisites)

  (let [out-dir (output-dir custom-output-dir)
        overview (overview-file)
        questions (load-questions)
        overview-content (files/read-file overview)]

    ;; Create output directories
    (files/mkdir-p (str out-dir "/proposals"))
    (files/mkdir-p (str out-dir "/rankings"))

    (log :green (str "Output directory: " out-dir))
    (println)

    ;; Generate proposals
    (log :blue (str "=== Generating " (* (count questions) (count providers)) " Proposals (" (count questions) " questions × " (count providers) " providers) ==="))
    (println)

    (let [futures
          (doall
           (for [question questions]
             (let [question-id (name (:id question))
                   question-focus (:focus question)
                   prompt (build-proposal-prompt overview-content question)]

               (log :yellow (str "Question: " question-focus))

               (doall
                (for [provider providers]
                  (if (and (= provider "grok") (str/blank? (System/getenv "GROK_API_KEY")))
                    (do
                      (log :yellow (str "  ⊘ " provider ": skipped (no API key)"))
                      nil)
                    (let [output-file (str out-dir "/proposals/" provider "-" question-id ".md")]
                      (println (str "  → " provider ": " output-file))
                      (future
                        (query-provider provider prompt output-file question-id)))))))))]

      (println)
      (log :blue (str "Waiting for all " (* (count questions) (count providers)) " proposals to complete..."))
      (log :yellow "Note: Codex with reasoning-effort=high may take 2-5 minutes per query")
      (println)

      ;; Wait for all futures
      (doseq [question-futures futures]
        (doseq [f (remove nil? question-futures)]
          @f))

      (log :green "✓ All proposals generated")
      (println))

    ;; Collect and rank proposals
    (let [all-proposals (collect-proposals out-dir questions)]
      (generate-rankings out-dir all-proposals overview-content))

    ;; Print summary
    (print-summary out-dir)))
