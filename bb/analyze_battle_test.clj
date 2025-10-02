#!/usr/bin/env bb
(ns analyze-battle-test
  "Analyze battle-test results using Codex with high reasoning effort."
  (:require [files]
            [shell]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(def analysis-prompt-template
  "<all-results>
Below are the complete results from a battle test where multiple LLM providers analyzed several repositories for architectural patterns and techniques.

Each result shows one model's analysis of one repository. Your task is to synthesize these insights.

RESULTS:
%s
</all-results>

<analysis-task>
Analyze ALL the results above and provide:

## 1. Consensus Patterns (High Confidence)
Identify patterns/techniques that were independently discovered by MULTIPLE models across DIFFERENT repositories.

For each consensus pattern:
- **Pattern name and description**
- **Evidence:** Which models found it in which repos
- **Code examples:** Concrete code from the results
- **Applicability to EVO:** How this could improve our architecture
- **Confidence level:** How many independent discoveries (2+ = medium, 3+ = high)

## 2. Repository-Specific Insights
Unique approaches found in a single repository (mentioned by 2+ models about SAME repo).

For each:
- **Repository and pattern**
- **What makes it unique**
- **Code examples**
- **When to use this approach**

## 3. Model-Specific Discoveries (Outliers)
Interesting patterns found by ONLY ONE model about ONE repo.

For each:
- **Which model and repo**
- **The insight**
- **Why it might be brilliant OR why it might be hallucination**
- **How to verify** (e.g., \"check datascript source for X\")

## 4. Cross-Repository Themes
Broader architectural principles that emerge across multiple repos.

Examples:
- \"All analyzed projects use X pattern for Y\"
- \"Divergent approaches: repo1 does X, repo2 does Y, both valid\"
- \"Evolution: older projects use X, newer use Y\"

## 5. Actionable Recommendations for EVO
Based on the consensus patterns and high-confidence insights:

1. **Adopt immediately:** Patterns with strong evidence and clear benefits
2. **Experiment with:** Interesting approaches worth prototyping
3. **Research further:** Promising ideas that need deeper investigation
4. **Avoid:** Patterns that multiple repos avoid or have moved away from

## 6. Follow-Up Research Questions
What questions should we ask next to deepen our understanding?

Format as specific, actionable research prompts.
</analysis-task>

<output-requirements>
- Be SPECIFIC with code examples (quote actual code from results)
- Cite your evidence (e.g., \"gemini-datascript found X, codex-reitit found similar Y\")
- Distinguish between strong consensus (3+ independent discoveries) and weak signals (1-2 mentions)
- Flag uncertainty: if you're not sure whether something is brilliant or hallucination, say so
- Focus on ARCHITECTURAL insights, not surface-level observations
- Prioritize SIMPLICITY and DEBUGGABILITY over clever abstractions
</output-requirements>")

(defn parse-filename
  "Parse 'repo-model.md' filename into {:repo string :model string}."
  [filename]
  (let [name-without-ext (fs/strip-ext filename)]
    (if-let [[_ repo model] (re-matches #"^([^-]+)-(.+)$" name-without-ext)]
      {:repo repo :model model}
      {:repo "unknown" :model name-without-ext})))

(defn format-size
  "Format bytes as human-readable size."
  [size-bytes]
  (cond
    (< size-bytes 1024) (str size-bytes " B")
    (< size-bytes (* 1024 1024)) (str (quot size-bytes 1024) " KB")
    :else (str (quot size-bytes (* 1024 1024)) " MB")))

(defn collect-results
  "Collect all .md result files from directory."
  [results-dir]
  (let [md-files (files/list-files results-dir {:glob "*.md"})]
    (for [path md-files
          :let [{:keys [repo model]} (parse-filename (fs/file-name path))
                size-bytes (files/file-size path)
                content (files/read-file path)]]
      {:path path
       :filename (fs/file-name path)
       :repo repo
       :model model
       :size size-bytes
       :content content})))

(defn format-result
  "Format single result with separators for prompt."
  [{:keys [repo model content]}]
  (str "\n"
       "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
       "RESULT: " repo " analyzed by " model "\n"
       "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
       "\n"
       content
       "\n"))

(defn build-prompt
  "Build full analysis prompt with all results."
  [results]
  (let [all-results (str/join "\n" (map format-result results))]
    (format analysis-prompt-template all-results)))

(defn auto-detect-latest
  "Find latest results directory under research/results/."
  []
  (let [results-base (fs/path "research" "results")]
    (when (files/exists? results-base)
      (first (sort-by #(- (inst-ms (files/file-modified %)))
                      (filter fs/directory? (fs/list-dir results-base)))))))

(defn analyze!
  "Main analysis function."
  [results-dir-arg]
  (let [;; Determine results directory
        results-dir (or results-dir-arg
                        (auto-detect-latest)
                        (throw (ex-info "No results directory found. Usage: bb analyze-battle-test [results-dir]" {})))
        results-dir (fs/path results-dir)

        _ (when-not (files/exists? results-dir)
            (throw (ex-info (str "Results directory not found: " results-dir) {:dir results-dir})))

        ;; Collect results
        _ (println "\n=== Battle Test Analysis ===\n")
        _ (println "Results directory:" (str results-dir))
        _ (println)

        results (collect-results results-dir)

        _ (when (empty? results)
            (throw (ex-info (str "No result files found in " results-dir) {:dir results-dir})))

        _ (println (str "Found " (count results) " result files:"))
        _ (doseq [{:keys [filename size]} results]
            (println (str "  - " filename " (" (format-size size) ")")))
        _ (println)

        ;; Build prompt
        prompt (build-prompt results)

        ;; Output file
        output-file (fs/path results-dir "analysis.md")

        _ (println "Running analysis with Codex (this may take 2-5 minutes)...")
        _ (println "Output:" (str output-file))
        _ (println)

        ;; Run codex
        result (shell/sh ["codex" "exec" "-m" "gpt-5-codex" "-c" "model_reasoning_effort=\"high\"" "-"]
                         {:in prompt})]

    ;; Write output
    (files/write-file output-file (:out result))

    ;; Show status
    (if (zero? (:exit result))
      (do
        (println "✓ Analysis complete\n")
        (println "Preview (first 50 lines):\n")
        (println (str/join "\n" (take 50 (str/split-lines (:out result)))))
        (println "\n... (see full analysis in" (str output-file) ")"))
      (do
        (println "✗ Analysis failed:")
        (println (:err result))
        (System/exit 1)))))

(defn -main
  "Main entry point for bb task."
  [& [results-dir]]
  (analyze! results-dir))

;; Script entry point (for direct execution)
(when (= *file* (System/getProperty "babashka.file"))
  (analyze! (first *command-line-args*)))
