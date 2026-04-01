#!/usr/bin/env bb

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(def scan-targets
  ["src/kernel"
   "test/kernel"
   "test/scripts"])

(def forbidden-require-pattern
  #"\[(shell|components|keymap|view)\.[^\]\s]+")

(defn clj-file? [file]
  (and (.isFile file)
       (re-matches #".*\.(clj|cljs|cljc)" (.getName file))))

(defn boundary-issues []
  (for [root scan-targets
        file (file-seq (io/file root))
        :when (clj-file? file)
        :let [path (str file)
              lines (-> path slurp str/split-lines)]
        [line-num line] (map-indexed vector lines)
        :when (re-find forbidden-require-pattern line)]
    {:path path
     :line (inc line-num)
     :issue (str "forbidden dependency in extraction surface: " (str/trim line))}))

(defn main []
  (println "Verifying kernel extraction boundaries...")
  (let [issues (boundary-issues)]
    (if (seq issues)
      (do
        (println)
        (doseq [{:keys [path line issue]} issues]
          (println (str path ":" line " - " issue)))
        (System/exit 1))
      (println "✓ Kernel and script surfaces avoid shell/component/keymap/view requires"))))

(main)
