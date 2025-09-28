# Proposal: High-Feedback, REPL-Driven Transactions

**Inspiration Source**: `/Users/alien/Projects/inspo-clones/clojure-mcp/BIG_IDEAS.md`

This proposal is inspired by a development philosophy: that effective AI-assisted programming requires a workflow of **"tiny steps with rich feedback."** We will transform the kernel's transaction function from a brittle, exception-throwing mechanism into a robust, information-rich system that enables a true REPL-style interaction for an LLM agent.

---

### Before: Brittle, Exception-Based Flow Control

The current `run-tx` function uses exceptions to signal failure. This is a classic approach for human developers but is deeply problematic for an AI agent.

```clojure
;; **COMPLEXITY**: High (for the AI agent).
;; The agent must wrap every call in a try/catch block and then resort to
;; unreliable string parsing on the exception message to figure out what
;; went wrong. It gets no information about partial success.

(try
  (run-tx db [{:op :insert :id "a"}
              {:op :place :id "a" :parent-id "non-existent"}])
  (catch Exception e
    (println "The transaction failed.")
    (let [message (.getMessage e)]
      ;; Fragile, unreliable string matching
      (if (.contains message "parent-id does not exist")
        (println "Error: The parent was missing.")
        (println "An unknown error occurred.")))))
```

### After: Robust, Data-Driven Reporting

The redesigned `run-tx` will *never* throw a predictable error. It will *always* return a detailed report, making success and failure part of the data, not the control flow.

```clojure
;; **COMPLEXITY**: Low (for the AI agent).
;; The agent receives a simple data structure. It can use standard map and
;; vector operations to precisely determine what succeeded, what failed,
;; and why. This is a robust, reliable, and simple way to process results.

(let [report (run-tx db [{:op :insert :id "a"}
                         {:op :place :id "a" :parent-id "non-existent"}])]
  (if (:ok? report)
    (println "Success!")
    (let [failed-op-report (->> (:ops report)
                                (filter (comp not :ok?))
                                first)]
      (println "The transaction failed on operation:" (:op failed-op-report))
      (println "Reason:" (get-in failed-op-report [:error :why]))
      (println "Data:" (get-in failed-op-report [:error :data])))))

;; Example Report Data:
;; {:ok? false,
;;  :ops [{:op {:op :insert, :id "a"}, :ok? true}
;;        {:op {:op :place, :id "a", :parent-id "non-existent"},
;;         :ok? false,
;;         :error {:why :op-failed, :data {:parent-id "non-existent"}}}]}
```

---
### Summary of Improvements

*   **Enables REPL Workflow**: An AI agent can now operate in a tight loop: attempt a transaction, read the structured report, and if it failed, use the precise error data to immediately construct a corrected transaction.
*   **Robustness**: Replaces fragile exception message parsing with a stable, machine-readable data API for errors.
*   **Clarity and Debuggability**: The detailed report provides a clear audit trail of what happened during a transaction, benefiting both AI and human developers.
*   **Separation of Concerns**: Errors become data, which is a more functional and Clojure-idiomatic way of handling control flow compared to exceptions.