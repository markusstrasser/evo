(ns temp)
;;lens <> view <> derived?
;;subs+args <> lenses

;;derived: deriver(input)
;;Plugins can register them under :plugin-id/utils/

;(def Derived
;  "Derived data structure"
;  [:map
;   [:parent-of [:map-of Id [:or Id :keyword]]]
;   [:index-of [:map-of Id :int]]
;   [:prev-id-of [:map-of Id [:maybe Id]]]
;   [:next-id-of [:map-of Id [:maybe Id]]]
;   [:pre :map]
;   [:post :map]
;   [:id-by-pre :map]])
;(def Db
;  "Canonical database structure"
;  [:map
;   [:nodes [:map-of Id Node]]
;   [:children-by-parent [:map-of Parent [:vector Id]]]
;   [:roots [:set :keyword]]
;   [:derived Derived]])


;•	Ops: same three ops, plus a realm tag (default :canon):
;•	{:op :update-node :realm :session :ws "ws-1" :id "selection" :props {:ids #{"n2"}}}

;{:nodes {...} :children-by-parent {...} :root  :trash}
; :selections-by-id {} #intent: select/deselect
; :derived {;; flat, namespaced, computed ONLY from :canon
;           :ref/backlinks {...}
;           :srs/due-index {...}}}


(defn intent->ops []
  "Convert intent to operations"
  [])

(def state {:nodes {} :children-by-parent {}  :derived {} :roots "if multiple trees"})


(register-derived {:name :metrics :dependencies [] :fn (fn [db] {:depth (depth db)})})
(register-intent {:name :outdent :dependencies ["other intents or derived helpers"] :fn (fn [db] "some func that returns a batch of sequential kernel ops")})

(register-hook) ;;hook into some event and change state –– doesn't provide ui.

(defn register-component
  {:component :toc
   :prop-schema {:root-id :keyword}
   :query     (fn [db {:keys [root-id] :or {root-id :root}}]
                {:children (children-of db root-id)})
   :render    (fn [{:keys [children title]}]
                [:ul
                 [:li title]
                 (for [child children]
                   [:li child])])})
