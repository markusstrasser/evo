#!/usr/bin/env bb
;; Block-id emission lint.
;;
;; The runtime keys off :data-block-id in two ways: the shell does
;; closest("[data-block-id]") to map a DOM event back to a block, and
;; Replicant uses element identity for keyed reconciliation. Two render
;; sites emitting the attribute would create either a silent ambiguity
;; (closest returns the first match) or a duplicate-key bug.
;;
;; Contract: src/components/block.cljs is the only source file allowed
;; to emit :data-block-id as a hiccup attribute. Other files may read it
;; via querySelector/closest or assert it in tests, but they must not
;; introduce a second emission point.
;;
;; What we flag: a literal `:data-block-id` token appearing in any
;; src/**/*.{cljs,cljc} file other than the canonical emitter, in a
;; position that isn't a JS DOM string ("data-block-id" inside a
;; querySelector / closest call etc.).

(ns lint-block-id-emission
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def canonical-emitter "src/components/block.cljs")

(defn- offending-lines
  "Return [{:line :col :text}] for hiccup-attribute uses of :data-block-id."
  [content]
  (let [lines (str/split-lines content)]
    (->> lines
         (map-indexed
          (fn [idx line]
            (let [row (inc idx)
                  ;; Match the keyword token `:data-block-id` not preceded
                  ;; by a quote (which would mean it's part of a JS string
                  ;; passed to querySelector / closest).
                  pat #"(?<![\"'])\:data-block-id\b"]
              (when (re-find pat line)
                {:line row
                 :col (inc (str/index-of line ":data-block-id"))
                 :text (str/triml line)}))))
         (remove nil?))))

(defn- lint-file [f]
  (let [path (str f)]
    (when-not (= path canonical-emitter)
      (let [content (slurp f)]
        (->> (offending-lines content)
             (map #(assoc % :file path)))))))

(defn -main [& _]
  (let [files (->> (concat (fs/glob "src" "**/*.cljs")
                           (fs/glob "src" "**/*.cljc"))
                   (map str)
                   sort)
        warnings (mapcat lint-file files)]
    (doseq [{:keys [file line col text]} warnings]
      (println
       (format "%s:%d:%d  :data-block-id emitted outside %s"
               file line col canonical-emitter))
      (println (format "    %s" text)))
    (if (seq warnings)
      (do
        (println)
        (println
         (format "✗ %d :data-block-id emission(s) outside %s"
                 (count warnings) canonical-emitter))
        (println
         "   Move the emission to components/block.cljs and reach the id via DOM traversal")
        (System/exit 1))
      (println "✓ data-block-id emission stays in components/block.cljs"))))

(-main)
