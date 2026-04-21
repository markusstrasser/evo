#!/usr/bin/env bb
;; Rename-escape lint.
;;
;; Catches the specific failure class that bit us in commit 3b6fdcd7:
;; a local was renamed to avoid shadowing a core fn (e.g. `key-name`),
;; but somewhere in the same file a bare reference to the un-renamed
;; form (`key`) still appears. CLJS silently resolves the bare name to
;; `cljs.core/<name>`, and downstream code that stringifies the value
;; gets the compiled function source instead of whatever the author
;; expected.
;;
;; clj-kondo's :shadowed-var doesn't catch this: the bare reference
;; isn't shadowed — there's no local to shadow. The writer is just
;; *missing* the `-name` suffix on that one call site.
;;
;; Heuristic:
;;   1. Find bindings of shape `<prefix>-name` where `<prefix>` is a
;;      known-dangerous core fn name.
;;   2. For each prefix, find all `defn` / `fn` forms in the file that
;;      bind `<prefix>` as a parameter; treat their body ranges as
;;      safe zones (bare `<prefix>` in scope of a real parameter is
;;      legitimate).
;;   3. In every other position, flag bare `<prefix>` that is NOT:
;;        - the first token after `(` (fn-call position — legit core use)
;;        - a keyword (`:key`)
;;        - part of a longer identifier (`key-name`, `keys`, `keyword`)
;;        - inside a string or comment
;;
;; Exit non-zero if any warnings.

(ns lint-rename-escape
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]))

(def dangerous-prefixes
  "Core CLJS fns whose names collide with common local-variable names.
   Each has been shadowed or renamed somewhere in the codebase."
  #{"key" "val" "type" "name" "count" "first" "last"
    "get" "set" "keys" "vals"})

(defn- strip-strings-and-comments
  "Blank string literals and line comments while preserving line count."
  [content]
  (-> content
      (str/replace #"\"(?:[^\"\\]|\\.)*\""
                   (fn [s]
                     (apply str (map #(if (= % \newline) \newline \space) s))))
      (str/replace #";[^\n]*" (fn [s] (apply str (repeat (count s) \space))))))

(defn- find-rename-bindings
  "Return set of prefixes for which the file binds `<prefix>-name`."
  [content]
  (->> (re-seq #"\b([a-z]+)-name\b" content)
       (map second)
       (filter dangerous-prefixes)
       set))

(defn- param-vector-symbols
  "Given a params vector node, return set of parameter symbol names.
   Expands basic destructuring: `[{:keys [a b]} c & rest]` →
   #{\"a\" \"b\" \"c\" \"rest\"}. Over-includes on purpose — better
   to miss a warning than raise a false one."
  [params-node]
  (let [sexpr (try (n/sexpr params-node) (catch Exception _ nil))]
    (->> (tree-seq coll? seq sexpr)
         (filter symbol?)
         (map str)
         set)))

(defn- fn-param-scopes
  "Walk the file and return [{:prefix <str> :start-row <n> :end-row <n>}]
   for every `defn` / `defn-` / `fn` form whose parameter vector binds
   a dangerous prefix. Row numbers are 1-based. Multi-arity forms
   flatten to one scope per arity."
  [content]
  (let [zloc (z/of-string content {:track-position? true})]
    (loop [loc (z/find-next zloc z/next
                            #(contains? #{'defn 'defn- 'fn}
                                        (try (first (n/sexpr (z/node %)))
                                             (catch Exception _ nil))))
           scopes []]
      (if-not loc
        scopes
        (let [node (z/node loc)
              [row _] (z/position loc)
              end-row (+ row (dec (count (str/split-lines (n/string node)))))
              ;; Collect every vector child — in a defn these are the
              ;; parameter lists (one for single-arity, multiple for
              ;; multi-arity).
              param-vecs (->> (n/children node)
                              (filter #(= :vector (n/tag %))))
              bound (set (mapcat param-vector-symbols param-vecs))
              hits (keep (fn [p] (when (bound p)
                                   {:prefix p :start-row row :end-row end-row}))
                         dangerous-prefixes)
              next-loc (z/find-next loc z/next
                                    #(contains? #{'defn 'defn- 'fn}
                                                (try (first (n/sexpr (z/node %)))
                                                     (catch Exception _ nil))))]
          (recur next-loc (into scopes hits)))))))

(defn- row-in-scope?
  [row scopes prefix]
  (some (fn [{p :prefix s :start-row e :end-row}]
          (and (= p prefix) (<= s row e)))
        scopes))

(defn- bare-use-locations
  "Return [{:row :col :text}] for every problematic bare use of prefix."
  [content prefix scopes]
  (let [pat (re-pattern (str "(?<![\\w\\-:#])" prefix "(?![\\w\\-])"))
        lines (str/split-lines content)]
    (->> lines
         (map-indexed
          (fn [idx line]
            (let [row (inc idx)]
              (when-not (row-in-scope? row scopes prefix)
                (when-let [m (re-find pat line)]
                  (let [col (.indexOf line prefix)
                        prev-char (when (pos? col) (.charAt line (dec col)))]
                    ;; Skip legitimate fn-call position: `(<prefix> …)`.
                    (when-not (= prev-char \()
                      {:row row
                       :col (inc col)
                       :text (str/triml line)})))))))
         (remove nil?))))

(defn- lint-file
  [f]
  (let [raw (slurp f)
        clean (strip-strings-and-comments raw)
        prefixes (find-rename-bindings clean)]
    (when (seq prefixes)
      (let [scopes (fn-param-scopes raw)]
        (mapcat (fn [prefix]
                  (->> (bare-use-locations clean prefix scopes)
                       (map #(assoc % :file (str f) :prefix prefix))))
                prefixes)))))

(defn -main [& _]
  (let [files (->> (concat (fs/glob "src" "**/*.cljs")
                           (fs/glob "src" "**/*.cljc"))
                   (map str)
                   sort)
        warnings (mapcat lint-file files)]
    (doseq [{:keys [file row col prefix text]} warnings]
      (println
       (format "%s:%d:%d  bare '%s' — file binds '%s-name'; did you mean '%s-name'?"
               file row col prefix prefix prefix))
      (println (format "    %s" text)))
    (if (seq warnings)
      (do
        (println)
        (println (format "✗ %d potential rename-escape issue(s)" (count warnings)))
        (System/exit 1))
      (println "✓ No rename-escape issues"))))

(-main)
