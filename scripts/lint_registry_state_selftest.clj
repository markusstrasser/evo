#!/usr/bin/env bb

;; Selftest: temporarily mutate src/keymap/bindings_data.cljc to inject one
;; violation at a time, run `bb lint:registry-state`, assert it fails with
;; the expected category, and restore the file.

(require '[babashka.process :as p]
         '[clojure.java.io :as io])

(def path "src/keymap/bindings_data.cljc")

(defn run-lint []
  (let [res (p/sh "bb" "lint:registry-state")]
    {:exit (:exit res) :out (:out res) :err (:err res)}))

(defn expect-fail [label inject-fn category-substring]
  (let [original (slurp path)]
    (println (format "[selftest] case: %s" label))
    (spit path (inject-fn original))
    (try
      (let [{:keys [exit out err]} (run-lint)
            combined (str out err)]
        (when (zero? exit)
          (println "  ✗ lint did NOT fail")
          (println combined)
          (System/exit 2))
        (when-not (re-find (re-pattern category-substring) combined)
          (println (format "  ✗ lint failed, but output missing expected category: %s"
                           category-substring))
          (println combined)
          (System/exit 3))
        (println "  ✓ caught"))
      (finally
        (spit path original)))))

(defn inject-unknown-intent [src]
  ;; Add a bogus binding under :global.
  (clojure.string/replace src
    "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]"
    (str "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]\n            "
         "[{:key \"j\" :mod true} :__selftest_phantom_intent]")))

(defn inject-duplicate-key [src]
  ;; Add a second :global row for Cmd+K pointing somewhere different.
  (clojure.string/replace src
    "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]"
    (str "[{:key \"k\" :mod true} {:type :toggle-quick-switcher}]\n            "
         "[{:key \"k\" :mod true} :undo]")))

(defn inject-block-owned-in-editing [src]
  ;; Inject an Enter binding into the :editing context. The block owns Enter
  ;; in editing mode, so this MUST fail the block-owned check.
  (clojure.string/replace src
    "[{:key \"b\" :mod true} {:type :format-selection :marker \"**\"}]"
    (str "[{:key \"b\" :mod true} {:type :format-selection :marker \"**\"}]\n             "
         "[{:key \"Enter\"} :__selftest_block_owned]")))

(defn -main []
  (println "[selftest] Confirming baseline lint passes before injecting violations…")
  (let [{:keys [exit out]} (run-lint)]
    (when-not (zero? exit)
      (println "[selftest] ✗ baseline lint already fails — fix that first.")
      (println out)
      (System/exit 1)))

  (expect-fail "unknown intent target"
               inject-unknown-intent
               "unknown-intents")
  (expect-fail "duplicate normalized key"
               inject-duplicate-key
               "duplicate-keys")
  (expect-fail "block-owned key in :editing"
               inject-block-owned-in-editing
               "block-owned-in-editing")

  (println "[selftest] ✓ all injected violations caught"))

(-main)
