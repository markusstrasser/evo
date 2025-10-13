(ns core.demo
 "Demo script for the three-op kernel."
 (:require [core.interpret :as interp]
           [core.db :as db]))

(defn demo-script
 "Demonstrates the three-op kernel with the REPL script from the spec."
 []
 (println "=== Three-Op Kernel Demo ===")

 ;; Start with empty database
 (let [db (db/empty-db)]
  (println "\n1. Empty database:")
  (println "   Roots:" (:roots db))

  ;; Create and place some nodes (separate operations as per spec)
  (let [ops [{:op :create-node :id "doc1" :type :document :props {:title "My Document"}}
             {:op :place :id "doc1" :under :doc :at :last}
             {:op :create-node :id "para1" :type :paragraph :props {:content "Hello, world!"}}
             {:op :place :id "para1" :under "doc1" :at :first}
             {:op :create-node :id "para2" :type :paragraph :props {:content "Second paragraph"}}
             {:op :place :id "para2" :under "doc1" :at :last}
             {:op :update-node :id "para1" :props {:content "Hello, three-op kernel!"}}]

        result (interp/interpret db ops)]

   (println "\n2. After creating document structure:")
   (println "   Issues:" (count (:issues result)))
   (println "   Operations applied:" (count (:trace result)))
   (println "   Document children:" (get-in result [:db :children-by-parent "doc1"]))
   (println "   Para1 content:" (get-in result [:db :nodes "para1" :props :content]))

   ;; Test validation
   (let [validation (interp/validate (:db result))]
    (println "\n3. Database validation:")
    (println "   Valid?" (:ok? validation))
    (println "   Errors:" (count (:errors validation))))

   ;; Test cycle detection
   (let [cycle-ops [{:op :place :id "doc1" :under "para1" :at :last}]
         cycle-result (interp/interpret (:db result) cycle-ops)]
    (println "\n4. Cycle detection test:")
    (println "   Issues:" (count (:issues cycle-result)))
    (println "   Issue type:" (get-in cycle-result [:issues 0 :issue])))

   ;; Test describe-ops
   (let [schemas (interp/describe-ops)]
    (println "\n5. Available schemas:")
    (println "   Schema keys:" (keys schemas)))

    (println "\n=== Demo Complete ===")
    result)))

(defn init!
  "Initialize demo for browser context"
  []
 (println "Initializing three-op kernel demo...")
 (demo-script))


