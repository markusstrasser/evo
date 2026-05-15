#!/usr/bin/env bb

;; Self-test for bb lint:registry-state.
;;
;; Cleanup contract: every per-case injection restores the bindings file in
;; a finally block, and System/exit is only called after restoration. JVM
;; System.exit skips in-flight finally blocks, so an exit inside the try
;; would leak the mutated fixture and break every later bb check run.

(require '[babashka.process :as p]
         '[clojure.java.io :as io])

(def path "src/keymap/bindings_data.cljc")

(defn run-lint []
  (let [res (p/sh "bb" "lint:registry-state")]
    {:exit (:exit res) :out (:out res) :err (:err res)}))

(defn run-case
  "Apply inject-fn to the bindings file, run the lint, and return either nil
   (success) or a failure map. The bindings file is always restored before
   returning."
  [label inject-fn category-substring]
  (let [original (slurp path)]
    (println (format "[selftest] case: %s" label))
    (spit path (inject-fn original))
    (try
      (let [{:keys [exit out err]} (run-lint)
            combined (str out err)]
        (cond
          (zero? exit)
          {:label label
           :msg "lint did NOT fail"
           :detail combined}

          (not (re-find (re-pattern category-substring) combined))
          {:label label
           :msg (format "lint failed, but output missing expected category: %s"
                        category-substring)
           :detail combined}

          :else
          (do (println "  ✓ caught") nil)))
      (finally
        (spit path original)))))

(defn run-multi-file-case
  "Apply each [path inject-fn] simultaneously, run the lint, then restore all
   files unconditionally. Returns nil on success or a failure map."
  [label injections category-substring]
  (let [originals (into {} (for [[p _] injections] [p (slurp p)]))]
    (println (format "[selftest] case: %s" label))
    (doseq [[p f] injections]
      (spit p (f (get originals p))))
    (try
      (let [{:keys [exit out err]} (run-lint)
            combined (str out err)]
        (cond
          (zero? exit)
          {:label label
           :msg "lint did NOT fail"
           :detail combined}

          (not (re-find (re-pattern category-substring) combined))
          {:label label
           :msg (format "lint failed, but output missing expected category: %s"
                        category-substring)
           :detail combined}

          :else
          (do (println "  ✓ caught") nil)))
      (finally
        (doseq [[p orig] originals]
          (spit p orig))))))

(defn inject-unknown-intent [src]
  (clojure.string/replace src
    "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]"
    (str "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]\n            "
         "[{:key \"j\" :mod true} :__selftest_phantom_intent]")))

(defn inject-duplicate-key [src]
  (clojure.string/replace src
    "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]"
    (str "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]\n            "
         "[{:key \"k\" :mod true} :undo]")))

(defn inject-block-owned-enter [src]
  (clojure.string/replace src
    "[{:key \"b\" :mod true} {:type :format-selection :marker \"**\"}]"
    (str "[{:key \"b\" :mod true} {:type :format-selection :marker \"**\"}]\n             "
         "[{:key \"Enter\"} :__selftest_block_owned]")))

(defn inject-block-owned-arrow-left [src]
  ;; Cursor-key navigation in editing is browser-owned. Injecting ArrowLeft
  ;; into :editing MUST trip block-owned-in-editing.
  (clojure.string/replace src
    "[{:key \"b\" :mod true} {:type :format-selection :marker \"**\"}]"
    (str "[{:key \"b\" :mod true} {:type :format-selection :marker \"**\"}]\n             "
         "[{:key \"ArrowLeft\"} :__selftest_block_owned]")))

(defn inject-block-owned-shift-arrow-right [src]
  ;; Editing Shift+Arrow is contenteditable-owned (CLAUDE.md). Shift+Right
  ;; is a regression case the original block-owned set was missing.
  (clojure.string/replace src
    "[{:key \"b\" :mod true} {:type :format-selection :marker \"**\"}]"
    (str "[{:key \"b\" :mod true} {:type :format-selection :marker \"**\"}]\n             "
         "[{:key \"ArrowRight\" :shift true} :__selftest_block_owned]")))

(defn inject-binding-only [src]
  ;; Inject a keybinding that targets an intent we will then claim to
  ;; "register" via a commented form in a plugin file. With correct
  ;; comment stripping, the lint MUST reject this; without stripping, it
  ;; would falsely accept.
  (clojure.string/replace src
    "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]"
    (str "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]\n            "
         "[{:key \"j\" :mod true} :__selftest_commented_intent]")))

(defn inject-commented-registration-in-plugin [src]
  ;; Append a commented-out registration to a real plugin file. The intent
  ;; name must NOT show up in the discovered set after comment stripping.
  (str src
       "\n;; (register-intent! :__selftest_commented_intent {:doc \"phantom\"})\n"))

(defn -main []
  (println "[selftest] Confirming baseline lint passes before injecting violations…")
  (let [{:keys [exit out]} (run-lint)]
    (when-not (zero? exit)
      (println "[selftest] ✗ baseline lint already fails — fix that first.")
      (println out)
      (System/exit 1)))

  (let [cases [["unknown intent target"        inject-unknown-intent              "unknown-intents"]
               ["duplicate normalized key"     inject-duplicate-key               "duplicate-keys"]
               ["block-owned Enter in :editing" inject-block-owned-enter          "block-owned-in-editing"]
               ["block-owned ArrowLeft in :editing" inject-block-owned-arrow-left "block-owned-in-editing"]
               ["block-owned Shift+ArrowRight in :editing"
                inject-block-owned-shift-arrow-right                              "block-owned-in-editing"]]
        case-failures (vec (keep (fn [[label inject category]]
                                   (run-case label inject category))
                                 cases))
        multi-failure (run-multi-file-case
                        "commented registration not counted (real plugin file)"
                        {path                       inject-binding-only
                         "src/plugins/folding.cljc" inject-commented-registration-in-plugin}
                        "unknown-intents")
        failures (cond-> case-failures
                   multi-failure (conj multi-failure))]
    (if (seq failures)
      (do
        (println "[selftest] ✗ failures:")
        (doseq [{:keys [label msg detail]} failures]
          (println (format "  - %s: %s" label msg))
          (println detail))
        (System/exit 2))
      (println "[selftest] ✓ all injected violations caught"))))

(-main)
