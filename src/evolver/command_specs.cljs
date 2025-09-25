(ns evolver.command-specs
  (:require [cljs.spec.alpha :as s]))

;; Basic command structure
(s/def ::op keyword?)
(s/def ::node-id string?)
(s/def ::parent-id string?)
(s/def ::position (s/or :number int? :spec map? :nil nil?))

;; Node data structure
(s/def ::type keyword?)
(s/def ::text string?)
(s/def ::props (s/keys :opt-un [::text]))
(s/def ::node-data (s/keys :req-un [::type] :opt-un [::props]))

;; Individual command specs
(s/def ::insert-command
  (s/keys :req-un [::op ::parent-id ::node-id ::node-data]
          :opt-un [::position]))

(s/def ::delete-command
  (s/keys :req-un [::op ::node-id]
          :opt-un [::recursive]))

(s/def ::move-command
  (s/keys :req-un [::op ::node-id ::new-parent-id]
          :opt-un [::position]))

(s/def ::patch-command
  (s/keys :req-un [::op ::node-id ::updates]))

(s/def ::reorder-command
  (s/keys :req-un [::op ::node-id ::parent-id ::from-index ::to-index]))

(s/def ::reference-command
  (s/keys :req-un [::op ::from-node-id ::to-node-id]))

(s/def ::undo-redo-command
  (s/keys :req-un [::op]))

;; Combined command spec
(s/def ::command
  (s/or :insert ::insert-command
        :delete ::delete-command
        :move ::move-command
        :patch ::patch-command
        :reorder ::reorder-command
        :add-reference ::reference-command
        :remove-reference ::reference-command
        :undo ::undo-redo-command
        :redo ::undo-redo-command))

;; UI command specs (for the command registry)
(s/def ::ui-command-name keyword?)
(s/def ::ui-command-params map?)
(s/def ::ui-command (s/tuple ::ui-command-name ::ui-command-params))

(defn validate-command
  "Validate a command against its spec"
  [command]
  (if (s/valid? ::command command)
    command
    (let [explanation (s/explain-str ::command command)]
      (js/console.error "❌ Invalid command:" explanation)
      (throw (js/Error. (str "Invalid command: " explanation))))))

(defn validate-ui-command
  "Validate a UI command against its spec"
  [ui-command]
  (if (s/valid? ::ui-command ui-command)
    ui-command
    (let [explanation (s/explain-str ::ui-command ui-command)]
      (js/console.error "❌ Invalid UI command:" explanation)
      (throw (js/Error. (str "Invalid UI command: " explanation))))))