(ns repl.lens-scenarios
  "Copy-pasteable REPL scenarios for lens functions.

   These scenarios demonstrate lens usage and provide quick debugging tools."
  (:require [kernel.lens :as lens]
            [kernel.core :as core]))

;; Sample database for testing
(def sample-db
  {:nodes {"root" {:type :root :props {}}
           "header" {:type :div :props {:class "header"}}
           "nav" {:type :nav :props {}}
           "logo" {:type :img :props {:src "logo.png"}}
           "menu" {:type :ul :props {}}
           "item1" {:type :li :props {:class "active"}}
           "item2" {:type :li :props {}}
           "content" {:type :div :props {:class "main"}}
           "sidebar" {:type :aside :props {}}}
   :child-ids/by-parent {"root" ["header" "content" "sidebar"]
                         "header" ["nav"]
                         "nav" ["logo" "menu"]
                         "menu" ["item1" "item2"]}
   :derived {:parent-id-of {"header" "root" "content" "root" "sidebar" "root"
                            "nav" "header" "logo" "nav" "menu" "nav"
                            "item1" "menu" "item2" "menu"}
             :index-of {"header" 0 "content" 1 "sidebar" 2
                        "nav" 0 "logo" 0 "menu" 1
                        "item1" 0 "item2" 1}}})

