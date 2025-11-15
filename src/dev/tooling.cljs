(ns dev.tooling
  "Minimal dev tooling stub.

   The full dev.tooling namespace was removed in consolidation,
   but some files still reference log-dispatch! for intent logging.
   This stub provides the minimum needed for compilation.")

(defn log-dispatch!
  "Stub for intent dispatch logging.
   Does nothing - full logging was consolidated into debugging skills."
  ([intent db-before db-after]
   nil)
  ([intent db-before db-after hotkey]
   nil))
