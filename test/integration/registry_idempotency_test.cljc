(ns integration.registry-idempotency-test
  "Phase 1c verification from
   docs/research/2026-05-15-feature-module-architecture-plan.md:
   the explicit bootstrap surfaces (plugins, keymap, render) must produce
   byte-identical registry snapshots when called twice in the same JVM."
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing use-fixtures]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer [deftest is testing use-fixtures]])
            [harness.runtime-fixtures :as runtime-fixtures]
            [kernel.derived-registry :as derived]
            [kernel.intent :as intent]
            [keymap.bindings :as keymap-bindings]
            [keymap.core :as keymap-core]
            [plugins.manifest :as plugins-manifest]
            [shell.render-manifest :as render-manifest]
            [shell.render-registry :as render-registry]))

(use-fixtures :once runtime-fixtures/bootstrap-runtime)

(defn- intent-fingerprint []
  ;; list-intents strips :handler/:validator; the remainder (:doc :spec :version)
  ;; must be stable across re-init.
  (intent/list-intents))

(defn- derived-fingerprint []
  ;; Drop functions (`:initial`, `:apply-tx`) because Clojure equality on fns
  ;; is identity-based, which still works inside one JVM but reads cleaner.
  (into {} (for [[k spec] (derived/registered)]
             [k (dissoc spec :initial :apply-tx)])))

(defn- render-fingerprint []
  (render-registry/registered-tags))

(defn- keymap-fingerprint []
  @keymap-core/!keymap-registry)

(deftest plugins-manifest-init-is-idempotent
  (testing "two consecutive plugins.manifest/init! calls leave the same intent + derived snapshot"
    (plugins-manifest/init!)
    (let [intents-after-first (intent-fingerprint)
          derived-after-first (derived-fingerprint)]
      (plugins-manifest/init!)
      (is (= intents-after-first (intent-fingerprint))
          "Re-init must not change registered intent metadata.")
      (is (= derived-after-first (derived-fingerprint))
          "Re-init must not change derived plugin registrations."))))

(deftest render-manifest-init-is-idempotent
  (testing "two consecutive shell.render-manifest/init! calls leave the same render tag set"
    (render-manifest/init!)
    (let [tags-after-first (render-fingerprint)]
      (render-manifest/init!)
      (is (= tags-after-first (render-fingerprint))
          "Re-init must not add or drop render tags."))))

(deftest keymap-bindings-reload-is-idempotent
  (testing "two consecutive keymap.bindings/reload! calls leave the same keymap registry"
    (keymap-bindings/reload!)
    (let [snapshot (keymap-fingerprint)]
      (keymap-bindings/reload!)
      (is (= snapshot (keymap-fingerprint))
          "Re-reload must not duplicate or drop bindings."))))
