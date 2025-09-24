(ns kernel-min
  (:require [datascript.core :as d]
            [clojure.test :refer [deftest is run-tests]]))

(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
    :pos {:db/cardinality :db.cardinality/one :db/index true}
    :references {:db/valueType :db.type/ref
                :db/cardinality :db.cardinality/many}})

(def rules
  '[[(subtree-member ?a ?d) [?d :parent ?a]]
    [(subtree-member ?a ?d) [?d :parent ?m] (subtree-member ?a ?m)]])

(defn e [db id]
  (d/entity db [:id id]))

(defn children-ids [db parent-id]
  (->> (d/q '[:find [?child-id ...]
              :in $ ?parent-id
              :where [?parent-entity :id ?parent-id]
                     [?child-entity :parent ?parent-entity]
                     [?child-entity :id ?child-id]]
            db parent-id)
       (sort-by #(:pos (e db %)))))

(defn splice [v idx x]
  (let [i (min (max 0 (or idx (count v))) (count v))]
    (into (subvec v 0 i) (cons x (subvec v i)))))

(defn target [db {:keys [parent sibling rel idx] :as pos}]
  (let [parent-id (or parent
                      (:id (:parent (e db sibling)))
                      (throw (ex-info "No parent specified" {:position pos})))
        children-ids (children-ids db parent-id)
        relation-function (case rel
                            :first (constantly 0)
                            :last #(count %)
                            :before #(let [sibling-index (.indexOf % sibling)]
                                       (if (neg? sibling-index) (count %) sibling-index))
                            :after #(let [sibling-index (.indexOf % sibling)]
                                      (if (neg? sibling-index) (count %) (inc sibling-index)))
                            (fn [children] (min (or idx (count children)) (count children))))]
    [parent-id (relation-function children-ids)]))

(def cid (atom 0))

