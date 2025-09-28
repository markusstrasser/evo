(ns kernel.responses
  (:require [malli.core :as m]
            [kernel.schemas :as S]))

(def R
  {:status [:enum :ok :error :conflict :redirect :diag]
   :error [:map
           [:why keyword?]
           [:op-index {:optional true} int?]
           [:op {:optional true} any?]
           [:data {:optional true} map?]]
   :redirect [:map
              [:to keyword?]
              [:payload {:optional true} any?]]})

(def rsp-schema
  (m/schema
   [:schema {:registry S/registry}
    [:map
     [:status (R :status)]
     [:db {:optional true} ::S/db]
     [:effects {:optional true} [:vector map?]]
     [:error {:optional true} (R :error)]
     [:redirect {:optional true} (R :redirect)]
     [:trace {:optional true} [:vector map?]]]]))

(defn- v! [m] (when-not (m/validate rsp-schema m)
                (throw (ex-info "Response shape invalid" {:resp m}))))

(defn ok [{:keys [db effects trace]}]
  (let [m (cond-> {:status :ok :db db :effects (vec (or effects []))}
            trace (assoc :trace trace))]
    (v! m) m))
(defn error [{:keys [why data op-index op]}]
  (let [error-data (cond-> {:why why}
                     data (assoc :data data)
                     op-index (assoc :op-index op-index)
                     op (assoc :op op))
        m {:status :error :error error-data}]
    (v! m) m))
(defn conflict [data] (let [m {:status :conflict :error {:why :conflict :data data}}] (v! m) m))
(defn redirect [to payload] (let [m {:status :redirect :redirect {:to to :payload payload}}] (v! m) m))
(defn diag [payload] (let [m {:status :diag :effects [] :error {:why :diag :data payload}}] (v! m) m))