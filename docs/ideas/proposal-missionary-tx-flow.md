# Proposal: Asynchronous Effects and a Reactive Transaction Log

**Inspiration Source**: `/Users/alien/Projects/inspo-clones/missionary/src/missionary/core.cljc`

Missionary provides powerful, composable abstractions for asynchronous operations (`tasks`) and streams of values (`flows`). Adopting these patterns can fundamentally improve the kernel's architecture, making it asynchronous-native and reactively observable.

---

### Before: Synchronous and Opaque Transactions

The current kernel is entirely synchronous. Effects are returned as a simple list, and there is no built-in way to observe a stream of changes.

```clojure
;; **COMPLEXITY**: High (for the application developer).
;; The application developer is responsible for all asynchronous logic and
;; for implementing any kind of state-change observation.

;; 1. Handling an async effect requires complex, bespoke code.
(let [{:keys [db effects]} (apply-tx+effects* @!db {:op :create-user :name "test"})]
  (reset! !db db)
  (doseq [effect effects]
    (when (= (:effect effect) :http/post)
      ;; Problem: This blocks the main thread.
      ;; Or, requires the developer to build a complex async handler.
      (http/post (:url effect) (:body effect)))))

;; 2. Observing changes requires manual wrapping.
(defn transact-and-log! [tx]
  (let [old-db @!db
        new-db (apply-tx* old-db tx)]
    (println "DB CHANGED:" (data/diff old-db new-db)) ;; Manual logging
    (reset! !db new-db)))
```

### After: Asynchronous, Observable Transactions

By returning a Missionary `task` and pushing results to a `flow`, we make the kernel's core asynchronous and observable by design.

```clojure
;; **COMPLEXITY**: Low (for the application developer).
;; The complexity of asynchrony and observation is handled by the kernel and
;; Missionary's robust primitives. The developer consumes these features
;; through a clean, declarative API.

;; 1. Asynchronous effects are handled naturally.
(def tx-task (apply-tx+effects* @!db {:op :create-user :name "test"}))

;; The application can now compose this task with others.
;; The effect's implementation can be async without blocking.
(m/? 
  (m/sp
    (let [{:keys [db effects]} (m/? tx-task)]
      (reset! !db db)
      ;; The effect itself can be a task
      (m/? (run-effects effects)))))

;; 2. Observing changes becomes trivial.
(def tx-log-flow (setup-tx-log-flow))

;; Any part of the system can now subscribe to all database changes.
(m/reduce (fn [_ report] (println "DB CHANGED:" report)) nil tx-log-flow)
```

---
### Summary of Improvements

*   **Asynchronous-Native**: The kernel is no longer a blocking system. It can natively handle asynchronous side-effects, which is essential for any real-world application.
*   **Reactive Core**: The transaction log `flow` provides a powerful, centralized stream of state changes. This is a foundational building block for reactive UIs, logging, and undo/redo systems.
*   **Decoupling**: Components that need to react to state changes no longer need to be aware of the components that cause them. They simply listen to the log.
*   **Improved Composability**: By returning tasks, transactions become composable units of work that can be integrated into larger asynchronous workflows.