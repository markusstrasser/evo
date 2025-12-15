(ns plugins.text-formatting
  "Text formatting plugin: markdown-style text decorations.
   Handles bold, italic with proper toggle behavior.

   Logseq parity: Auto-trims whitespace from selection before formatting
   so '** bold text **' becomes '**bold text**' with surrounding spaces preserved."
  (:require [kernel.intent :as intent]
            #?(:clj [clojure.string :as str]
               :cljs [clojure.string :as str])))

;; Sentinel for DCE prevention - referenced by spec.runner

(defn- trim-selection
  "Trim leading/trailing whitespace from a selection, adjusting positions.
   Returns {:start :end} with adjusted indices pointing to non-whitespace content.

   Example: '  hello world  ' with start=0, end=15
            → {:start 2 :end 13} (pointing to 'hello world')

   Logseq parity: Prevents formatting from wrapping whitespace."
  [text start end]
  (let [selected (subs text start end)
        ;; Count leading whitespace
        leading-ws (count (take-while #(= % \space) selected))
        ;; Count trailing whitespace (reverse, count, careful of empty)
        trailing-ws (count (take-while #(= % \space) (reverse selected)))
        ;; Adjust positions
        new-start (+ start leading-ws)
        new-end (- end trailing-ws)]
    ;; Guard against invalid range (all whitespace selection)
    (if (>= new-start new-end)
      {:start start :end end} ; Keep original if all whitespace
      {:start new-start :end new-end})))

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
         Toggles formatting: wraps if not formatted, unwraps if already formatted.

         Logseq parity: Automatically trims leading/trailing whitespace from selection
         before applying formatting. ' bold text ' → '**bold text**' (spaces preserved outside)."
                          :fr/ids #{:fr.format/bold-italic}
                          :spec [:map
                                 [:type [:= :format-selection]]
                                 [:block-id :string]
                                 [:start :int]
                                 [:end :int]
                                 [:marker :string]]
                          :handler (fn [db _session {:keys [block-id start end marker]}]
                                     (let [current-text (get-in db [:nodes block-id :props :text] "")
                                           ;; LOGSEQ PARITY: Trim whitespace from selection
                                           {:keys [start end]} (trim-selection current-text start end)
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


;; ══════════════════════════════════════════════════════════════════════════════
;; DCE Sentinel - prevents dead code elimination in test builds
;; ══════════════════════════════════════════════════════════════════════════════

(def loaded? "Sentinel for spec.runner to verify plugin loaded." true)
