(ns repl.malli-patterns-validation
  "REPL validation for Malli-inspired patterns"
  (:require [core.schema :as schema]
            [core.db :as db]
            [core.ops :as ops]
            [malli.core :as m]))

(comment
  (require '[repl :as repl])
  (repl/init!)
  (repl/cljs!)

  (println "\n=== Pattern #1: Schema-as-Data ===")
  (def user-op {:op :create-node :id "u1" :type :user :props {:name "Alice"}})
  (schema/valid-op? user-op)

  (def dynamic-schema
    [:map
     [:op [:= :create-node]]
     [:id :string]
     [:type :keyword]
     [:props [:map [:name :string]]]])
  (m/validate dynamic-schema user-op)

  (println "\n=== Pattern #2: Transformation Pipelines ===")
  (def raw-input {:op "create-node" :id "n1" :type "paragraph" :props {:text "hello"} :extra "data"})
  (def decoded (schema/decode-op raw-input))
  (schema/valid-op? decoded)
  (contains? decoded :extra)

  (def test-db (db/empty-db))
  (def encoded-db (schema/encode-db test-db))
  (def decoded-db (schema/decode-db encoded-db))
  (= test-db decoded-db)

  (println "\n=== Pattern #3: Function Schemas with Guards ===")
  (def result-db (-> (db/empty-db)
                     (ops/create-node "n1" :paragraph {:text "test"})))
  (schema/valid-db? result-db)

  (def result-with-placement
    (-> (db/empty-db)
        (ops/create-node "n1" :paragraph {})
        (db/derive-indexes)
        (ops/place "n1" :doc :first)
        (db/derive-indexes)))
  (schema/valid-db? result-with-placement)

  (println "\n=== Pattern #4: Generative Testing ===")
  (def gen-op (schema/generate-op))
  (schema/valid-op? gen-op)

  (def gen-create (schema/generate-create-op))
  (= :create-node (:op gen-create))

  (def gen-place (schema/generate-place-op))
  (= :place (:op gen-place))

  (def gen-update (schema/generate-update-op))
  (= :update-node (:op gen-update))

  (def gen-tx (schema/generate-transaction 5))
  (= 5 (count gen-tx))
  (every? schema/valid-op? gen-tx)

  (dotimes [_ 10]
    (let [op (schema/generate-op)]
      (assert (schema/valid-op? op) "Generated op must be valid")))
  (println "Generated 10 valid operations ✓")

  (println "\n=== Pattern #5: Instrumentation ===")
  (schema/instrument-ops! 'core.ops)
  (println "Instrumentation configured (Clojure only)")

  (println "\n=== Pattern #6: Schema Inference ===")
  (def sample-nodes
    [{:type :paragraph :props {:text "hello" :bold true}}
     {:type :paragraph :props {:text "world" :bold false}}])
  (def inferred (schema/infer-node-schema sample-nodes))
  (m/validate inferred (first sample-nodes))

  (schema/infer-op-schema {:op :create-node})
  (schema/infer-op-schema {:op :place})
  (schema/infer-op-schema {:op :update-node})

  (println "\n=== Pattern #8: Performance-Optimized Validation ===")
  (def fast-op {:op :create-node :id "n1" :type :p :props {}})
  (time (dotimes [_ 1000] (schema/validate-op-fast fast-op)))

  (def slow-op {:op :create-node :id "n1" :type :p :props {}})
  (time (dotimes [_ 1000] (schema/valid-op? slow-op)))

  (time (dotimes [_ 1000] (schema/validate-db-fast (db/empty-db))))

  (def invalid-op {:op :invalid})
  (schema/validate-op-fast invalid-op)
  (schema/explain-human schema/Op invalid-op)

  (println "\n=== All Patterns Validated ===")
  (println "✓ Pattern #1: Schema-as-Data")
  (println "✓ Pattern #2: Transformation Pipelines")
  (println "✓ Pattern #3: Function Schemas with Guards")
  (println "✓ Pattern #4: Generative Testing")
  (println "✓ Pattern #5: Instrumentation (Clojure)")
  (println "✓ Pattern #6: Schema Inference")
  (println "✓ Pattern #8: Performance Optimization")

  :validation-complete)