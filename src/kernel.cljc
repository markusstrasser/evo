(ns kernel
  (:require [datascript.core :as d]
            [clojure.test :refer [deftest is run-tests]]))

(def schema {:id {:db/unique :db.unique/identity}
             :parent {:db/valueType :db.type/ref}
             :order {:db/index true}})

(defn- get-children-sorted [db parent-ref]
  (->> (d/q '[:find ?e ?o :in $ ?p :where [?e :parent ?p] [?e :order ?o]] db parent-ref)
       (sort-by second)
       (mapv first)))

(defn- renumber! [conn parent-ref]
  (let [children (get-children-sorted @conn parent-ref)]
    (d/transact! conn
                 (map-indexed (fn [i e] [:db/add e :order i]) children))))

(defn- calculate-order [db {:keys [rel target]}]
  (case rel
    :first -1
    :last 999999

    (:before :after)
    (let [target-entity (d/entity db [:id target])
          target-order (:order target-entity)]
      (if (= rel :before)
        (- target-order 0.5)
        (+ target-order 0.5)))))

(defn- resolve-position [db {:keys [rel target]}]
  (let [parent-id (if (#{:first :last} rel)
                    target ; target IS the parent for first/last
                    (let [target-entity (d/entity db [:id target])]
                      (when-not target-entity
                        (throw (ex-info "Target entity not found" {:target target})))
                      (:id (:parent target-entity)))) ; get parent of target for before/after
        parent-ref [:id parent-id]]
    {:parent parent-ref
     :order (calculate-order db {:rel rel :target target})}))

(defn- data->txns [entity-map parent-ref order]
  (let [entity-id (or (:id entity-map) (str (random-uuid)))
        temp-id (d/tempid :db.part/user)
        children (get entity-map :children [])
        base-entity (-> entity-map
                        (dissoc :children)
                        (assoc :db/id temp-id
                               :id entity-id
                               :parent parent-ref
                               :order order))
        child-txns (mapcat
                    (fn [i child]
                      (data->txns child temp-id i))
                    (range)
                    children)]
    (conj child-txns base-entity)))

(defn position!
  "Creates or moves an entity to a position. Handles nested creation."
  [conn entity-map position-spec]
  (let [db @conn
        {:keys [parent order]} (resolve-position db position-spec)
        tx-data (data->txns entity-map parent order)]
    (d/transact! conn tx-data)
    (renumber! conn parent)))

(defn patch!
  "Partially update entity attributes."
  [entity-id conn attr-map]
  (when (d/entity @conn [:id entity-id]) ; Only patch if entity exists
    (d/transact! conn
                 (mapv (fn [[k v]] [:db/add [:id entity-id] k v]) attr-map))))

(defn children-ids
  "Returns child IDs in order."
  [db parent-id]
  (->> (d/q '[:find ?id ?order :in $ ?pid :where
              [?p :id ?pid] [?c :parent ?p]
              [?c :id ?id] [?c :order ?order]]
            db parent-id)
       (sort-by second)
       (mapv first)))

(defn- collect-descendant-ids [db parent-id]
  (let [child-ids (children-ids db parent-id)]
    (concat child-ids (mapcat #(collect-descendant-ids db %) child-ids))))

(defn delete!
  "Deletes entity and all descendants."
  [conn entity-id]
  (let [db @conn
        entity (d/entity db [:id entity-id])]
    (when entity ; Only delete if entity exists
      (let [parent (:parent entity)
            all-ids (cons entity-id (collect-descendant-ids db entity-id))]
        (d/transact! conn (mapv #(vector :db/retractEntity [:id %]) all-ids))
        (when parent (renumber! conn parent))))))

;; ## Tests
;; ----------------------------------------------------------------------------
(deftest kernel-api-tests
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root", :order 0}])

    ;; Test nested create
    (println "\n=== Testing nested create ===")
    (position! conn
               {:id "main" :text "Content"
                :children [{:id "p1" :text "Paragraph 1"}
                           {:id "p2" :text "Paragraph 2"
                            :children [{:id "span1" :text "Span"}]}]}
               {:rel :first :target "root"})

    (is (= ["main"] (children-ids @conn "root")))
    (is (= "Content" (:text (d/entity @conn [:id "main"]))))
    (is (= ["p1" "p2"] (children-ids @conn "main")))
    (is (= ["span1"] (children-ids @conn "p2")))

    ;; Test positioning
    (println "\n=== Testing move operations ===")
    (position! conn {:id "header" :text "Header"} {:rel :first :target "root"})
    (is (= ["header" "main"] (children-ids @conn "root")))

    (position! conn {:id "main"} {:rel :after :target "header"})
    (is (= ["header" "main"] (children-ids @conn "root")))
    (is (= ["p1" "p2"] (children-ids @conn "main")))

    ;; Test cascade delete
    (println "\n=== Testing cascade delete ===")
    (delete! conn "main")
    (is (= ["header"] (children-ids @conn "root")))
    (is (nil? (d/entity @conn [:id "p1"])))
    (is (nil? (d/entity @conn [:id "span1"])))))

(deftest patch-tests
  (println "\n=== Testing patch functionality ===")
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root", :order 0}])

    ;; Create an entity to patch
    (position! conn {:id "item" :text "Original" :color "blue"} {:rel :first :target "root"})

    ;; Test single attribute patch
    (patch! "item" conn {:text "Updated"})
    (is (= "Updated" (:text (d/entity @conn [:id "item"]))))
    (is (= "blue" (:color (d/entity @conn [:id "item"])))) ; unchanged

    ;; Test multiple attribute patch
    (patch! "item" conn {:text "Final" :color "red" :size "large"})
    (let [entity (d/entity @conn [:id "item"])]
      (is (= "Final" (:text entity)))
      (is (= "red" (:color entity)))
      (is (= "large" (:size entity))))))

(deftest subtree-operations-tests
  (println "\n=== Testing subtree operations ===")
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root", :order 0}])

    ;; Create a complex UI component tree
    (position! conn
               {:id "sidebar" :type "container"
                :children [{:id "nav" :type "navigation"
                            :children [{:id "home-link" :text "Home"}
                                       {:id "about-link" :text "About"}]}
                           {:id "ads" :type "advertisement"
                            :children [{:id "ad1" :text "Buy Now!"}
                                       {:id "ad2" :text "Subscribe!"}]}]}
               {:rel :first :target "root"})

    ;; Create main content area
    (position! conn
               {:id "main-content" :type "content"
                :children [{:id "article" :type "article"
                            :children [{:id "title" :text "Article Title"}
                                       {:id "body" :text "Article content..."}
                                       {:id "comments" :type "comments-section"
                                        :children [{:id "comment1" :text "Great article!"}
                                                   {:id "comment2" :text "Thanks for sharing."}]}]}]}
               {:rel :after :target "sidebar"})

    ;; Verify initial structure
    (is (= ["sidebar" "main-content"] (children-ids @conn "root")))
    (is (= ["nav" "ads"] (children-ids @conn "sidebar")))
    (is (= ["home-link" "about-link"] (children-ids @conn "nav")))
    (is (= ["article"] (children-ids @conn "main-content")))
    (is (= ["title" "body" "comments"] (children-ids @conn "article")))
    (is (= ["comment1" "comment2"] (children-ids @conn "comments")))

    ;; Test moving entire subtree - move navigation to main content
    (println "\n=== Testing subtree move ===")
    (position! conn {:id "nav"} {:rel :first :target "main-content"})
    (is (= ["nav" "article"] (children-ids @conn "main-content")))
    (is (= ["ads"] (children-ids @conn "sidebar"))) ; nav removed from sidebar
    (is (= ["home-link" "about-link"] (children-ids @conn "nav"))) ; children preserved

    ;; Test moving nested component - move comments section to sidebar
    (position! conn {:id "comments"} {:rel :after :target "ads"})
    (is (= ["ads" "comments"] (children-ids @conn "sidebar")))
    (is (= ["title" "body"] (children-ids @conn "article"))) ; comments removed from article
    (is (= ["comment1" "comment2"] (children-ids @conn "comments"))) ; children preserved

    ;; Test cascade delete of complex subtree
    (println "\n=== Testing complex cascade delete ===")
    (delete! conn "main-content")
    (is (= ["sidebar"] (children-ids @conn "root")))
    (is (nil? (d/entity @conn [:id "nav"])))
    (is (nil? (d/entity @conn [:id "home-link"])))
    (is (nil? (d/entity @conn [:id "article"])))
    (is (nil? (d/entity @conn [:id "title"])))
    (is (nil? (d/entity @conn [:id "body"])))

    ;; Verify sidebar and its remaining children still exist
    (is (not (nil? (d/entity @conn [:id "sidebar"]))))
    (is (= ["ads" "comments"] (children-ids @conn "sidebar")))
    (is (= ["comment1" "comment2"] (children-ids @conn "comments")))))

(deftest ordering-stress-tests
  (println "\n=== Testing ordering edge cases ===")
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root", :order 0}])

    ;; Test multiple :first operations
    (position! conn {:id "item1"} {:rel :first :target "root"})
    (position! conn {:id "item2"} {:rel :first :target "root"})
    (position! conn {:id "item3"} {:rel :first :target "root"})
    (is (= ["item3" "item2" "item1"] (children-ids @conn "root")))

    ;; Test multiple :last operations
    (position! conn {:id "item4"} {:rel :last :target "root"})
    (position! conn {:id "item5"} {:rel :last :target "root"})
    (is (= ["item3" "item2" "item1" "item4" "item5"] (children-ids @conn "root")))

    ;; Test before/after with renumbering
    (position! conn {:id "middle"} {:rel :after :target "item1"})
    (is (= ["item3" "item2" "item1" "middle" "item4" "item5"] (children-ids @conn "root")))

    ;; Test moving existing items
    (position! conn {:id "item3"} {:rel :after :target "item5"})
    (is (= ["item2" "item1" "middle" "item4" "item5" "item3"] (children-ids @conn "root")))

    ;; Verify ordering is clean (no fractional orders after renumbering)
    (let [orders (->> (d/q '[:find ?e ?o :in $ ?parent
                             :where [?p :id ?parent] [?e :parent ?p] [?e :order ?o]]
                           @conn "root")
                      (sort-by second)
                      (mapv second))]
      (is (= [0 1 2 3 4 5] orders)))))

(deftest error-handling-tests
  (println "\n=== Testing error handling ===")
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root", :order 0}])

    ;; Test operations on non-existent entities
    (try
      (position! conn {:id "item"} {:rel :after :target "nonexistent"})
      (is false "Should have thrown exception")
      (catch Exception e
        (is true "Expected exception for non-existent target")))

    ;; Test delete on non-existent entity (should not crash)
    (delete! conn "nonexistent")
    (is (= [] (children-ids @conn "root"))) ; root should still be empty

    ;; Test patch on non-existent entity (should not crash)
    (patch! "nonexistent" conn {:text "test"})
    (is (nil? (d/entity @conn [:id "nonexistent"])))))

(deftest realistic-document-editor-scenario
  (println "\n=== Testing realistic document editor scenario ===")
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "document", :order 0}])

    ;; Create initial document structure
    (position! conn
               {:id "title" :type "heading" :level 1 :text "My Document"}
               {:rel :first :target "document"})

    (position! conn
               {:id "intro" :type "paragraph" :text "This is the introduction paragraph."}
               {:rel :after :target "title"})

    ;; Add a complex section with nested content
    (position! conn
               {:id "section1" :type "section"
                :children [{:id "section1-title" :type "heading" :level 2 :text "Section 1"}
                           {:id "section1-content" :type "paragraph" :text "Section 1 content here."}
                           {:id "code-block" :type "code" :language "clojure"
                            :children [{:id "code-line1" :text "(defn hello [name]"}
                                       {:id "code-line2" :text "  (str \"Hello, \" name))"}]}]}
               {:rel :after :target "intro"})

    ;; Verify initial structure
    (is (= ["title" "intro" "section1"] (children-ids @conn "document")))
    (is (= ["section1-title" "section1-content" "code-block"] (children-ids @conn "section1")))
    (is (= ["code-line1" "code-line2"] (children-ids @conn "code-block")))

    ;; User edits: Update title
    (patch! "title" conn {:text "My Amazing Document" :style "bold"})
    (is (= "My Amazing Document" (:text (d/entity @conn [:id "title"]))))
    (is (= "bold" (:style (d/entity @conn [:id "title"]))))

    ;; User reorganizes: Move code block to be a child of intro paragraph
    (position! conn {:id "code-block"} {:rel :after :target "intro"})
    (is (= ["title" "intro" "code-block" "section1"] (children-ids @conn "document")))
    (is (= ["section1-title" "section1-content"] (children-ids @conn "section1")))
    (is (= ["code-line1" "code-line2"] (children-ids @conn "code-block"))) ; children preserved

    ;; User adds a new section with a nested list
    (position! conn
               {:id "section2" :type "section"
                :children [{:id "section2-title" :type "heading" :level 2 :text "Features"}
                           {:id "feature-list" :type "list"
                            :children [{:id "feature1" :type "list-item" :text "Fast performance"}
                                       {:id "feature2" :type "list-item" :text "Easy to use"}
                                       {:id "feature3" :type "list-item" :text "Extensible"}]}]}
               {:rel :last :target "document"})

    (is (= ["title" "intro" "code-block" "section1" "section2"] (children-ids @conn "document")))
    (is (= ["feature1" "feature2" "feature3"] (children-ids @conn "feature-list")))

    ;; User decides to delete the entire first section
    (delete! conn "section1")
    (is (= ["title" "intro" "code-block" "section2"] (children-ids @conn "document")))
    (is (nil? (d/entity @conn [:id "section1-title"])))
    (is (nil? (d/entity @conn [:id "section1-content"])))

    ;; User reorders features in the list
    (position! conn {:id "feature3"} {:rel :first :target "feature-list"})
    (is (= ["feature3" "feature1" "feature2"] (children-ids @conn "feature-list")))

    ;; User adds inline formatting to a feature
    (patch! "feature2" conn {:text "Easy to use" :emphasis "italic" :color "blue"})
    (let [feature2 (d/entity @conn [:id "feature2"])]
      (is (= "Easy to use" (:text feature2)))
      (is (= "italic" (:emphasis feature2)))
      (is (= "blue" (:color feature2))))

    ;; Final document structure verification
    (is (= 4 (count (children-ids @conn "document"))))
    (is (= 3 (count (children-ids @conn "feature-list"))))
    (is (= 2 (count (children-ids @conn "code-block"))))

    (println "Document editing scenario completed successfully!")))

(run-tests)