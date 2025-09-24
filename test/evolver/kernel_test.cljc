(ns evolver.kernel-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [datascript.core :as d]
            [kernel-min :as kernel]))

(defn- tree-fixture []
  (doto (d/create-conn kernel/schema)
    (d/transact! [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (kernel/insert! {:id "a"} {:parent "root"})
    (kernel/insert! {:id "b"} {:parent "root"})
    (kernel/insert! {:id "c"} {:parent "a"})))

(deftest basic-insert-test
  (let [conn (tree-fixture)]
    (is (= '("a" "b") (kernel/children-ids @conn "root")))
    (is (= '("c") (kernel/children-ids @conn "a")))))

(deftest position-resolution-test
  (let [conn (tree-fixture)]
    (kernel/insert! conn {:id "d"} {:parent "root" :idx 1})
    (is (= '("a" "d" "b") (kernel/children-ids @conn "root")))

    (kernel/insert! conn {:id "e"} {:parent "root" :rel :first})
    (is (= '("e" "a" "d" "b") (kernel/children-ids @conn "root")))

    (kernel/insert! conn {:id "f"} {:parent "root" :rel :last})
    (is (= '("e" "a" "d" "b" "f") (kernel/children-ids @conn "root")))

    (kernel/insert! conn {:id "g"} {:sibling "d" :rel :before})
    (is (= '("e" "a" "g" "d" "b" "f") (kernel/children-ids @conn "root")))

    (kernel/insert! conn {:id "h"} {:sibling "d" :rel :after})
    (is (= '("e" "a" "g" "d" "h" "b" "f") (kernel/children-ids @conn "root")))))

(deftest tree-operations-test
  (let [conn (tree-fixture)]
    (kernel/move! conn "c" {:parent "root"})
    (is (= '("a" "b" "c") (kernel/children-ids @conn "root")))
    (is (= '() (kernel/children-ids @conn "a")))
    (kernel/delete! conn "b")
    (is (= '("a" "c") (kernel/children-ids @conn "root")))))

(deftest cycle-detection-test
  (let [conn (tree-fixture)]
    (is (thrown? #?(:clj Exception :cljs js/Error) (kernel/move! conn "root" {:parent "a"})))))

(deftest splice-test
  (is (= [1 2 99 3 4] (kernel/splice [1 2 3 4] 2 99)))
  (is (= [99 1 2 3 4] (kernel/splice [1 2 3 4] 0 99)))
  (is (= [1 2 3 4 99] (kernel/splice [1 2 3 4] 10 99)))
  (is (= [1 2 3 4 99] (kernel/splice [1 2 3 4] nil 99))))

(deftest entity-lookup-test
  (let [conn (tree-fixture)]
    (is (= "root" (:id (kernel/entity-by-id @conn "root"))))
    (is (= "a" (:id (kernel/entity-by-id @conn "a"))))
    (is (nil? (kernel/entity-by-id @conn "nonexistent")))))

(deftest update-test
  (let [conn (tree-fixture)]
    (kernel/update! conn "a" {:name "updated-a" :value 42})
    (let [entity (kernel/entity-by-id @conn "a")]
      (is (= "updated-a" (:name entity)))
      (is (= 42 (:value entity))))))

(deftest auto-id-generation-test
  (let [conn (d/create-conn kernel/schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (kernel/insert! conn {:name "auto-node"} {:parent "root"})
    (let [children (kernel/children-ids @conn "root")]
      (is (= 1 (count children)))
      (is (.startsWith (first children) "auto-")))))

(deftest threaded-operations-test
  (let [conn (d/create-conn kernel/schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (doto conn
      (kernel/insert! {:id "a"} {:parent "root"})
      (kernel/insert! {:id "b"} {:parent "root"})
      (kernel/insert! {:id "c"} {:parent "a"}))
    (is (= '("a" "b") (kernel/children-ids @conn "root")))
    (is (= '("c") (kernel/children-ids @conn "a")))

    ; Test threaded move and delete
    (doto conn
      (kernel/move! "c" {:parent "root"}))
    (is (= '("a" "b" "c") (kernel/children-ids @conn "root")))
    (is (= '() (kernel/children-ids @conn "a")))

    (doto conn
      (kernel/delete! "b"))
    (is (= '("a" "c") (kernel/children-ids @conn "root")))))

(deftest nested-tree-operations-test
  (let [conn (d/create-conn kernel/schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (kernel/insert! conn {:id "level1"} {:parent "root"})
    (kernel/insert! conn {:id "level2"} {:parent "level1"})
    (kernel/insert! conn {:id "level3"} {:parent "level2"})
    (kernel/insert! conn {:id "level4"} {:parent "level3"})
    (is (= '("level1") (kernel/children-ids @conn "root")))
    (is (= '("level2") (kernel/children-ids @conn "level1")))
    (is (= '("level3") (kernel/children-ids @conn "level2")))
    (is (= '("level4") (kernel/children-ids @conn "level3")))

    ; Test moving a deep node
    (kernel/move! conn "level4" {:parent "root"})
    (is (= '("level1" "level4") (kernel/children-ids @conn "root")))
    (is (= '() (kernel/children-ids @conn "level3")))
    (is (thrown? #?(:clj Exception :cljs js/Error) (kernel/move! conn "level1" {:parent "level2"})))))

(deftest position-edge-cases-test
  (let [conn (d/create-conn kernel/schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (kernel/insert! conn {:id "a"} {:parent "root"})
    (is (thrown? #?(:clj Exception :cljs js/Error) (kernel/insert! conn {:id "b"} {:sibling "nonexistent" :rel :before})))
    (is (thrown? #?(:clj Exception :cljs js/Error) (kernel/insert! conn {:id "c"} {:sibling "nonexistent" :rel :after})))
    (kernel/insert! conn {:id "d"} {:parent "root" :idx 100})
    (kernel/insert! conn {:id "d"} {:parent "root" :idx 100})
    (is (= '("a" "d") (kernel/children-ids @conn "root")))))

(deftest api-composition-test
  (let [conn (d/create-conn kernel/schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])

    ; Test composing operations
    (kernel/insert! conn {:id "composed-node" :type "test"} {:parent "root"})

    (is (= '("composed-node") (kernel/children-ids @conn "root")))
    (let [node-id (->> (kernel/children-ids @conn "root") first)]
      (is (= "composed-node" (:id (kernel/entity-by-id @conn node-id)))))))

(deftest cross-reference-relationships-test
  (let [conn (tree-fixture)]
    (kernel/insert! conn {:id "form", :type "form"} {:parent "root"})
    (kernel/insert! conn {:id "input", :type "input", :name "email"} {:parent "form"})
    (kernel/insert! conn {:id "validator", :type "validator", :pattern "email"} {:parent "root"})
    (kernel/insert! conn {:id "submit-btn", :type "button"} {:parent "form"})
    (kernel/insert! conn {:id "api-endpoint", :type "service"} {:parent "root"})
    (kernel/update! conn "input" {:references [[:id "validator"]]})
    (kernel/update! conn "submit-btn" {:references [[:id "api-endpoint"]]})
    (kernel/update! conn "form" {:references [[:id "input"] [:id "submit-btn"]]})

    ; Verify relationships exist
    (is (= 1 (count (:references (kernel/entity-by-id @conn "input")))))
    (is (= 1 (count (:references (kernel/entity-by-id @conn "submit-btn")))))
    (is (= 2 (count (:references (kernel/entity-by-id @conn "form")))))
    (let [validator-users (d/q '[:find [?id ...] :in $ ?validator-ref :where [?e :references ?validator-ref] [?e :id ?id]] @conn [:id "validator"])]
      (is (= ["input"] validator-users)))))

(deftest referential-integrity-test
  (let [conn (tree-fixture)]
    (kernel/insert! conn {:id "dialog", :type "dialog"} {:parent "root"})
    (kernel/insert! conn {:id "button", :type "button"} {:parent "dialog"})
    (kernel/insert! conn {:id "action", :type "action"} {:parent "root"})

    ; Create reference
    (kernel/update! conn "button" {:references [[:id "action"]]})
    (is (= 1 (count (:references (kernel/entity-by-id @conn "button")))))

    ; Delete referenced entity - DataScript removes all references to deleted entities
    (kernel/delete! conn "action")

    ; References are automatically cleaned up by DataScript
    (let [button-entity (kernel/entity-by-id @conn "button")
          refs (:references button-entity)]
      (is (= 0 (count refs)))) ; References are removed

    ; Test with multiple references
    (kernel/insert! conn {:id "service1", :type "service"} {:parent "root"})
    (kernel/insert! conn {:id "service2", :type "service"} {:parent "root"})
    (kernel/update! conn "button" {:references [[:id "service1"] [:id "service2"]]})

    (kernel/delete! conn "service1")
    ; One reference is removed, other remains
    (let [refs (:references (kernel/entity-by-id @conn "button"))]
      (is (= 1 (count refs)))
      (is (= "service2" (:id (first refs)))))))

(deftest bidirectional-relationships-test
  (let [conn (tree-fixture)]
    (kernel/insert! conn {:id "parent-comp", :type "component"} {:parent "root"})
    (kernel/insert! conn {:id "child-comp", :type "component"} {:parent "parent-comp"})
    (kernel/insert! conn {:id "sibling-comp", :type "component"} {:parent "parent-comp"})
    (kernel/insert! conn {:id "external-service", :type "service"} {:parent "root"})

    ; Create bidirectional references using :references
    (kernel/update! conn "child-comp" {:references [[:id "sibling-comp"]]})
    (kernel/update! conn "sibling-comp" {:references [[:id "child-comp"]]})
    (kernel/update! conn "parent-comp" {:references [[:id "external-service"]]})
    (kernel/update! conn "external-service" {:references [[:id "parent-comp"]]})

    ; Test graph traversal patterns
    (let [; Find all components that reference each other
          mutual-refs (d/q '[:find ?id1 ?id2
                             :where [?e1 :id ?id1]
                             [?e1 :references ?ref]
                             [?ref :id ?id2]
                             [?e2 :id ?id2]
                             [?e2 :references ?back-ref]
                             [?back-ref :id ?id1]]
                           @conn)
          ; Find all reference relationships
          all-refs (d/q '[:find ?referrer ?referenced
                          :where [?e1 :id ?referrer]
                          [?e1 :references ?ref]
                          [?ref :id ?referenced]]
                        @conn)]
      (is (= #{["child-comp" "sibling-comp"] ["sibling-comp" "child-comp"]
               ["parent-comp" "external-service"] ["external-service" "parent-comp"]}
             (set mutual-refs)))
      (is (= 4 (count all-refs))))))

(deftest disconnected-subgraphs-test
  (let [conn (tree-fixture)]
    ; Create main tree
    (kernel/insert! conn {:id "app", :type "application"} {:parent "root"})
    (kernel/insert! conn {:id "main-view", :type "view"} {:parent "app"})

    ; Create disconnected entities (floating dialogs, background services)
    (kernel/insert! conn {:id "floating-dialog", :type "dialog", :floating true} {:parent "root"})
    (kernel/insert! conn {:id "background-service", :type "service", :background true} {:parent "root"})
    (kernel/insert! conn {:id "notification-system", :type "system"} {:parent "root"})

    ; These exist outside the main app hierarchy but can reference it
    (kernel/update! conn "floating-dialog" {:references [[:id "main-view"]]})
    (kernel/update! conn "background-service" {:references [[:id "app"]]})
    (kernel/update! conn "notification-system" {:references [[:id "app"] [:id "floating-dialog"]]})

    ; Verify disconnected entities exist
    (is (= "dialog" (:type (kernel/entity-by-id @conn "floating-dialog"))))
    (is (= "service" (:type (kernel/entity-by-id @conn "background-service"))))

    ; Test that they're not in main app subtree
    (let [app-subtree (d/q '[:find [?id ...]
                             :in $ % ?app-ref
                             :where (subtree-member ?app-ref ?d)
                             [?d :id ?id]]
                           @conn kernel/rules [:id "app"])]
      (is (not (contains? (set app-subtree) "floating-dialog")))
      (is (not (contains? (set app-subtree) "background-service")))
      (is (not (contains? (set app-subtree) "notification-system"))))

    ; But they can still reference app components
    (is (= 1 (count (:references (kernel/entity-by-id @conn "floating-dialog")))))
    (is (= 1 (count (:references (kernel/entity-by-id @conn "background-service")))))))

(deftest multiple-relationship-types-test
  (let [conn (tree-fixture)]
    (kernel/insert! conn {:id "ui-component", :type "component"} {:parent "root"})
    (kernel/insert! conn {:id "data-store", :type "store"} {:parent "root"})
    (kernel/insert! conn {:id "validator", :type "validator"} {:parent "root"})
    (kernel/insert! conn {:id "formatter", :type "formatter"} {:parent "root"})
    (kernel/insert! conn {:id "api-client", :type "client"} {:parent "root"})

    ; Add multiple references to same entity
    (kernel/update! conn "ui-component" {:references [[:id "data-store"] [:id "validator"]
                                               [:id "formatter"] [:id "api-client"]]})

    ; Test that component has multiple references
    (let [component (kernel/entity-by-id @conn "ui-component")]
      (is (= 4 (count (:references component)))))

    ; Query by references
    (let [store-users (d/q '[:find [?id ...]
                             :in $ ?store-ref
                             :where [?e :references ?store-ref]
                             [?e :id ?id]]
                           @conn [:id "data-store"])
          all-users (d/q '[:find [?id ...]
                           :where [?e :references ?v]
                           [?e :id ?id]]
                         @conn)]
      (is (= ["ui-component"] store-users))
      (is (= ["ui-component"] all-users)))))

;; Uncomment to run directly in REPL
;; (clojure.test/run-tests)