(defn insert! [conn entity pos]
  (let [db @conn
        entity-id (or (:id entity) (str "auto-" (swap! cid inc)))
        entity (assoc entity :id entity-id)
        [parent-id position-index] (target db pos)
        temp-entity-id (- (swap! cid inc))]
    ;; First, add the entity with its attributes
    (d/transact! conn
                 (into [[:db/add temp-entity-id :id entity-id]
                        [:db/add temp-entity-id :parent [:id parent-id]]]
                       (for [[k v] (dissoc entity :id :children)]
                         [:db/add [:id entity-id] k v])))
    ;; Then, update positions of siblings
    (let [current-siblings (children-ids @conn parent-id)
          reordered-siblings (splice (vec (remove #{entity-id} current-siblings)) position-index entity-id)]
      (d/transact! conn (mapv #(vector :db/add [:id %2] :pos %1) (range) reordered-siblings)))))

(defn move! [conn entity-id pos]
  (let [db @conn
        descendant-ids (set (d/q '[:find [?id ...] :in $ % ?p :where (subtree-member ?p ?d) [?d :id ?id]] db rules [:id entity-id]))
        [target-parent-id target-position-index] (target db pos)]
    ;; Prevent cycles by checking if target is a descendant
    (when (descendant-ids target-parent-id)
      (throw (ex-info "Cycle detected" {:entity entity-id :target target-parent-id})))
    (let [current-parent-id (:id (:parent (e db entity-id)))
          same-parent? (= current-parent-id target-parent-id)
          current-siblings (children-ids db current-parent-id)
          target-siblings (if same-parent? current-siblings (children-ids db target-parent-id))
          updated-current-siblings (when-not same-parent? (vec (remove #{entity-id} current-siblings)))
          updated-target-siblings (splice (vec (remove #{entity-id} target-siblings)) target-position-index entity-id)
          transactions (cond-> []
                         (not same-parent?) (conj [:db/add [:id entity-id] :parent [:id target-parent-id]])
                         (not same-parent?) (into (mapv #(vector :db/add [:id %2] :pos %1) (range) updated-current-siblings))
                         true (into (mapv #(vector :db/add [:id %2] :pos %1) (range) updated-target-siblings)))]
      (d/transact! conn transactions))))

(defn delete! [conn entity-id]
  (let [db @conn
        parent-id (:id (:parent (e db entity-id)))
        siblings (children-ids db parent-id)
        descendant-ids (d/q '[:find [?id ...] :in $ % ?p :where (subtree-member ?p ?d) [?d :id ?id]] db rules [:id entity-id])]
    ;; Delete the entity and all its descendants
    (d/transact! conn (mapv #(vector :db/retractEntity [:id %]) (cons entity-id descendant-ids)))
    ;; Update positions of remaining siblings
    (d/transact! conn (mapv #(vector :db/add [:id %2] :pos %1) (range) (remove #{entity-id} siblings)))))

(defn update! [conn entity-id attrs]
  (when (some #{:parent :pos :id} (keys attrs))
    (throw (ex-info "Cannot modify structural attributes" {:attrs (select-keys attrs [:parent :pos :id])})))
  (let [resolved-entity-id (if (string? entity-id) (:db/id (e @conn entity-id)) entity-id)
        transactions (for [[attr-key attr-value] attrs
                           ref (if (= attr-key :references)
                                 (map #(if (vector? %) (:db/id (e @conn (second %))) %) attr-value)
                                 [attr-value])]
                       [:db/add resolved-entity-id attr-key ref])]
    (d/transact! conn transactions)))

(defn- tree-fixture []
  (doto (d/create-conn schema)
    (d/transact! [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (insert! {:id "a"} {:parent "root"})
    (insert! {:id "b"} {:parent "root"})
    (insert! {:id "c"} {:parent "a"})))



(deftest basic-insert-test
  (let [conn (tree-fixture)]
    (is (= '("a" "b") (children-ids @conn "root")))
    (is (= '("c") (children-ids @conn "a")))))

(deftest position-resolution-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "d"} {:parent "root" :idx 1})
    (is (= '("a" "d" "b") (children-ids @conn "root")))

    (insert! conn {:id "e"} {:parent "root" :rel :first})
    (is (= '("e" "a" "d" "b") (children-ids @conn "root")))

    (insert! conn {:id "f"} {:parent "root" :rel :last})
    (is (= '("e" "a" "d" "b" "f") (children-ids @conn "root")))

    (insert! conn {:id "g"} {:sibling "d" :rel :before})
    (is (= '("e" "a" "g" "d" "b" "f") (children-ids @conn "root")))

    (insert! conn {:id "h"} {:sibling "d" :rel :after})
    (is (= '("e" "a" "g" "d" "h" "b" "f") (children-ids @conn "root")))))

(deftest tree-operations-test
  (let [conn (tree-fixture)]
    (move! conn "c" {:parent "root"})
    (is (= '("a" "b" "c") (children-ids @conn "root")))
    (is (= '() (children-ids @conn "a")))
    (delete! conn "b")
    (is (= '("a" "c") (children-ids @conn "root")))))

(deftest cycle-detection-test
  (let [conn (tree-fixture)]
    (is (thrown? Exception (move! conn "root" {:parent "a"})))))

(deftest splice-test
  (is (= [1 2 99 3 4] (splice [1 2 3 4] 2 99)))
  (is (= [99 1 2 3 4] (splice [1 2 3 4] 0 99)))
  (is (= [1 2 3 4 99] (splice [1 2 3 4] 10 99)))
  (is (= [1 2 3 4 99] (splice [1 2 3 4] nil 99))))

(deftest entity-lookup-test
  (let [conn (tree-fixture)]
    (is (= "root" (:id (e @conn "root"))))
    (is (= "a" (:id (e @conn "a"))))
    (is (nil? (e @conn "nonexistent")))))

(deftest update-test
  (let [conn (tree-fixture)]
    (update! conn "a" {:name "updated-a" :value 42})
    (let [entity (e @conn "a")]
      (is (= "updated-a" (:name entity)))
      (is (= 42 (:value entity))))))

(deftest auto-id-generation-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (insert! conn {:name "auto-node"} {:parent "root"})
    (let [children (children-ids @conn "root")]
      (is (= 1 (count children)))
      (is (.startsWith (first children) "auto-")))))

(deftest threaded-operations-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (doto conn
      (insert! {:id "a"} {:parent "root"})
      (insert! {:id "b"} {:parent "root"})
      (insert! {:id "c"} {:parent "a"}))
    (is (= '("a" "b") (children-ids @conn "root")))
    (is (= '("c") (children-ids @conn "a")))

    ; Test threaded move and delete
    (doto conn
      (move! "c" {:parent "root"}))
    (is (= '("a" "b" "c") (children-ids @conn "root")))
    (is (= '() (children-ids @conn "a")))

    (doto conn
      (delete! "b"))
    (is (= '("a" "c") (children-ids @conn "root")))))

(deftest nested-tree-operations-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (insert! conn {:id "level1"} {:parent "root"})
    (insert! conn {:id "level2"} {:parent "level1"})
    (insert! conn {:id "level3"} {:parent "level2"})
    (insert! conn {:id "level4"} {:parent "level3"})
    (is (= '("level1") (children-ids @conn "root")))
    (is (= '("level2") (children-ids @conn "level1")))
    (is (= '("level3") (children-ids @conn "level2")))
    (is (= '("level4") (children-ids @conn "level3")))

    ; Test moving a deep node
    (move! conn "level4" {:parent "root"})
    (is (= '("level1" "level4") (children-ids @conn "root")))
    (is (= '() (children-ids @conn "level3")))
    (is (thrown? Exception (move! conn "level1" {:parent "level2"})))))

(deftest position-edge-cases-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (insert! conn {:id "a"} {:parent "root"})
    (is (thrown? Exception (insert! conn {:id "b"} {:sibling "nonexistent" :rel :before})))
    (is (thrown? Exception (insert! conn {:id "c"} {:sibling "nonexistent" :rel :after})))
    (insert! conn {:id "d"} {:parent "root" :idx 100})
    (insert! conn {:id "d"} {:parent "root" :idx 100})
    (is (= '("a" "d") (children-ids @conn "root")))))

(deftest api-composition-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])

    ; Test composing operations
    (insert! conn {:id "composed-node" :type "test"} {:parent "root"})

    (is (= '("composed-node") (children-ids @conn "root")))
    (let [node-id (->> (children-ids @conn "root") first)]
      (is (= "composed-node" (:id (e @conn node-id)))))))

(deftest cross-reference-relationships-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "form", :type "form"} {:parent "root"})
    (insert! conn {:id "input", :type "input", :name "email"} {:parent "form"})
    (insert! conn {:id "validator", :type "validator", :pattern "email"} {:parent "root"})
    (insert! conn {:id "submit-btn", :type "button"} {:parent "form"})
    (insert! conn {:id "api-endpoint", :type "service"} {:parent "root"})
    (update! conn "input" {:references [[:id "validator"]]})
    (update! conn "submit-btn" {:references [[:id "api-endpoint"]]})
    (update! conn "form" {:references [[:id "input"] [:id "submit-btn"]]})

    ; Verify relationships exist
    (is (= 1 (count (:references (e @conn "input")))))
    (is (= 1 (count (:references (e @conn "submit-btn")))))
    (is (= 2 (count (:references (e @conn "form")))))
    (let [validator-users (d/q '[:find [?id ...] :in $ ?validator-ref :where [?e :references ?validator-ref] [?e :id ?id]] @conn [:id "validator"])]
      (is (= ["input"] validator-users)))))

(deftest referential-integrity-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "dialog", :type "dialog"} {:parent "root"})
    (insert! conn {:id "button", :type "button"} {:parent "dialog"})
    (insert! conn {:id "action", :type "action"} {:parent "root"})

    ; Create reference
    (update! conn "button" {:references [[:id "action"]]})
    (is (= 1 (count (:references (e @conn "button")))))

    ; Delete referenced entity - DataScript removes all references to deleted entities
    (delete! conn "action")

    ; References are automatically cleaned up by DataScript
    (let [button-entity (e @conn "button")
          refs (:references button-entity)]
      (is (= 0 (count refs)))) ; References are removed

    ; Test with multiple references
    (insert! conn {:id "service1", :type "service"} {:parent "root"})
    (insert! conn {:id "service2", :type "service"} {:parent "root"})
    (update! conn "button" {:references [[:id "service1"] [:id "service2"]]})

    (delete! conn "service1")
    ; One reference is removed, other remains
    (let [refs (:references (e @conn "button"))]
      (is (= 1 (count refs)))
      (is (= "service2" (:id (first refs)))))))

(deftest bidirectional-relationships-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "parent-comp", :type "component"} {:parent "root"})
    (insert! conn {:id "child-comp", :type "component"} {:parent "parent-comp"})
    (insert! conn {:id "sibling-comp", :type "component"} {:parent "parent-comp"})
    (insert! conn {:id "external-service", :type "service"} {:parent "root"})

    ; Create bidirectional references using :references
    (update! conn "child-comp" {:references [[:id "sibling-comp"]]})
    (update! conn "sibling-comp" {:references [[:id "child-comp"]]})
    (update! conn "parent-comp" {:references [[:id "external-service"]]})
    (update! conn "external-service" {:references [[:id "parent-comp"]]})

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
    (insert! conn {:id "app", :type "application"} {:parent "root"})
    (insert! conn {:id "main-view", :type "view"} {:parent "app"})

    ; Create disconnected entities (floating dialogs, background services)
    (insert! conn {:id "floating-dialog", :type "dialog", :floating true} {:parent "root"})
    (insert! conn {:id "background-service", :type "service", :background true} {:parent "root"})
    (insert! conn {:id "notification-system", :type "system"} {:parent "root"})

    ; These exist outside the main app hierarchy but can reference it
    (update! conn "floating-dialog" {:references [[:id "main-view"]]})
    (update! conn "background-service" {:references [[:id "app"]]})
    (update! conn "notification-system" {:references [[:id "app"] [:id "floating-dialog"]]})

    ; Verify disconnected entities exist
    (is (= "dialog" (:type (e @conn "floating-dialog"))))
    (is (= "service" (:type (e @conn "background-service"))))

    ; Test that they're not in main app subtree
    (let [app-subtree (d/q '[:find [?id ...]
                             :in $ % ?app-ref
                             :where (subtree-member ?app-ref ?d)
                             [?d :id ?id]]
                           @conn rules [:id "app"])]
      (is (not (contains? (set app-subtree) "floating-dialog")))
      (is (not (contains? (set app-subtree) "background-service")))
      (is (not (contains? (set app-subtree) "notification-system"))))

    ; But they can still reference app components
    (is (= 1 (count (:references (e @conn "floating-dialog")))))
    (is (= 1 (count (:references (e @conn "background-service")))))))

(deftest multiple-relationship-types-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "ui-component", :type "component"} {:parent "root"})
    (insert! conn {:id "data-store", :type "store"} {:parent "root"})
    (insert! conn {:id "validator", :type "validator"} {:parent "root"})
    (insert! conn {:id "formatter", :type "formatter"} {:parent "root"})
    (insert! conn {:id "api-client", :type "client"} {:parent "root"})

    ; Add multiple references to same entity
    (update! conn "ui-component" {:references [[:id "data-store"] [:id "validator"]
                                               [:id "formatter"] [:id "api-client"]]})

    ; Test that component has multiple references
    (let [component (e @conn "ui-component")]
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

(run-tests)