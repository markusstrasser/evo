;; Direct Clojure verification of our multi-root + effects refactor
(require '[kernel.core :as K] '[kernel.invariants :as inv] '[malli.core :as m] '[kernel.schemas :as S])

(println "🧪 Verifying multi-root + effects refactor")

;; Test 1: Schema allows roots
(def schema-test (m/validate S/db-schema {:nodes {"root" {:type :root}} :children-by-parent-id {} :roots ["root"]}))
(println "✅ Schema test:" schema-test)
(assert schema-test "Schema should accept :roots")

;; Test 2: Multi-root traversal
(def db {:nodes {"root" {:type :root} "palette" {:type :root} "w" {:type :div}}
         :children-by-parent-id {"root" ["w"]}
         :roots ["root" "palette"]})
(def derived (K/*derive-pass* db))
(def preorder (get-in derived [:derived :preorder]))
(println "✅ Multi-root preorder:" preorder)
(assert (= preorder ["root" "w" "palette"]) "Multi-root preorder should be correct")

;; Test 3: Invariants pass
(def inv-pass (try (inv/check-invariants derived) true (catch Exception e false)))
(println "✅ Invariants pass:" inv-pass)
(assert inv-pass "Invariants should pass for multi-root")

;; Test 4: Effects detection
(def base {:nodes {"root" {:type :root}} :children-by-parent-id {}})
(def bundle (K/interpret-bundle* base {:op :ins :id "x" :parent-id "root"}))
(def effects (:effects bundle))
(println "✅ Effects detected:" (map :effect effects))
(assert (= (map :effect effects) [:view/scroll-into-view]) "Should detect scroll effect")

;; Test 5: Backward compatibility
(def old-result (:nodes (K/interpret* base {:op :ins :id "z" :parent-id "root"})))
(def new-result (:nodes (:db (K/interpret-bundle* base {:op :ins :id "z" :parent-id "root"}))))
(println "✅ Backward compatibility:" (= old-result new-result))
(assert (= old-result new-result) "Backward compatibility maintained")

(println "\n🎯 All verification tests passed! Multi-root + effects refactor complete.")