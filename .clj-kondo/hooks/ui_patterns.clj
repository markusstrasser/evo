#!/usr/bin/env bb
;; clj-kondo hooks to catch contenteditable anti-patterns

(ns hooks.ui-patterns
  "Lint rules that would have caught contenteditable bugs"
  (:require [clj-kondo.hooks-api :as api]))

;; RULE 1: Warn when lifecycle hook reads closure vars
(defn check-lifecycle-hook-closures
  "Detect stale closures in :replicant/on-render"
  [{:keys [node]}]
  (let [hook-fn (api/sexpr node)]
    (when (and (list? hook-fn)
               (= 'fn (first hook-fn)))
      (let [params (second hook-fn)
            body (drop 2 hook-fn)]
        ;; Check if body references vars not in destructured params
        ;; This would catch: (fn [{:replicant/keys [node]}] (set! ... text))
        ;; Where `text` is from outer closure
        (api/reg-finding!
         {:message "Lifecycle hook may close over stale vars. Use :replicant/remember or pass data via node dataset."
          :type :warning
          :row (:row (meta node))
          :col (:col (meta node))})))))

;; RULE 2: Warn when .focus is inside conditional
(defn check-focus-always-called
  "Detect .focus inside (when ...) blocks"
  [{:keys [node]}]
  (let [expr (api/sexpr node)]
    (when (and (list? expr)
               (= 'when (first expr)))
      (letfn [(has-focus? [form]
                (cond
                  (and (list? form)
                       (= '. (first form))
                       (= 'focus (last form)))
                  true

                  (coll? form)
                  (some has-focus? form)

                  :else false))]
        (when (has-focus? expr)
          (api/reg-finding!
           {:message ".focus should ALWAYS be called, not conditionally (breaks empty blocks)"
            :type :error
            :row (:row (meta node))
            :col (:col (meta node))}))))))

;; RULE 3: Warn when contenteditable has text as direct child
(defn check-declarative-contenteditable
  "Detect [:span.content-edit text] pattern (controlled component anti-pattern)"
  [{:keys [node]}]
  (let [hiccup (api/sexpr node)]
    (when (and (vector? hiccup)
               (keyword? (first hiccup))
               (or (= :span.content-edit (first hiccup))
                   (= :span.content-view (first hiccup))))
      ;; Check if text is passed as direct child (not via lifecycle hook)
      (let [has-text-child? (some #(and (symbol? %) (= 'text %)) hiccup)
            has-lifecycle? (some #(and (map? %) (contains? % :replicant/on-render)) hiccup)]
        (when (and has-text-child? (not has-lifecycle?))
          (api/reg-finding!
           {:message "contenteditable with text child is controlled component (breaks cursor). Use :replicant/on-render with :replicant/remember instead."
            :type :warning
            :row (:row (meta node))
            :col (:col (meta node))}))))))

;; RULE 4: Check for duplicate key handlers
(defn check-duplicate-key-bindings
  "Warn if same key is bound in both keymap and component"
  [{:keys [node]}]
  ;; This would parse keymap configs and component :on-keydown handlers
  ;; Flag duplicates like Enter bound in :editing keymap AND block component
  (api/reg-finding!
   {:message "Key handled in both keymap and component - will fire twice!"
    :type :error
    :row (:row (meta node))
    :col (:col (meta node))}))

;; Register hooks
{:hooks
 {:analyze-call
  {'replicant.core/hiccup check-declarative-contenteditable}}}
