(ns evolver.protocol-sketch
  (:require [clojure.pprint :refer [pprint]]))

;;; ===========================================================================
;;; 1. THE CONTRACTS (The Core's API)
;;; ===========================================================================

(defprotocol IStructuralState
  "The READ API contract. Abstracts the data structure for navigation."
  (get-parent [this db node-id])
  (get-children [this db node-id]))

(defprotocol IAlgebra
  "The WRITE API contract. Abstracts the primitive operations (our IR)."
  (patch [this db op])
  (del [this db op])
  (move [this db op]))

;;; ===========================================================================
;;; 2. THE GENERIC KERNEL (Reusable Logic)
;;; ===========================================================================
;; Generic functions written purely against the IStructuralState protocol.
;; They have no knowledge of the concrete db shape (e.g., :children-by-parent).

(defn is-ancestor?
  "Checks if a node is an ancestor of another using the READ protocol."
  [store db node-id potential-ancestor-id]
  (loop [current-id node-id]
    (when-let [parent-id (get-parent store db current-id)]
      (if (= parent-id potential-ancestor-id)
        true
        (recur parent-id)))))

(defn find-previous-sibling
  "Finds the previous sibling of a node using the READ protocol."
  [store db node-id]
  (when-let [parent-id (get-parent store db node-id)]
    (let [children (get-children store db parent-id)
          idx      (.indexOf children node-id)]
      (when (> idx 0)
        (nth children (dec idx))))))

;;; ===========================================================================
;;; 3. THE CORE ENGINE (The Concrete Implementation)
;;; ===========================================================================
;; The stateless engine that provides the concrete logic for our contracts.

(defn- derive-metadata [db]
  "Computes derived data, like a parent lookup map for fast access."
  (let [parent-of (reduce-kv (fn [m p children]
                               (reduce #(assoc %1 %2 p) m children))
                             {}
                             (:children-by-parent db))]
    (assoc db :derived {:parent-of parent-of})))

(defrecord InMemoryStore []
  ;; A. The READ contract implementation
  IStructuralState
  (get-parent [_ db node-id]
    (get-in db [:derived :parent-of node-id]))
  (get-children [_ db node-id]
    (get-in db [:children-by-parent node-id] []))

  ;; B. The WRITE contract implementation
  IAlgebra
  (patch [_ db {:keys [node-id updates]}]
    (update-in db [:nodes node-id] merge updates))

  (del [this db {:keys [node-id]}]
    (if-let [parent-id (get-parent this db node-id)]
      (-> db
          (update :nodes dissoc node-id)
          (update-in [:children-by-parent parent-id] #(vec (remove #{node-id} %)))
          (update :children-by-parent dissoc node-id))
      db))

  (move [this db {:keys [node-id new-parent-id at]}]
    ;; This demonstrates the power of the architecture: the WRITE implementation
    ;; uses a generic helper (is-ancestor?) built on the READ protocol.
    (if (or (= node-id new-parent-id) (is-ancestor? this db new-parent-id node-id))
      (throw (ex-info "Invalid move: cannot move a node into itself or its children." {}))
      (let [old-parent-id (get-parent this db node-id)]
        (-> db
            (update-in [:children-by-parent old-parent-id] #(vec (remove #{node-id} %)))
            (update-in [:children-by-parent new-parent-id] (fnil conj []) node-id))))))

;;; ===========================================================================
;;; 4. THE CORE INTERPRETER (The "Executor" for the IR)
;;; ===========================================================================

(defn interpret
  "Takes a db and a transaction, returns a new db. Keeps derived data fresh."
  [store db transaction]
  (reduce
    (fn [current-db [op-type op-payload]]
      (let [db-with-derived (derive-metadata current-db)
            new-db-base     (case op-type
                              :patch (patch store db-with-derived op-payload)
                              :del   (del   store db-with-derived op-payload)
                              :move  (move  store db-with-derived op-payload))]
        (dissoc new-db-base :derived)))
    db
    transaction))

;;; ===========================================================================
;;; 5. THE APPLICATION SHELL (The "Compiler" Frontend)
;;; ===========================================================================

;; --- High-Level Commands (The "Lowering Passes") ---
;; Pure functions that compile intents into transactions (our IR).

(defn merge-block-up-tx [store db {:keys [node-id]}]
  (when-let [prev-id (find-previous-sibling store db node-id)]
    (let [prev-node (get-in db [:nodes prev-id])
          curr-node (get-in db [:nodes node-id])]
      [[:patch {:node-id prev-id, :updates {:text (str (:text prev-node) (:text curr-node))}}]
       [:del {:node-id node-id}]])))

(defn indent-block-tx [store db {:keys [node-id]}]
  (when-let [prev-id (find-previous-sibling store db node-id)]
    [[:move {:node-id node-id, :new-parent-id prev-id, :at :last}]]))

;; --- The Dispatcher ---

(defn dispatch!
  "The single entry point for all application state changes."
  [store state-atom [intent-type payload]]
  (let [current-db (:core-db @state-atom)
        db-with-derived (derive-metadata current-db)

        ;; 1. Compile the intent into a transaction (IR).
        tx (case intent-type
             :merge-up (merge-block-up-tx store db-with-derived payload)
             :indent   (indent-block-tx store db-with-derived payload))]

    (when (seq tx)
      ;; 2. Execute the IR using the Core interpreter.
      (let [new-core-db (interpret store current-db tx)]
        ;; 3. Update the application state.
        (swap! state-atom assoc :core-db new-core-db)
        true))))

;;; ===========================================================================
;;; 6. REPL WORKBENCH
;;; ===========================================================================

(comment

  ;; --- Setup ---
  (def my-store (->InMemoryStore))

  (def initial-db
    {:core-db {:nodes            {"root" {:text "Root"}
                                  "a"    {:text "A"}
                                  "b"    {:text "B"}
                                  "c"    {:text "C"}
                                  "c1"   {:text "C1"}}
               :children-by-parent {"root" ["a" "b" "c"]
                                    "c"    ["c1"]}}
     :view    {:selection ["b"]}})

  (def !app-state (atom initial-db))

  (println "✅ Setup Complete. Let's run the flows.")


  ;; --- FLOW 1: Test the `:merge-up` Intent ---
  (println "\n\n--- 🚀 FLOW 1: MERGE 'B' INTO 'A' ---")
  (println "State BEFORE:")
  (pprint @!app-state)

  (assert (dispatch! my-store !app-state [:merge-up {:node-id "b"}])
          "Dispatch should return true on success")

  (println "\nState AFTER:")
  (pprint @!app-state)

  (let [final-db (:core-db @!app-state)]
    (assert (= (get-in final-db [:nodes "a" :text]) "AB") "Node A text should be merged")
    (assert (nil? (get-in final-db [:nodes "b"])) "Node B should be deleted")
    (assert (= (get-in final-db [:children-by-parent "root"]) ["a" "c"]) "Root children should be updated"))
  (println "\n✅ FLOW 1 Asserts Passed!")


  ;; --- FLOW 2: Test the `:indent` Intent ---
  (println "\n\n--- 🚀 FLOW 2: INDENT 'C' UNDER 'B' ---")
  ;; Reset state for a clean test
  (reset! !app-state initial-db)
  (println "State BEFORE (reset):")
  (pprint @!app-state)

  (assert (dispatch! my-store !app-state [:indent {:node-id "c"}])
          "Dispatch should return true on success")

  (println "\nState AFTER:")
  (pprint @!app-state)

  (let [final-db (:core-db @!app-state)]
    (assert (= (get-in final-db [:children-by-parent "root"]) ["a" "b"]) "Root children should be updated")
    (assert (= (get-in final-db [:children-by-parent "b"]) ["c"]) "Node C should now be a child of B"))
  (println "\n✅ FLOW 2 Asserts Passed!")

  )