(comment
  ;; =============================================================================
  ;; Basic Navigation Scenarios
  ;; =============================================================================

  ;; Explore the tree structure
  (lens/children-of sample-db "root")        ; => ["header" "content" "sidebar"]
  (lens/children-of sample-db "nav")         ; => ["logo" "menu"]
  (lens/children-of sample-db "menu")        ; => ["item1" "item2"]

  ;; Find parents
  (lens/parent-of sample-db "nav")           ; => "header"
  (lens/parent-of sample-db "item1")         ; => "menu"
  (lens/parent-of sample-db "root")          ; => nil (root has no parent)

  ;; Check positions
  (lens/index-of sample-db "content")        ; => 1 (second child of root)
  (lens/index-of sample-db "item2")          ; => 1 (second item in menu)
  (lens/position-of sample-db "sidebar")     ; => {:parent-id "root" :index 2}

  ;; =============================================================================
  ;; Path and Depth Scenarios
  ;; =============================================================================

  ;; Trace paths to root
  (lens/path-to-root sample-db "item1")      ; => ["item1" "menu" "nav" "header" "root"]
  (lens/path-to-root sample-db "logo")       ; => ["logo" "nav" "header" "root"]
  (lens/path-to-root sample-db "root")       ; => ["root"]

  ;; Check depths
  (lens/depth-of sample-db "root")           ; => 0
  (lens/depth-of sample-db "header")         ; => 1
  (lens/depth-of sample-db "nav")            ; => 2
  (lens/depth-of sample-db "item1")          ; => 4

  ;; =============================================================================
  ;; Sibling Navigation Scenarios
  ;; =============================================================================

  ;; Get siblings (including self)
  (lens/siblings sample-db "content")        ; => ["header" "content" "sidebar"]
  (lens/siblings sample-db "item1")          ; => ["item1" "item2"]

  ;; Navigate between siblings
  (lens/next-id sample-db "header")          ; => "content"
  (lens/next-id sample-db "content")         ; => "sidebar"
  (lens/next-id sample-db "sidebar")         ; => nil (last sibling)

  (lens/prev-id sample-db "sidebar")         ; => "content"
  (lens/prev-id sample-db "content")         ; => "header"
  (lens/prev-id sample-db "header")          ; => nil (first sibling)

  ;; Round-trip navigation
  (-> sample-db (lens/next-id "item1") (lens/prev-id sample-db)) ; => "item1"

  ;; =============================================================================
  ;; Node Information Scenarios
  ;; =============================================================================

  ;; Check node properties
  (lens/node-exists? sample-db "nav")        ; => true
  (lens/node-exists? sample-db "missing")    ; => false

  (lens/node-type sample-db "nav")           ; => :nav
  (lens/node-type sample-db "item1")         ; => :li

  (lens/node-props sample-db "logo")         ; => {:src "logo.png"}
  (lens/node-props sample-db "item1")        ; => {:class "active"}

  ;; Check tree position properties
  (lens/is-root? sample-db "root")           ; => true
  (lens/is-root? sample-db "header")         ; => false

  (lens/is-leaf? sample-db "logo")           ; => true (no children)
  (lens/is-leaf? sample-db "nav")            ; => false (has children)

  ;; =============================================================================
  ;; Debugging and Introspection Scenarios
  ;; =============================================================================

  ;; Get human-readable descriptions
  (lens/explain sample-db "root")            ; => "id=root at /root index=-1 type=:root [ROOT]"
  (lens/explain sample-db "nav")             ; => "id=nav at /root/header/nav index=0 type=:nav"
  (lens/explain sample-db "item1")           ; => "id=item1 at /root/header/nav/menu/item1 index=0 type=:li [LEAF]"
  (lens/explain sample-db "missing")         ; => "id=missing [NOT FOUND]"

  ;; Quick tree exploration function
  (defn explore-node [db node-id]
    (println (lens/explain db node-id))
    (when-let [children (seq (lens/children-of db node-id))]
      (println "  Children:" children)
      (doseq [child children]
        (println "   -" (lens/explain db child)))))

  ;; Explore the sample tree
  (explore-node sample-db "root")
  (explore-node sample-db "nav")
  (explore-node sample-db "menu")

  ;; =============================================================================
  ;; Integration with Real Operations
  ;; =============================================================================

  ;; Build a tree step by step and inspect with lenses
  (def build-steps
    (-> {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        (core/apply-tx* {:op :create-node :id "page" :type :div})
        (core/apply-tx* {:op :place :id "page" :parent-id "root"})
        (core/apply-tx* {:op :create-node :id "title" :type :h1})
        (core/apply-tx* {:op :place :id "title" :parent-id "page"})))

  ;; Inspect the built structure
  (lens/children-of build-steps "root")      ; => ["page"]
  (lens/children-of build-steps "page")      ; => ["title"]
  (lens/path-to-root build-steps "title")    ; => ["title" "page" "root"]
  (lens/explain build-steps "title")         ; => descriptive path info

  ;; Verify invariants with lenses
  (defn verify-tree-invariants [db]
    (doseq [node-id (keys (:nodes db))]
      (let [siblings (lens/siblings db node-id)
            index (lens/index-of db node-id)]
        ;; Every node's index should match its position in siblings
        (when (>= index 0)
          (assert (= node-id (nth siblings index))
                  (str "Index mismatch: " node-id " index=" index " siblings=" siblings)))

        ;; Every child should have parent pointing back
        (doseq [child (lens/children-of db node-id)]
          (assert (= node-id (lens/parent-of db child))
                  (str "Parent mismatch: " child " parent should be " node-id))))))

  ;; Test invariants
  (verify-tree-invariants sample-db)        ; Should pass
  (verify-tree-invariants build-steps)      ; Should pass

  ;; =============================================================================
  ;; Quick Helper Functions for REPL
  ;; =============================================================================

  (defn show-tree
    "Display tree structure starting from node"
    ([db] (show-tree db "root" 0))
    ([db node-id] (show-tree db node-id 0))
    ([db node-id depth]
     (let [indent (apply str (repeat depth "  "))]
       (println (str indent "- " (lens/explain db node-id)))
       (doseq [child (lens/children-of db node-id)]
         (show-tree db child (inc depth))))))

  (defn find-path [db from to]
    "Find common ancestor path between two nodes"
    (let [path1 (set (lens/path-to-root db from))
          path2 (lens/path-to-root db to)
          common (first (filter path1 path2))]
      {:from from :to to :common-ancestor common
       :from-path (lens/path-to-root db from)
       :to-path (lens/path-to-root db to)}))

  ;; Try the helpers
  (show-tree sample-db)                      ; Display entire tree
  (show-tree sample-db "nav")                ; Display nav subtree
  (find-path sample-db "logo" "item1")       ; Find relationship between nodes
  )