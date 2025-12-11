(ns plugins.text-formatting
  "Text formatting plugin: markdown-style text decorations.
   Handles bold, italic with proper toggle behavior."
  (:require [kernel.intent :as intent]))

;; Sentinel for DCE prevention - referenced by spec.runner

(defn- toggle-text-range
  "Pure function: wraps or unwraps a substring with markers.
   If text is already wrapped with the marker, unwraps it.
   Otherwise, wraps it."
  [{:keys [text start end marker]}]
  (let [prefix (subs text 0 start)
        selected (subs text start end)
        suffix (subs text end)
        marker-len (count marker)
        already-wrapped? (and (>= (count selected) (* 2 marker-len))
                              (= marker (subs selected 0 marker-len))
                              (= marker (subs selected (- (count selected) marker-len))))]
    (if already-wrapped?
      ;; UNWRAP
      (let [unwrapped-text (subs selected marker-len (- (count selected) marker-len))]
        {:text (str prefix unwrapped-text suffix)
         :selection-start start
         :selection-end (- end (* 2 marker-len))})
      ;; WRAP
      {:text (str prefix marker selected marker suffix)
       :selection-start (+ start marker-len)
       :selection-end (+ end marker-len)})))

(intent/register-intent! :format-selection
                         {:doc "Format selected text with markdown markers (bold, italic).
         Toggles formatting: wraps if not formatted, unwraps if already formatted."
                          :fr/ids #{:fr.format/bold-italic}
                          :spec [:map
                                 [:type [:= :format-selection]]
                                 [:block-id :string]
                                 [:start :int]
                                 [:end :int]
                                 [:marker :string]]
                          :handler (fn [db _session {:keys [block-id start end marker]}]
                                     (let [current-text (get-in db [:nodes block-id :props :text] "")
                                           {:keys [text selection-start selection-end]}
                                           (toggle-text-range {:text current-text
                                                               :start start
                                                               :end end
                                                               :marker marker})]
                                       {:ops [{:op :update-node
                                               :id block-id
                                               :props {:text text}}]
                                        :session-updates {:ui {:pending-selection
                                                               {:block-id block-id
                                                                :start selection-start
                                                                :end selection-end}}}}))})
