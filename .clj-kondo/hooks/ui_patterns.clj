(ns hooks.ui-patterns
  "Lint rules for contenteditable and Replicant anti-patterns.
   
   Catches failure modes documented in resources/failure_modes.edn:
   - :replicant-lifecycle-inversion (on-render before on-mount)
   - :duplicate-data-attributes (Playwright strict mode)
   - :declarative-anti-pattern (cursor reset)
   - :focus-not-attached (conditional focus)"
  (:require [clj-kondo.hooks-api :as api]))

;; =============================================================================
;; RULE: check-on-render-session-access
;; Failure mode: :replicant-lifecycle-inversion
;; 
;; on-render fires BEFORE on-mount for new elements. Reading session state
;; (like cursor-position) without checking if element is mounted causes bugs.
;; =============================================================================

(defn check-on-render-session-access
  "Warn when :replicant/on-render reads session state without mount guard.
   
   BAD:  :replicant/on-render (fn [...] (when-let [pos (session/cursor-position)] ...))
   GOOD: :replicant/on-render (fn [...] (when (.-mounted (.-dataset node)) 
                                           (when-let [pos (session/cursor-position)] ...)))"
  [{:keys [node]}]
  (let [children (api/children node)]
    (when (seq children)
      (let [sexpr (try (api/sexpr node) (catch Exception _ nil))]
        (when (and (vector? sexpr)
                   (some #(= :replicant/on-render %) sexpr))
          ;; Find the on-render value
          (let [pairs (partition 2 (rest sexpr))
                on-render-pair (first (filter #(= :replicant/on-render (first %)) pairs))]
            (when on-render-pair
              (let [handler (second on-render-pair)
                    handler-str (str handler)]
                ;; Check if it accesses session without mounted guard
                (when (and (or (re-find #"session/" handler-str)
                               (re-find #"cursor-position" handler-str))
                           (not (re-find #"mounted" handler-str))
                           (not (re-find #"dataset" handler-str)))
                  (api/reg-finding!
                   {:message "on-render reads session state without mount guard. on-render fires BEFORE on-mount for new elements! Check (.-mounted (.-dataset node)) first."
                    :type :warning
                    :row (:row (meta node))
                    :col (:col (meta node))}))))))))))

;; =============================================================================
;; RULE: check-duplicate-data-attrs
;; Failure mode: :duplicate-data-attributes
;;
;; Having data-block-id on both parent div and child span causes Playwright
;; strict mode locator failures.
;; =============================================================================

(defn check-duplicate-data-attrs
  "Warn when data-* attribute appears on both container and nested element.
   
   BAD:  [:div {:data-block-id id} 
           [:span {:data-block-id id} ...]]  ; Playwright finds 2 elements!
   GOOD: [:div {:data-block-id id} 
           [:span ...]]  ; Parent has it, that's enough"
  [{:keys [node]}]
  (let [sexpr (try (api/sexpr node) (catch Exception _ nil))]
    (when (vector? sexpr)
      (letfn [(extract-data-attrs [form]
                (when (and (vector? form) (>= (count form) 2))
                  (let [attrs (second form)]
                    (when (map? attrs)
                      (->> (keys attrs)
                           (filter #(and (keyword? %)
                                         (clojure.string/starts-with? (name %) "data-")))
                           set)))))
              (find-nested-duplicates [parent-attrs form]
                (when (vector? form)
                  (let [child-attrs (extract-data-attrs form)
                        duplicates (clojure.set/intersection (or parent-attrs #{})
                                                             (or child-attrs #{}))]
                    (concat
                     (when (seq duplicates)
                       [{:attrs duplicates :form form}])
                     (mapcat #(find-nested-duplicates
                               (clojure.set/union parent-attrs child-attrs) %)
                             (filter vector? form))))))]
        (let [root-attrs (extract-data-attrs sexpr)
              duplicates (find-nested-duplicates root-attrs sexpr)]
          (doseq [{:keys [attrs]} duplicates]
            (api/reg-finding!
             {:message (str "Duplicate data-* attrs on parent and child: " attrs
                            ". Causes Playwright strict mode failures. Keep on parent only.")
              :type :warning
              :row (:row (meta node))
              :col (:col (meta node))})))))))

;; =============================================================================
;; RULE: check-focus-unconditional
;; Failure mode: :focus-not-attached
;;
;; .focus must always be called in on-mount, not conditionally.
;; Empty blocks hit different code paths and miss focus.
;; =============================================================================

(defn check-focus-unconditional
  "Warn when .focus is inside a conditional in lifecycle hook.
   
   BAD:  (when text-node (.focus node))  ; Empty blocks have no text-node!
   GOOD: (.focus node)  ; Always focus, it's a required side effect"
  [{:keys [node]}]
  (let [sexpr (try (api/sexpr node) (catch Exception _ nil))]
    (when (and (list? sexpr) (= 'when (first sexpr)))
      (letfn [(has-focus-call? [form]
                (cond
                  (and (list? form) (= '.focus (first form))) true
                  (and (list? form) (= '. (first form))
                       (some #(= 'focus %) form)) true
                  (coll? form) (some has-focus-call? form)
                  :else false))]
        (when (has-focus-call? sexpr)
          (api/reg-finding!
           {:message ".focus inside conditional! Focus is a required side effect, not optional. Empty blocks will break."
            :type :warning
            :row (:row (meta node))
            :col (:col (meta node))}))))))

;; =============================================================================
;; RULE: check-declarative-contenteditable
;; Failure mode: :declarative-anti-pattern
;;
;; Passing text as direct child to contenteditable creates controlled component.
;; Browser resets cursor on every re-render.
;; =============================================================================

(defn check-declarative-contenteditable
  "Warn when contenteditable has text as declarative child.
   
   BAD:  [:span {:contentEditable true} text]  ; Re-renders reset cursor!
   GOOD: [:span {:contentEditable true 
                 :replicant/on-mount (fn [...] (set! (.-textContent node) text))}]"
  [{:keys [node]}]
  (let [sexpr (try (api/sexpr node) (catch Exception _ nil))]
    (when (vector? sexpr)
      (let [attrs (when (and (>= (count sexpr) 2) (map? (second sexpr)))
                    (second sexpr))
            is-editable? (or (get attrs :contentEditable)
                             (get attrs :contenteditable))
            ;; Check for symbol children (like `text` variable)
            has-symbol-child? (some symbol? (drop (if attrs 2 1) sexpr))]
        (when (and is-editable? has-symbol-child?)
          (api/reg-finding!
           {:message "contentEditable with text child is controlled component - cursor resets on re-render! Use :replicant/on-mount to set textContent once."
            :type :error
            :row (:row (meta node))
            :col (:col (meta node))}))))))

;; =============================================================================
;; RULE: check-session-before-db-reset
;; Failure mode: :session-db-timing
;;
;; Session updates must happen BEFORE db reset because Replicant renders
;; synchronously and lifecycle hooks read session state.
;; =============================================================================

(defn check-session-before-db-reset
  "Warn when reset! appears before session/swap-session! in same function.
   
   BAD:  (reset! !db new-db) (session/swap-session! ...)  ; Hook sees old session!
   GOOD: (session/swap-session! ...) (reset! !db new-db)  ; Session ready for hook"
  [{:keys [node]}]
  (let [sexpr (try (api/sexpr node) (catch Exception _ nil))]
    (when (and (list? sexpr) (= 'do (first sexpr)))
      (let [forms (rest sexpr)
            form-strs (map str forms)
            reset-idx (first (keep-indexed #(when (re-find #"reset!" %2) %1) form-strs))
            session-idx (first (keep-indexed #(when (re-find #"session.*swap" %2) %1) form-strs))]
        (when (and reset-idx session-idx (< reset-idx session-idx))
          (api/reg-finding!
           {:message "DB reset before session update! Replicant renders synchronously - lifecycle hooks will see stale session. Update session FIRST."
            :type :warning
            :row (:row (meta node))
            :col (:col (meta node))}))))))

;; Note: These hooks need to be registered in config.edn under :hooks {:analyze-call {...}}
;; For now they serve as documentation and can be run manually or integrated later.
