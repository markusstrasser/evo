(ns kernel
  (:require [datascript.core :as d]
            [clojure.test :refer [deftest is run-tests]]))

(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref
            :db/cardinality :db.cardinality/one}
   :pos {:db/cardinality :db.cardinality/one
         :db/index true}
   :parent+pos {:db/tupleAttrs [:parent :pos]
                :db/unique :db.unique/value}
   ; Cross-reference placeholder
   :references {:db/valueType :db.type/ref
                :db/cardinality :db.cardinality/many}})

(def rules
  '[[(subtree-member ?a ?d) [?d :parent ?a]]
    [(subtree-member ?a ?d) [?d :parent ?m] (subtree-member ?a ?m)]])

(defn e [db id] (d/entity db [:id id]))
(defn pid [db id] (-> (e db id) :parent :id))
(defn children-ids [db parent-id]
  (->> (d/q '[:find ?id ?p :in $ ?parent-lookup-ref
              :where [?c :parent ?parent-lookup-ref] [?c :id ?id] [?c :pos ?p]]
            db [:id parent-id])
       (sort-by second) (mapv first)))

(defn- reorder! [conn parent-id ids]
  ; Use negative temporary positions to avoid constraint violations, then set final positions
  ; This ensures each entity gets a unique temporary position before setting the final ones
  (let [temp-positions (map-indexed (fn [i id] [:db/add [:id id] :pos (- -1000 i)]) ids)
        final-positions (map-indexed (fn [i id] [:db/add [:id id] :pos i]) ids)]
    (d/transact! conn temp-positions)
    (d/transact! conn final-positions)))

