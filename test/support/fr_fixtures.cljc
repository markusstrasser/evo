(ns support.fr-fixtures
  "Deterministic FR fixture runner.

   Fixtures here are executable pressure tests. Only fixtures marked
   :coverage-kind :registry-golden are allowed to stand in for hand-owned
   scenario coverage; generated variants are reported separately."
  (:require [kernel.api :as api]
            [kernel.db :as db]
            [kernel.product-state :as product-state]
            [kernel.transaction :as tx]
            [utils.session-patch :as session-patch]))

(defn create-block [id text]
  {:op :create-node :id id :type :block :props {:text text}})

(defn place [id under at]
  {:op :place :id id :under under :at at})

(defn tree->db
  "Tiny deterministic tree compiler.
   Shape: [:doc [:a \"A\" [:a1 \"A1\"]] [:b \"B\"]]"
  [tree]
  (letfn [(compile-node [parent form]
            (let [[id text & children] form
                  id (name id)]
              (concat [(create-block id text)
                       (place id parent :last)]
                      (mapcat #(compile-node id %) children))))]
    (:db (tx/interpret (db/empty-db)
                       (mapcat #(compile-node :doc %) (rest tree))))))

(def default-session
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil}
   :buffer {}
   :ui {:folded #{}
        :zoom-root nil
        :current-page nil
        :editing-block-id nil
        :cursor-position nil}})

(def fixtures
  [{:id :fr-fixture/idle-backspace-noop
    :fr :fr.state/idle-guard
    :scenario :IDLE-BACKSPACE-NOOP
    :coverage-kind :registry-golden
    :setup {:tree [:doc [:a "A"]]}
    :action {:type :delete :id "a"}
    :expect {:ops {:count 0}
             :invariants [:db-valid :derived-fresh :product-state-valid]}}

   {:id :fr-fixture/focused-delete-noop
    :fr :fr.state/idle-guard
    :scenario :FOCUSED-DELETE-NOOP
    :coverage-kind :generated-variant
    :setup {:tree [:doc [:a "A"]]
            :session {:selection {:nodes #{} :focus "a" :anchor nil}}}
    :action {:type :delete :id "a"}
    :expect {:ops {:count 0}
             :invariants [:db-valid :derived-fresh :product-state-valid]}}])

(defn- merge-session [base patch]
  (merge-with (fn [a b]
                (if (and (map? a) (map? b))
                  (merge-session a b)
                  b))
              base
              patch))

(defn- ops-match? [ops {:keys [count]}]
  (or (nil? count) (= count (clojure.core/count ops))))

(defn run-fixture [fixture]
  (let [db (tree->db (get-in fixture [:setup :tree]))
        session (merge-session default-session (get-in fixture [:setup :session] {}))
        result (api/dispatch* db session (:action fixture) {:tx/now-ms 101})
        db-after (:db result)
        session-after (session-patch/merge-patch session (:session-updates result))
        ops (:ops result)
        db-valid? (:ok? (db/validate db-after))
        product-state-valid? (product-state/valid? db-after session-after)
        pass? (and (empty? (:issues result))
                   (ops-match? ops (get-in fixture [:expect :ops]))
                   db-valid?
                   product-state-valid?)]
    (assoc result
           :fixture-id (:id fixture)
           :coverage-kind (:coverage-kind fixture)
           :session-after session-after
           :pass? pass?
           :checks {:db-valid? db-valid?
                    :product-state-valid? product-state-valid?})))

(defn coverage-kind-counts []
  (frequencies (map :coverage-kind fixtures)))
