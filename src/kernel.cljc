(ns kernel
  (:require [datascript.core :as d]
            [clojure.string :as s]
            [clojure.test :refer [deftest is run-tests]]))

;; ---------- 1) Schema & rules (unchanged) ----------
(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref}
   :order {:db/index true}
   :parent+order {:db/tupleAttrs [:parent :order]
                  :db/unique :db.unique/value}})

(def rules
  '[[(subtree-member ?a ?d) [?d :parent ?a]]
    [(subtree-member ?a ?d) [?d :parent ?m] (subtree-member ?a ?m)]])

;; ---------- 2) Integer-based ordering ----------
(defn- sibs [db p]
  (->> (d/q '[:find ?id ?o :in $ ?p :where [?c :parent ?p] [?c :id ?id] [?c :order ?o]] db p)
       (sort-by second)))

(defn- renumber! [conn p]
  (d/transact! conn
               (map-indexed (fn [i [id _]] [:db/add [:id id] :order (* 1000 (inc i))])
                            (sibs @conn p))))

(defn- between [conn p lo hi]
  (let [o (if (and lo hi)
            (quot (+ lo hi) 2)
            (if lo (+ lo 1000) (if hi (- hi 1000) 1000)))]
    (if (and lo hi (= o lo))
      (do (renumber! conn p) (between conn p lo hi))
      o)))

;; ---------- 3) Tiny db helpers ----------
(defn- e [db id] (d/entity db [:id id]))
(defn- pid [db id] (-> (e db id) :parent :id))
(defn- orders [db parent-ref]
  (->> (d/q '[:find [?o ...] :in $ ?p :where [?e :parent ?p] [?e :order ?o]] db parent-ref)
       sort vec))
(defn- neighbors [v x]
  (when-let [i (first (keep-indexed #(when (= %2 x) %1) v))]
    [(get v (dec i)) (get v (inc i))]))

;; Resolve both parent-ref and new :order in one place.
(defn- resolve-position
  "position = {:parent id | :sibling id, :rel #{:first :last :before :after}}
   Defaults to append when :rel missing."
  [conn {:keys [parent sibling rel] :as pos}]
  (let [db @conn
        parent-id (or parent (when sibling (pid db sibling))
                      (throw (ex-info "No parent specified" {:position pos})))
        pref [:id parent-id]
        os (orders db pref)
        ord (case rel
              :first (between conn pref nil (first os))
              :last (between conn pref (last os) nil)
              :before (let [t (:order (e db sibling))
                            [bef _] (neighbors os t)]
                        (between conn pref bef t))
              :after (let [t (:order (e db sibling))
                           [_ aft] (neighbors os t)]
                       (between conn pref t aft))
              ;; default append
              (between conn pref (last os) nil))]
    [pref ord]))

;; ---------- 4) Tree insertion (compact walk) ----------
(defn- walk->tx
  "Assigns fresh :order to children within the new subtree."
  [conn node parent-ref order]
  (let [id (d/tempid :db.part/user)
        kids (:children node [])
        this (-> node (dissoc :children) (assoc :db/id id :parent parent-ref :order order))
        ;; sequence of orders with spacing starting from 1000: 1000, 2000, 3000, ...
        kid-ord (map #(* 1000 (inc %)) (range (count kids)))]
    (into [this]
          (mapcat (fn [[k o]] (walk->tx conn k id o)) (map vector kids kid-ord)))))

(defn- ensure-new-ids! [db root]
  (letfn [(ids [n] (cons (:id n) (mapcat ids (:children n []))))]
    (when-let [dups (seq (filter #(e db %) (ids root)))]
      (throw (ex-info "IDs already exist; use move! for existing entities" {:ids dups})))))

(defn insert! [conn entity {:keys [parent sibling] :as position}]
  (ensure-new-ids! @conn entity)
  (let [[pref root-order] (resolve-position conn position)]
    (d/transact! conn (walk->tx conn entity pref root-order))))

;; ---------- 5) Mutations ----------
(defn delete! [conn entity-id]
  (let [db @conn
        desc (d/q '[:find [?id ...] :in $ % ?p :where (subtree-member ?p ?d) [?d :id ?id]]
                  db rules [:id entity-id])]
    (d/transact! conn (mapv (fn [id] [:db/retractEntity [:id id]]) (cons entity-id desc)))))

(defn update! [conn entity-id attrs]
  (when (some #{:parent :order :id} (keys attrs))
    (throw (ex-info "Cannot modify structural attributes" {:attrs (select-keys attrs [:parent :order :id])})))
  (d/transact! conn (for [[k v] attrs] [:db/add [:id entity-id] k v])))

(defn move! [conn entity-id {:keys [parent sibling] :as position}]
  (let [db @conn
        desc (set (d/q '[:find [?id ...] :in $ % ?p :where (subtree-member ?p ?d) [?d :id ?id]]
                       db rules [:id entity-id]))
        tgt-p (or parent (pid db sibling))]
    (when (contains? desc tgt-p)
      (throw (ex-info "Cycle detected" {:entity entity-id :target tgt-p})))
    (let [[pref new-o] (resolve-position conn position)
          ent (e db entity-id)
          old-parent-ref [:id (-> ent :parent :id)]]
      (d/transact! conn
                   [[:db/retract [:id entity-id] :parent old-parent-ref]
                    [:db/retract [:id entity-id] :order (:order ent)]
                    [:db/add [:id entity-id] :parent pref]
                    [:db/add [:id entity-id] :order new-o]]))))

;; ---------- 6) Read ----------
(defn children-ids [db parent-id]
  (->> (d/q '[:find ?id ?o :in $ ?pid
              :where [?p :id ?pid] [?c :parent ?p] [?c :id ?id] [?c :order ?o]]
            db parent-id)
       (sort-by second) (mapv first)))

;; ---------- 8) Test Utilities ----------
(defn- tree-fixture []
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root"}])
    conn))

(defn- tree-structure [conn & paths]
  "Get tree structure as nested vectors for easy comparison"
  (letfn [(structure [id] [id (mapv structure (children-ids @conn id))])]
    (if (= 1 (count paths))
      (structure (first paths))
      (mapv structure paths))))

;; ---------- 9) Core Tests ----------
(deftest integer-ordering-properties
  "Test mathematical properties of integer ordering with renumbering"
  (let [conn (tree-fixture)]
    ;; Test initial spacing
    (insert! conn {:id "a"} {:parent "root"})
    (insert! conn {:id "b"} {:parent "root"})
    (insert! conn {:id "c"} {:parent "root"})

    (let [orders (mapv #(:order (e @conn %)) (children-ids @conn "root"))]
      (is (= [1000 2000 3000] orders) "Initial spacing should be 1000 apart"))

    ;; Test dense insertion creates midpoints
    (insert! conn {:id "between-a-b"} {:rel :after :sibling "a"})
    (let [between-order (:order (e @conn "between-a-b"))]
      (is (= 1500 between-order) "Dense insertion should create midpoint"))

    ;; Test renumbering when gaps get too small
    (insert! conn {:id "tight1"} {:rel :after :sibling "a"})
    (insert! conn {:id "tight2"} {:rel :after :sibling "tight1"})

    ;; All orders should still be sortable integers
    (let [final-orders (mapv #(:order (e @conn %)) (children-ids @conn "root"))]
      (is (= final-orders (sort final-orders)) "Orders maintain sort invariant")
      (is (every? integer? final-orders) "All orders are integers")
      (is (= (count final-orders) (count (set final-orders))) "All orders are unique"))))

(deftest position-resolution-edge-cases
  "Test position resolution with various edge cases"
  (let [conn (tree-fixture)]
    (insert! conn {:id "a"} {:parent "root"})
    (insert! conn {:id "b"} {:parent "root"})

    ;; Default position (append)
    (insert! conn {:id "c"} {:parent "root"})
    (is (= ["a" "b" "c"] (children-ids @conn "root")))

    ;; Sibling positioning
    (insert! conn {:id "between"} {:rel :after :sibling "a"})
    (is (= ["a" "between" "b" "c"] (children-ids @conn "root")))

    ;; Error conditions
    (is (thrown-with-msg? Exception #"No parent specified"
                          (insert! conn {:id "orphan"} {})))
    (is (thrown-with-msg? Exception #"IDs already exist"
                          (insert! conn {:id "a"} {:parent "root"})))))

(deftest crud-operations-integration
  "Test full CRUD lifecycle with validation"
  (let [conn (tree-fixture)]
    ;; Create hierarchical data
    (insert! conn {:id "ui", :type "app",
                   :children [{:id "header", :children [{:id "nav"}]}
                              {:id "main", :children [{:id "sidebar"} {:id "content"}]}
                              {:id "footer"}]}
             {:parent "root"})

    ;; Verify initial structure
    (is (= ["ui" [["header" [["nav" []]]]
                  ["main" [["sidebar" []] ["content" []]]]
                  ["footer" []]]]
           (tree-structure conn "ui")))

    ;; Update attributes (non-structural)
    (update! conn "nav" {:label "Navigation" :visible true})
    (is (= "Navigation" (:label (e @conn "nav"))))

    ;; Move components around
    (move! conn "nav" {:rel :first :parent "footer"}) ; nav to footer
    (move! conn "sidebar" {:rel :after :sibling "content"}) ; reorder in main

    ;; Verify restructured tree
    (is (= ["ui" [["header" []]
                  ["main" [["content" []] ["sidebar" []]]]
                  ["footer" [["nav" []]]]]]
           (tree-structure conn "ui")))

    ;; Delete subtree
    (delete! conn "main")
    (is (= ["ui" [["header" []] ["footer" [["nav" []]]]]]
           (tree-structure conn "ui")))
    (is (nil? (e @conn "content")) "Cascade delete worked")

    ;; Validation checks
    (is (thrown-with-msg? Exception #"Cannot modify structural"
                          (update! conn "nav" {:parent "other"})))
    (is (thrown-with-msg? Exception #"Cycle detected"
                          (move! conn "ui" {:parent "nav"})))))

(deftest complex-restructuring-scenarios
  "Test realistic tree manipulation scenarios"
  (let [conn (tree-fixture)]
    ;; Build a document-like structure
    (insert! conn {:id "doc", :title "My Document"
                   :children [{:id "sec1", :title "Introduction"
                               :children [{:id "p1", :text "First paragraph"}
                                          {:id "p2", :text "Second paragraph"}]}
                              {:id "sec2", :title "Methods"
                               :children [{:id "subsec1", :title "Approach A"}
                                          {:id "subsec2", :title "Approach B"}]}
                              {:id "sec3", :title "Conclusion"}]}
             {:parent "root"})

    ;; Scenario 1: Reorganize sections (move sec3 between sec1 and sec2)
    (move! conn "sec3" {:rel :after :sibling "sec1"})
    (is (= ["sec1" "sec3" "sec2"] (children-ids @conn "doc")))

    ;; Scenario 2: Merge sections (move subsections from sec2 to sec1)  
    (move! conn "subsec1" {:rel :last :parent "sec1"})
    (move! conn "subsec2" {:rel :last :parent "sec1"})
    (is (= ["p1" "p2" "subsec1" "subsec2"] (children-ids @conn "sec1")))
    (is (= [] (children-ids @conn "sec2")))

    ;; Scenario 3: Bulk operations with ordering constraints
    (insert! conn {:id "toc", :title "Table of Contents"} {:rel :first :parent "doc"})
    (insert! conn {:id "appendix"} {:rel :last :parent "doc"})
    (is (= ["toc" "sec1" "sec3" "sec2" "appendix"] (children-ids @conn "doc")))

    ;; Scenario 4: Complex nested moves preserving deep structure
    (let [sec1-structure (tree-structure conn "sec1")]
      (move! conn "sec1" {:rel :first :parent "appendix"})
      (is (= sec1-structure (tree-structure conn "sec1")) "Deep structure preserved"))))

(deftest performance-and-ordering-stress
  "Test performance with many operations and verify ordering invariants"
  (let [conn (tree-fixture)]
    ;; Generate many sequential inserts
    (doseq [i (range 50)]
      (insert! conn {:id (str "item-" i) :data i} {:parent "root"}))

    ;; Verify ordering is maintained
    (let [items (children-ids @conn "root")
          orders (mapv #(:order (e @conn %)) items)]
      (is (= 50 (count items)))
      (is (= orders (sort orders)) "Orders maintain sort invariant"))

    ;; Test interleaved insertions maintain ordering
    (insert! conn {:id "between-10-11"} {:rel :after :sibling "item-10"})
    (insert! conn {:id "between-20-21"} {:rel :after :sibling "item-20"})

    (let [items (children-ids @conn "root")
          item-10-idx (.indexOf items "item-10")
          item-20-idx (.indexOf items "item-20")]
      (is (= "between-10-11" (nth items (inc item-10-idx))))
      (is (= "between-20-21" (nth items (inc item-20-idx))))

      ;; Orders should still be sorted
      (let [orders (mapv #(:order (e @conn %)) items)]
        (is (= orders (sort orders)) "Interleaved insertions preserve order")))

    ;; Bulk operations should work efficiently
    (doseq [i (range 0 50 5)] ; Delete every 5th item
      (delete! conn (str "item-" i)))

    (is (= 42 (count (children-ids @conn "root"))) "Bulk deletes worked")))

(deftest utility-functions-and-edge-cases
  "Test helper functions and edge cases"
  (let [conn (tree-fixture)]
    ;; Test neighbors function
    (is (= [nil "b"] (neighbors ["a" "b" "c"] "a")))
    (is (= ["a" "c"] (neighbors ["a" "b" "c"] "b")))
    (is (= ["b" nil] (neighbors ["a" "b" "c"] "c")))
    (is (= nil (neighbors ["a" "b" "c"] "missing")))

    ;; Test empty tree operations
    (is (= [] (children-ids @conn "root")))
    (insert! conn {:id "first"} {:parent "root"})
    (is (= ["first"] (children-ids @conn "root")))

    ;; Test single child operations
    (insert! conn {:id "before-first"} {:rel :before :sibling "first"})
    (insert! conn {:id "after-first"} {:rel :after :sibling "first"})
    (is (= ["before-first" "first" "after-first"] (children-ids @conn "root")))

    ;; Test entity resolution
    (is (= "first" (:id (e @conn "first"))))
    (is (= "root" (pid @conn "first")))
    (is (nil? (e @conn "nonexistent")))

    ;; Test tree structure helper
    (insert! conn {:id "child-of-first"} {:parent "first"})
    (is (= ["first" [["child-of-first" []]]]
           (tree-structure conn "first")))))

(deftest hypergraph-cross-references
  "Test arbitrary entity references beyond parent-child hierarchy"
  (let [conn (tree-fixture)]
    ;; Create entities with cross-references (will need schema extension)
    ;; For now, using regular attributes to simulate references
    (insert! conn {:id "button" :label "Click Me" :onclick-handler "save-doc"} {:parent "root"})
    (insert! conn {:id "save-doc" :type "handler" :binds-to ["model" "view"]} {:parent "root"})
    (insert! conn {:id "model" :data "document state"} {:parent "root"})
    (insert! conn {:id "view" :template "document.html"} {:parent "root"})

    ;; Test that entities can reference each other outside tree structure
    (is (= "save-doc" (:onclick-handler (e @conn "button"))))
    (is (= ["model" "view"] (:binds-to (e @conn "save-doc"))))

;; Test querying reverse relationships (what references this entity?)
    ;; Use simpler approach without 'some' predicate for now
    (let [entities-referencing-model
          (d/q '[:find ?id :in $ ?target
                 :where [?e :id ?id] [?e :binds-to ?refs]
                 [(= ?refs ["model" "view"])]] ; Direct match for this test
               @conn "model")]
      (is (= #{["save-doc"]} entities-referencing-model)))

;; Test complex reference chains: button -> handler -> model
    ;; This demonstrates hypergraph traversal beyond tree walking
    (let [button-to-model-path
          (d/q '[:find ?model-id :in $ ?button-id
                 :where
                 [?button :id ?button-id]
                 [?button :onclick-handler ?handler-id]
                 [?handler :id ?handler-id]
                 [?handler :binds-to ?refs]
                 [(= ?refs ["model" "view"])]
                 [(ground "model") ?model-id]]
               @conn "button")]
      (is (= #{["model"]} button-to-model-path)))))

(deftest hypergraph-referential-integrity
  "Test what happens when referenced entities are deleted"
  (let [conn (tree-fixture)]
    ;; Create interconnected entities
    (insert! conn {:id "component-a" :depends-on ["service-x" "service-y"]} {:parent "root"})
    (insert! conn {:id "component-b" :depends-on ["service-x"]} {:parent "root"})
    (insert! conn {:id "service-x" :provides "data-api"} {:parent "root"})
    (insert! conn {:id "service-y" :provides "auth-api"} {:parent "root"})

    ;; Test referential integrity when deleting referenced entity
    (delete! conn "service-x")

    ;; Current behavior: references become dangling (no cascade)
    (is (= ["service-x" "service-y"] (:depends-on (e @conn "component-a"))))
    (is (= ["service-x"] (:depends-on (e @conn "component-b"))))
    (is (nil? (e @conn "service-x")) "service-x was deleted")

    ;; In a proper hypergraph, we might want:
    ;; 1. Cascade delete (delete dependents)
    ;; 2. Reference cleanup (remove from depends-on lists)
    ;; 3. Orphan detection (mark entities with broken dependencies)
    ;; 4. Referential constraints (prevent deletion if referenced)

;; Test finding orphaned references
    (let [all-entities-with-deps (d/q '[:find ?id ?deps :where [?e :id ?id] [?e :depends-on ?deps]] @conn)
          broken-deps (for [[id deps] all-entities-with-deps
                            :when (some #(= % "service-x") deps)]
                        [id])]
      (is (= #{["component-a"] ["component-b"]} (set broken-deps))))))

(deftest hypergraph-bidirectional-relationships
  "Test bidirectional relationships and graph traversal patterns"
  (let [conn (tree-fixture)]
    ;; Create entities with bidirectional relationships
    (insert! conn {:id "user-123" :follows ["user-456" "user-789"]} {:parent "root"})
    (insert! conn {:id "user-456" :follows ["user-789"] :followed-by ["user-123"]} {:parent "root"})
    (insert! conn {:id "user-789" :followed-by ["user-123" "user-456"]} {:parent "root"})

    ;; Test consistency of bidirectional references
    (let [user-123-follows (:follows (e @conn "user-123"))
          user-456-followed-by (:followed-by (e @conn "user-456"))]
      (is (some #(= % "user-456") user-123-follows))
      (is (some #(= % "user-123") user-456-followed-by)))

;; Test graph traversal: find mutual followers (users who follow each other)
    (let [all-followers (d/q '[:find ?id ?follows :where [?e :id ?id] [?e :follows ?follows]] @conn)
          user-123-follows (some #(when (= (first %) "user-123") (second %)) all-followers)]
      ;; user-456 should be mutual because: user-123 follows user-456 AND user-456 has followed-by ["user-123"]
      ;; But we need to check the :followed-by attribute, not :follows
      (is (some #(= % "user-456") user-123-follows) "user-123 should follow user-456")
      (is (some #(= % "user-123") (:followed-by (e @conn "user-456"))) "user-456 should be followed by user-123"))

;; Test transitive relationships: followers of followers  
    (let [all-followers (d/q '[:find ?id ?follows :where [?e :id ?id] [?e :follows ?follows]] @conn)
          user-123-follows (some #(when (= (first %) "user-123") (second %)) all-followers)
          followers-of-followers (for [direct-follow user-123-follows
                                       [id follows] all-followers
                                       :when (= id direct-follow)
                                       fof follows
                                       :when (not= fof "user-123")]
                                   [fof])]
      (is (contains? (set (map first followers-of-followers)) "user-789")))))

(deftest hypergraph-disconnected-subgraphs
  "Test handling of disconnected components and orphaned entities"
  (let [conn (tree-fixture)]
    ;; Create main connected component
    (insert! conn {:id "main-app" :children [{:id "header"} {:id "content"}]} {:parent "root"})

    ;; Create disconnected component (not in tree hierarchy)
    ;; This simulates floating dialogs, overlays, or background services
    (insert! conn {:id "floating-dialog" :modal true :triggered-by "main-app"} {:parent "root"})
    (insert! conn {:id "background-service" :runs-independently true} {:parent "root"})

    ;; Test that disconnected entities exist but aren't in tree traversal
    (is (e @conn "floating-dialog"))
    (is (e @conn "background-service"))

    ;; Test finding all disconnected entities (no structural children/parents beyond root)
    (let [disconnected-entities
          (d/q '[:find ?id :in $ ?root
                 :where
                 [?e :id ?id] [?e :parent ?root-ref] [?root-ref :id ?root]
                 (not [?child :parent ?e])] ; has no children
               @conn "root")]
      (is (contains? (set (map first disconnected-entities)) "floating-dialog"))
      (is (contains? (set (map first disconnected-entities)) "background-service")))

;; Test graph connectivity analysis
    ;; Find entities that have no references to/from other entities (true orphans)
    (let [true-orphans
          (d/q '[:find ?id :in $
                 :where
                 [?e :id ?id]
                 [?e :runs-independently true]]
               @conn)]
      (is (contains? (set (map first true-orphans)) "background-service")))

;; Test reachability from root (what's accessible via references?)
    ;; In a true hypergraph, some entities might only be reachable via references
    (let [reachable-from-main
          (d/q '[:find ?id :in $ ?start
                 :where
                 [?entity :triggered-by ?start] [?entity :id ?id]]
               @conn "main-app")]
      (is (= #{["floating-dialog"]} reachable-from-main)))))

(deftest hypergraph-relationship-types
  "Test multiple relationship types beyond parent-child"
  (let [conn (tree-fixture)]
    ;; Create entities with various relationship types
    (insert! conn {:id "text-input"
                   :type "component"
                   :validates-with "email-validator"
                   :submits-to "contact-form"
                   :error-display "error-message"} {:parent "root"})

    (insert! conn {:id "email-validator"
                   :type "validator"
                   :used-by ["text-input" "signup-form"]} {:parent "root"})

    (insert! conn {:id "contact-form"
                   :type "form"
                   :contains ["text-input" "submit-button"]
                   :processes-via "form-handler"} {:parent "root"})

    (insert! conn {:id "error-message"
                   :type "display"
                   :watches ["text-input"]} {:parent "root"})

    ;; Test querying by relationship type
    (let [validation-relationships
          (d/q '[:find ?source ?target :in $
                 :where [?e :id ?source] [?e :validates-with ?target]]
               @conn)]
      (is (= #{["text-input" "email-validator"]} validation-relationships)))

    (let [all-containers (d/q '[:find ?container ?items :where [?e :id ?container] [?e :contains ?items]] @conn)
          text-input-containers (for [[container items] all-containers
                                      :when (some #(= % "text-input") items)]
                                  [container])]
      (is (contains? (set (map first text-input-containers)) "contact-form")))

    ;; Test relationship multiplicity (one-to-many, many-to-many)
    (let [entities-using-validator
          (d/q '[:find ?user :in $ ?validator
                 :where [?e :id ?user] [?e :validates-with ?validator]]
               @conn "email-validator")]
      (is (= #{["text-input"]} entities-using-validator)))

    ;; Test reverse lookup: what validates what?
    (let [validator-usage
          (d/q '[:find ?validator ?users :in $
                 :where [?v :id ?validator] [?v :used-by ?users]]
               @conn)]
      (is (= #{["email-validator" ["text-input" "signup-form"]]} validator-usage)))

    ;; Test relationship chains: text-input -> form -> handler
    (let [input-to-handler-chain
          (d/q '[:find ?handler :in $ ?input
                 :where
                 [?input-e :id ?input] [?input-e :submits-to ?form]
                 [?form-e :id ?form] [?form-e :processes-via ?handler]]
               @conn "text-input")]
      (is (= #{["form-handler"]} input-to-handler-chain)))))

(run-tests)
;; Run tests when file is loaded
