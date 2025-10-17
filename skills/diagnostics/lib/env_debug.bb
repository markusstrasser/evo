#!/usr/bin/env bb
;; Debug .env file hierarchy and show which values are actually loaded
;;
;; Usage:
;;   env_debug.bb                    # Show all .env files and their vars
;;   env_debug.bb GEMINI_API_KEY     # Trace specific variable
;;   env_debug.bb --conflicts        # Show only conflicting vars

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

;; ANSI colors
(def colors
  {:red "\033[0;31m"
   :green "\033[0;32m"
   :yellow "\033[1;33m"
   :blue "\033[0;34m"
   :cyan "\033[0;36m"
   :gray "\033[0;90m"
   :nc "\033[0m"})

(defn colorize [color text]
  (str (colors color) text (colors :nc)))

;; Find all .env files from current dir up to home
(defn find-env-files []
  (loop [current (fs/cwd)
         files []]
    (let [env-file (fs/file current ".env")
          files' (if (fs/exists? env-file)
                   (conj files (str env-file))
                   files)
          parent (fs/parent current)]
      (if (or (nil? parent)
              (= (str parent) "/")
              (= (str parent) (str (fs/parent (fs/home)))))
        (reverse files')
        (recur parent files')))))

;; Extract variables from .env file
(defn extract-vars [file]
  (when (fs/exists? file)
    (->> (str/split-lines (slurp file))
         (filter #(re-matches #"^[A-Z_][A-Z0-9_]*=.*" %))
         (map #(str/replace % #"^export\s+" ""))
         (map #(first (str/split % #"=" 2)))
         (distinct)
         (sort))))

;; Get value from .env file for specific var
(defn get-file-value [file var-name]
  (when (fs/exists? file)
    (let [lines (str/split-lines (slurp file))
          pattern (re-pattern (str "^(export\\s+)?" var-name "=(.*)"))
          matching-line (->> lines
                             (filter #(re-matches pattern %))
                             (first))]
      (when matching-line
        (-> matching-line
            (str/replace #"^export\s+" "")
            (str/split #"=" 2)
            (second)
            (str/replace #"^[\"']" "")
            (str/replace #"[\"']$" ""))))))

;; Get value from environment
(defn get-env-value [var-name]
  (System/getenv var-name))

;; Truncate value for preview
(defn preview [value max-len]
  (if (> (count value) max-len)
    (str (subs value 0 max-len) "...")
    value))

;; Find conflicts (vars defined in multiple files)
(defn find-conflicts [env-files]
  (let [var-files (reduce
                   (fn [acc file]
                     (reduce
                      (fn [a var]
                        (update a var (fnil conj []) file))
                      acc
                      (extract-vars file)))
                   {}
                   env-files)]
    (->> var-files
         (filter (fn [[_ files]] (> (count files) 1)))
         (map first)
         (sort))))

;; Trace specific variable
(defn trace-var [env-files var-name]
  (println)
  (println (colorize :cyan (str "Tracing: " (colorize :yellow var-name))))
  (println)

  (let [found-in-files
        (filter #(get-file-value % var-name) env-files)]

    (if (empty? found-in-files)
      (do
        (println (colorize :yellow "⚠ Variable not found in any .env file"))
        (println))
      (doseq [file found-in-files]
        (let [value (get-file-value file var-name)
              dir (str (fs/parent file))]
          (println (colorize :blue (str "📄 " dir "/.env")))
          (println (str "   " var-name "=" (colorize :green (preview value 20))))
          (println))))

    ;; Show actual loaded value
    (if-let [actual (get-env-value var-name)]
      (do
        (println (colorize :green "✓ Currently loaded value:"))
        (println (str "   " var-name "=" (colorize :cyan (preview actual 20))))
        (println)

        ;; Check if it matches any file
        (if-let [matching-file
                 (->> env-files
                      (filter #(= actual (get-file-value % var-name)))
                      (first))]
          (println (colorize :green (str "✓ Matches: " (fs/parent matching-file) "/.env")))
          (do
            (println (colorize :red "✗ Does not match any .env file!"))
            (println (colorize :yellow "  Value may be from shell environment or other source")))))
      (println (colorize :red "✗ Variable not set in current environment")))

    (println)))

;; Show conflicts
(defn show-conflicts [env-files conflicts]
  (when (seq conflicts)
    (println (colorize :red (str "⚠ Variables with conflicts: " (count conflicts))))
    (println)

    (doseq [var conflicts]
      (println (colorize :yellow (str "  " var)))

      (doseq [file env-files]
        (when-let [value (get-file-value file var)]
          (let [dir (str (fs/parent file))]
            (println (str "    " (colorize :gray (str dir "/")) ".env: "
                          (colorize :cyan (preview value 30)))))))

      ;; Show actual loaded value
      (if-let [actual (get-env-value var)]
        (println (str "    " (colorize :green "→ Loaded:") " "
                      (colorize :cyan (preview actual 30))))
        (println (str "    " (colorize :red "→ Not loaded"))))

      (println))))

;; Show all variables by file
(defn show-all-vars [env-files conflicts]
  (println (colorize :cyan "All variables by file:"))
  (println)

  (doseq [file env-files]
    (let [dir (str (fs/parent file))
          vars (extract-vars file)]
      (println (colorize :blue (str "📄 " dir "/.env")))

      (if (empty? vars)
        (println (colorize :gray "   (no variables)"))
        (doseq [var vars]
          (let [value (get-file-value file var)
                is-conflict (some #(= var %) conflicts)
                conflict-mark (if is-conflict
                                (str (colorize :yellow "⚠") " ")
                                "")]
            (println (str "   " conflict-mark var "="
                          (colorize :gray (preview value 40)))))))
      (println))))

;; Main
(defn -main [& args]
  (let [args-vec (vec args)
        parsed-args (loop [remaining args-vec
                           specific-var nil
                           conflicts-only false]
                      (if (empty? remaining)
                        {:var specific-var :conflicts-only conflicts-only}
                        (let [arg (first remaining)]
                          (cond
                            (= arg "--conflicts")
                            (recur (rest remaining) specific-var true)

                            (or (= arg "--help") (= arg "-h"))
                            (do
                              (println "Usage: env_debug.bb [VAR_NAME] [--conflicts]")
                              (println)
                              (println "Examples:")
                              (println "  env_debug.bb                      # Show all .env files and vars")
                              (println "  env_debug.bb GEMINI_API_KEY       # Trace GEMINI_API_KEY through hierarchy")
                              (println "  env_debug.bb --conflicts          # Show only vars with conflicts")
                              (System/exit 0))

                            :else
                            (recur (rest remaining) arg conflicts-only)))))

        env-files (find-env-files)]

    (println)
    (println (colorize :blue "═══════════════════════════════════════════════════════════"))
    (println (colorize :blue "   .env File Hierarchy Debugger"))
    (println (colorize :blue "═══════════════════════════════════════════════════════════"))
    (println)

    (if (empty? env-files)
      (do
        (println (colorize :yellow "⚠ No .env files found in current directory or parents"))
        (println)
        (System/exit 0))
      (do
        (println (colorize :cyan (str "Found " (count env-files) " .env file(s):")))
        (doseq [file env-files]
          (let [dir (str (fs/parent file))]
            (println (str "  " (colorize :gray (str dir "/")) ".env"))))
        (println)))

    ;; Handle specific var trace
    (when-let [var (:var parsed-args)]
      (trace-var env-files var)
      (System/exit 0))

    ;; Find conflicts
    (let [conflicts (find-conflicts env-files)]
      ;; Show conflicts
      (show-conflicts env-files conflicts)

      ;; If --conflicts flag, stop here
      (when (:conflicts-only parsed-args)
        (System/exit 0))

      ;; Show separator if we had conflicts
      (when (seq conflicts)
        (println (colorize :gray "───────────────────────────────────────────────────────────"))
        (println))

      ;; Show all vars
      (show-all-vars env-files conflicts)

      (println (colorize :gray "───────────────────────────────────────────────────────────"))
      (println)
      (println (colorize :cyan "Tips:"))
      (println (str "  • Run: " (colorize :gray "env_debug.bb VAR_NAME") " to trace a specific variable"))
      (println (str "  • Run: " (colorize :gray "env_debug.bb --conflicts") " to see only conflicts"))
      (println (str "  • " (colorize :yellow "⚠") " means variable defined in multiple files"))
      (println))))

;; Run
(apply -main *command-line-args*)
