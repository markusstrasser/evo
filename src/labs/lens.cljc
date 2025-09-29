(ns kernel.lens
  "Path lenses - pure getters for tree navigation and introspection.

   These functions make specs/tests readable and let agents 'ask where am I?'
   without touching state. Zero runtime coupling to operations.

   All functions are pure - they take db and return data, never modify state.

   Usage:
     (lens/children-of db 'parent-id')       ; => ['child1' 'child2']
     (lens/parent-of db 'child-id')          ; => 'parent-id'
     (lens/path-to-root db 'deep-child')     ; => ['deep-child' 'parent' 'root']
     (lens/explain db 'node-id')             ; => 'id=node-id at /root/parent index=2'"
  (:require [clojure.string :as str]))

(defn children-of
  "Get immediate children of a parent node."
  [db parent-id]
  (get-in db [:child-ids/by-parent parent-id] []))

(defn parent-of
  "Get parent ID of a node, or nil if none."
  [db node-id]
  (get-in db [:derived :parent-id-of node-id]))

(defn index-of
  "Get index position of node within its parent's children, or -1 if not found."
  [db node-id]
  (get-in db [:derived :index-of node-id] -1))

(defn position-of
  "Get {:parent-id :index} position info for node."
  [db node-id]
  {:parent-id (parent-of db node-id)
   :index (index-of db node-id)})

(defn path-to-root
  "Get vector path from node to root: [node-id parent-id grandparent-id ... root]"
  [db node-id]
  (loop [current node-id
         path [node-id]]
    (if-let [parent (parent-of db current)]
      (recur parent (conj path parent))
      path)))

(defn siblings
  "Get all siblings of a node (including itself)."
  [db node-id]
  (let [parent-id (parent-of db node-id)]
    (children-of db parent-id)))

(defn prev-id
  "Get ID of previous sibling, or nil if none."
  [db node-id]
  (let [siblings-vec (siblings db node-id)
        idx (index-of db node-id)]
    (when (and (>= idx 0) (> idx 0))
      (nth siblings-vec (dec idx) nil))))

(defn next-id
  "Get ID of next sibling, or nil if none."
  [db node-id]
  (let [siblings-vec (siblings db node-id)
        idx (index-of db node-id)
        next-idx (inc idx)]
    (when (and (>= idx 0) (< next-idx (count siblings-vec)))
      (nth siblings-vec next-idx nil))))

(defn node-exists?
  "Check if node exists in database."
  [db node-id]
  (contains? (:nodes db) node-id))

(defn is-root?
  "Check if node is a root (has no parent)."
  [db node-id]
  (and (node-exists? db node-id)
       (nil? (parent-of db node-id))))

(defn is-leaf?
  "Check if node is a leaf (has no children)."
  [db node-id]
  (empty? (children-of db node-id)))

(defn depth-of
  "Get depth of node (0 for roots, 1 for immediate children, etc)."
  [db node-id]
  (dec (count (path-to-root db node-id))))

(defn node-type
  "Get type of node."
  [db node-id]
  (get-in db [:nodes node-id :type]))

(defn node-props
  "Get props map of node."
  [db node-id]
  (get-in db [:nodes node-id :props] {}))

(defn explain
  "Human-readable description of node position for debugging.
   Returns string like: 'id=child1 at /root/parent index=2 type=:div'"
  [db node-id]
  (if-not (node-exists? db node-id)
    (str "id=" node-id " [NOT FOUND]")
    (let [path (path-to-root db node-id)
          path-str (str "/" (str/join "/" (reverse path)))
          {:keys [parent-id index]} (position-of db node-id)
          type (node-type db node-id)]
      (str "id=" node-id
           " at " path-str
           " index=" index
           " type=" type
           (when (is-root? db node-id) " [ROOT]")
           (when (is-leaf? db node-id) " [LEAF]")))))

(comment
  ;; REPL usage examples:

  ;; Setup test data
  (def test-db
    {:nodes {"root" {:type :root :props {}}
             "parent" {:type :div :props {}}
             "child1" {:type :span :props {:class "first"}}
             "child2" {:type :span :props {:class "second"}}}
     :child-ids/by-parent {"root" ["parent"]
                           "parent" ["child1" "child2"]}
     :derived {:parent-id-of {"parent" "root" "child1" "parent" "child2" "parent"}
               :index-of {"parent" 0 "child1" 0 "child2" 1}}})

  ;; Basic navigation
  (children-of test-db "root")        ; => ["parent"]
  (children-of test-db "parent")      ; => ["child1" "child2"]
  (parent-of test-db "child1")        ; => "parent"
  (index-of test-db "child2")         ; => 1

  ;; Path operations
  (path-to-root test-db "child2")     ; => ["child2" "parent" "root"]
  (siblings test-db "child1")         ; => ["child1" "child2"]
  (prev-id test-db "child2")          ; => "child1"
  (next-id test-db "child1")          ; => "child2"

  ;; Predicates
  (is-root? test-db "root")           ; => true
  (is-leaf? test-db "child1")         ; => true
  (depth-of test-db "child2")         ; => 2

  ;; Debugging
  (explain test-db "child2")          ; => "id=child2 at /root/parent/child2 index=1 type=:span [LEAF]"
  )