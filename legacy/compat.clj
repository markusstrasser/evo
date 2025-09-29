(ns legacy.compat
  "Compatibility layer: lower legacy ops to 3-op kernel.
   Preserves old trace semantics while using clean core.")

(defn lower1
  "Lower single legacy op to sequence of 3-ops."
  [m]
  (case (:op m)
    ;; Legacy sugar ops → core
    :insert
    [{:op :create-node
      :id (or (:id m) (:node-id m))
      :type (or (:type m) (:node-type m) :div)
      :props (or (:props m) {})
      :under (or (:under m) (:parent-id m))
      :at (or (:at m) :last)}]

    :move
    [{:op :place
      :id (or (:id m) (:node-id m))
      :under (or (:target-parent-id m) (:parent-id m) (:under m))
      :at (or (:anchor m) :last)}]

    :reorder
    [{:op :place
      :id (or (:id m) (:node-id m))
      :under (or (:parent-id m) (:under m))
      :at (:anchor m)}]

    :delete
    [{:op :place :id (:id m) :under :trash :at :last}]

    ;; Refs lived as state → encode in node props; labs/graph derives index
    :add-ref
    [{:op :update-node
      :id (:source-id m)
      :props {:refs {(:relation m) #{(:target-id m)}}}}]

    :rm-ref
    [{:op :update-node
      :id (:source-id m)
      :props {:refs {(:relation m) #{}}}}] ;; Simplified: full removal

    ;; Core ops pass through
    :create-node [m]
    :place [m]
    :update-node [m]

    ;; Unknown = breadcrumb for debugging
    [{:op :__unknown__ :raw m}]))

(defn lower
  "Lower sequence of legacy ops to 3-op sequence."
  [txs]
  (mapcat lower1 txs))