(defn- ensure-new-ids! [db root]
  (letfn [(ids [n] (cons (:id n) (mapcat ids (:children n []))))]
    (when-let [dups (seq (filter #(e db %) (ids root)))]
      (throw (ex-info "IDs already exist; use move! for existing entities" {:ids dups})))))

(defn- position->target [db {:keys [parent sibling rel] :as pos}]
  (let [parent-id (or parent (when sibling (pid db sibling))
                      (throw (ex-info "No parent specified" {:position pos})))
        kids (children-ids db parent-id)
        idx (case rel
              :first 0
              :last (count kids)
              :before (let [i (.indexOf kids sibling)] (if (neg? i) (count kids) i))
              :after (let [i (.indexOf kids sibling)] (if (neg? i) (count kids) (inc i)))
              (count kids))]
    [parent-id idx]))

(def ^:private temp-pos-counter (atom 0))

(defn- walk->tx [node parent-ref]
  (letfn [(walk-with-counter [n p-ref]
            (let [id (:id n)
                  temp-id (str "temp-" id)
                  kids (:children n [])
                  temp-pos (- (swap! temp-pos-counter inc) 1000000)
                  entity (merge (dissoc n :children)
                                {:db/id temp-id
                                 :id id
                                 :parent p-ref
                                 :pos temp-pos})]
              (into [entity]
                    (mapcat (fn [child]
                              (walk-with-counter child temp-id))
                            kids))))]
    (walk-with-counter node parent-ref)))

(defn insert! [conn entity position]
  (ensure-new-ids! @conn entity)
  (let [[parent-id idx] (position->target @conn position)]
    (d/transact! conn (walk->tx entity [:id parent-id]))
    (let [existing-ids (children-ids @conn parent-id)
          ids' (let [without (vec (remove #{(:id entity)} existing-ids))]
                 (vec (concat (subvec without 0 (min idx (count without)))
                              [(:id entity)]
                              (subvec without (min idx (count without))))))]
      (reorder! conn parent-id ids'))))

(defn move! [conn entity-id position]
  (let [db @conn
        desc (set (d/q '[:find [?id ...] :in $ % ?p
                         :where (subtree-member ?p ?d) [?d :id ?id]]
                       db rules [:id entity-id]))
        [parent-id idx] (position->target db position)]
    (when (contains? desc parent-id)
      (throw (ex-info "Cycle detected" {:entity entity-id :target parent-id})))
    (let [old-parent (pid db entity-id)
          old-kids (children-ids db old-parent)
          new-kids (children-ids db parent-id)]
      (d/transact! conn [[:db/add [:id entity-id] :parent [:id parent-id]]])
      (reorder! conn old-parent (vec (remove #{entity-id} old-kids)))
      (let [without (vec (remove #{entity-id} new-kids))
            ids' (vec (concat (subvec without 0 (min idx (count without)))
                              [entity-id]
                              (subvec without (min idx (count without)))))]
        (reorder! conn parent-id ids')))))

(defn delete! [conn entity-id]
  (let [db @conn
        parent (pid db entity-id)
        desc (d/q '[:find [?id ...] :in $ % ?p
                    :where (subtree-member ?p ?d) [?d :id ?id]]
                  db rules [:id entity-id])]
    (d/transact! conn (mapv (fn [id] [:db/retractEntity [:id id]]) (cons entity-id desc)))
    (when parent (reorder! conn parent (children-ids @conn parent)))))

(defn update! [conn entity-id attrs]
  (when (some #{:parent :pos :id} (keys attrs))
    (throw (ex-info "Cannot modify structural attributes" {:attrs (select-keys attrs [:parent :pos :id])})))
  (d/transact! conn (for [[k v] attrs] [:db/add [:id entity-id] k v])))

(defn- tree-fixture []
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root"}])
    conn))

(defn- tree-structure [conn & paths]
  (letfn [(structure [id] [id (mapv structure (children-ids @conn id))])]
    (if (= 1 (count paths))
      (structure (first paths))
      (mapv structure paths))))

(deftest basic-insert-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "a"} {:parent "root"})
    (insert! conn {:id "b"} {:parent "root"})
    (insert! conn {:id "c"} {:parent "root"})
    (is (= ["a" "b" "c"] (children-ids @conn "root")))

    (let [positions (mapv #(:pos (e @conn %)) (children-ids @conn "root"))]
      (is (= [0 1 2] positions)))))

(deftest position-resolution-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "a"} {:parent "root"})
    (insert! conn {:id "b"} {:parent "root"})
    (insert! conn {:id "c"} {:parent "root"})
    (is (= ["a" "b" "c"] (children-ids @conn "root")))

    (insert! conn {:id "between"} {:rel :after :sibling "a"})
    (is (= ["a" "between" "b" "c"] (children-ids @conn "root")))

    (insert! conn {:id "first"} {:rel :first :parent "root"})
    (is (= ["first" "a" "between" "b" "c"] (children-ids @conn "root")))

    (insert! conn {:id "last"} {:rel :last :parent "root"})
    (is (= ["first" "a" "between" "b" "c" "last"] (children-ids @conn "root")))))

(deftest tree-operations-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "ui", :type "app",
                   :children [{:id "header", :children [{:id "nav"}]}
                              {:id "main", :children [{:id "sidebar"} {:id "content"}]}
                              {:id "footer"}]}
             {:parent "root"})

    (is (= ["ui" [["header" [["nav" []]]]
                  ["main" [["sidebar" []] ["content" []]]]
                  ["footer" []]]]
           (tree-structure conn "ui")))

    (update! conn "nav" {:label "Navigation" :visible true})
    (is (= "Navigation" (:label (e @conn "nav"))))

    (move! conn "nav" {:rel :first :parent "footer"})
    (move! conn "sidebar" {:rel :after :sibling "content"})

    (is (= ["ui" [["header" []]
                  ["main" [["content" []] ["sidebar" []]]]
                  ["footer" [["nav" []]]]]]
           (tree-structure conn "ui")))

    (delete! conn "main")
    (is (= ["ui" [["header" []] ["footer" [["nav" []]]]]]
           (tree-structure conn "ui")))
    (is (nil? (e @conn "content")))))

(deftest cross-reference-relationships-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "form", :type "form"} {:parent "root"})
    (insert! conn {:id "input", :type "input", :name "email"} {:parent "form"})
    (insert! conn {:id "validator", :type "validator", :pattern "email"} {:parent "root"})
    (insert! conn {:id "submit-btn", :type "button"} {:parent "form"})
    (insert! conn {:id "api-endpoint", :type "service"} {:parent "root"})

    ; Add cross-references using the :references attribute
    (update! conn "input" {:references [[:id "validator"]]})
    (update! conn "submit-btn" {:references [[:id "api-endpoint"]]})
    (update! conn "form" {:references [[:id "input"] [:id "submit-btn"]]})

    ; Verify relationships exist
    (is (= 1 (count (:references (e @conn "input")))))
    (is (= 1 (count (:references (e @conn "submit-btn")))))
    (is (= 2 (count (:references (e @conn "form")))))

    ; Test navigation patterns
    (let [validator-users (d/q '[:find [?id ...]
                                 :in $ ?validator-ref
                                 :where [?e :references ?validator-ref]
                                 [?e :id ?id]]
                               @conn [:id "validator"])]
      (is (= ["input"] validator-users)))))

(deftest referential-integrity-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "dialog", :type "dialog"} {:parent "root"})
    (insert! conn {:id "button", :type "button"} {:parent "dialog"})
    (insert! conn {:id "action", :type "action"} {:parent "root"})

    ; Create reference
    (update! conn "button" {:references [[:id "action"]]})
    (is (= 1 (count (:references (e @conn "button")))))

    ; Delete referenced entity
    (delete! conn "action")

    ; Reference becomes dangling (entity no longer exists but reference remains)
    (let [button-entity (e @conn "button")
          refs (:references button-entity)]
      (is (= 1 (count refs))) ; Reference still exists
      (is (nil? (:id (first refs))))) ; But target is gone

    ; Test with multiple references
    (insert! conn {:id "service1", :type "service"} {:parent "root"})
    (insert! conn {:id "service2", :type "service"} {:parent "root"})
    (update! conn "button" {:references [[:id "service1"] [:id "service2"]]})

    (delete! conn "service1")
    ; One reference becomes dangling, other remains valid
    (let [refs (:references (e @conn "button"))]
      (is (= 2 (count refs)))
      ; At least one should be valid
      (is (some #(some? (:id %)) refs)))))

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
      (is (= #{["child-comp" "sibling-comp"] ["sibling-comp" "child-comp"]} (set mutual-refs)))
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

(deftest complex-subtree-operations-test
  (let [conn (tree-fixture)]
    ; Create complex nested structure
    (insert! conn {:id "app", :type "app",
                   :children [{:id "header", :children [{:id "nav", :children [{:id "menu"}]}]}
                              {:id "main", :children [{:id "sidebar", :children [{:id "widget1"} {:id "widget2"}]}
                                                      {:id "content", :children [{:id "article", :children [{:id "paragraph1"} {:id "paragraph2"}]}]}]}
                              {:id "footer", :children [{:id "links"}]}]}
             {:parent "root"})

    ; Test initial structure
    (is (= ["app" [["header" [["nav" [["menu" []]]]]]
                   ["main" [["sidebar" [["widget1" []] ["widget2" []]]]
                            ["content" [["article" [["paragraph1" []] ["paragraph2" []]]]]]]]
                   ["footer" [["links" []]]]]]
           (tree-structure conn "app")))

    ; Move entire sidebar subtree to footer
    (move! conn "sidebar" {:parent "footer"})
    (is (= ["app" [["header" [["nav" [["menu" []]]]]]
                   ["main" [["content" [["article" [["paragraph1" []] ["paragraph2" []]]]]]]]
                   ["footer" [["links" []] ["sidebar" [["widget1" []] ["widget2" []]]]]]]]
           (tree-structure conn "app")))

    ; Move article before header (complex reordering)
    (move! conn "article" {:rel :before :sibling "header"})
    (is (= ["app" [["article" [["paragraph1" []] ["paragraph2" []]]]
                   ["header" [["nav" [["menu" []]]]]]
                   ["main" [["content" []]]]
                   ["footer" [["links" []] ["sidebar" [["widget1" []] ["widget2" []]]]]]]]
           (tree-structure conn "app")))

    ; Test moving subtree with cross-references
    (update! conn "widget1" {:references [[:id "paragraph1"]]})
    (update! conn "paragraph2" {:references [[:id "widget2"]]})

    ; Move sidebar to main (references should remain intact)
    (move! conn "sidebar" {:parent "main"})
    (is (= 1 (count (:references (e @conn "widget1")))))
    (is (= 1 (count (:references (e @conn "paragraph2")))))))

(deftest large-subtree-insertion-test
  (let [conn (tree-fixture)]
    ; Create large nested structure to insert
    (let [deep-structure {:id "complex-component",
                          :type "component",
                          :children [{:id "level1a",
                                      :children [{:id "level2a",
                                                  :children [{:id "level3a",
                                                              :children [{:id "level4a"} {:id "level4b"}]}
                                                             {:id "level3b"}]}
                                                 {:id "level2b",
                                                  :children [{:id "level3c"} {:id "level3d"}]}]}
                                     {:id "level1b",
                                      :children [{:id "level2c"}]}]}]

      ; Insert entire structure at once
      (insert! conn deep-structure {:parent "root"})

      ; Verify all levels exist and are properly positioned
      (is (= ["level1a" "level1b"] (children-ids @conn "complex-component")))
      (is (= ["level2a" "level2b"] (children-ids @conn "level1a")))
      (is (= ["level3a" "level3b"] (children-ids @conn "level2a")))
      (is (= ["level4a" "level4b"] (children-ids @conn "level3a")))

      ; Test that positioning maintains order (not specific values due to temp positions)
      (let [positions (fn [parent-id]
                        (let [pos-vals (mapv #(:pos (e @conn %)) (children-ids @conn parent-id))]
                          (= pos-vals (sort pos-vals))))]
        (is (positions "complex-component"))
        (is (positions "level1a"))
        (is (positions "level2a"))
        (is (positions "level3a")))))

  ; Test insertion between existing siblings in deep structure
  (let [conn (tree-fixture)]
    (insert! conn {:id "container",
                   :children [{:id "first"} {:id "last"}]}
             {:parent "root"})

    ; Insert complex structure between existing siblings
    (insert! conn {:id "middle-complex",
                   :children [{:id "sub1"} {:id "sub2"}]}
             {:rel :after :sibling "first"})

    (is (= ["first" "middle-complex" "last"] (children-ids @conn "container")))
    (is (= ["sub1" "sub2"] (children-ids @conn "middle-complex")))))

(deftest cycle-detection-edge-cases-test
  (let [conn (tree-fixture)]
    ; Create multi-level hierarchy
    (insert! conn {:id "a", :children [{:id "b", :children [{:id "c", :children [{:id "d"}]}]}]}
             {:parent "root"})

    ; Test direct cycle
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Cycle detected"
         (move! conn "a" {:parent "a"})))

    ; Test indirect cycle (grandparent)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Cycle detected"
         (move! conn "a" {:parent "c"})))

    ; Test deep cycle
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Cycle detected"
         (move! conn "a" {:parent "d"})))

    ; Test that valid moves still work
    (insert! conn {:id "safe-target"} {:parent "root"})
    (move! conn "d" {:parent "safe-target"}) ; Should work
    (is (= "safe-target" (pid @conn "d")))))

(deftest simple-reordering-test
  (let [conn (tree-fixture)]
    ; Create container with a few children
    (insert! conn {:id "container"} {:parent "root"})
    (insert! conn {:id "item-1"} {:parent "container"})
    (insert! conn {:id "item-2"} {:parent "container"})
    (insert! conn {:id "item-3"} {:parent "container"})

    ; Verify initial order
    (is (= ["item-1" "item-2" "item-3"] (children-ids @conn "container")))

    ; Move last item to first
    (move! conn "item-3" {:rel :first :parent "container"})
    (is (= "item-3" (first (children-ids @conn "container"))))

    ; Test positions maintain order
    (let [positions (mapv #(:pos (e @conn %)) (children-ids @conn "container"))]
      (is (= (sort positions) positions)) ; Positions are in order
      (is (= (count positions) (count (set positions)))))))