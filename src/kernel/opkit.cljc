(ns kernel.opkit
  "Sugar-op definition kit: single source for impl + schema + docs."
  (:require [kernel.schemas :as S]
            [kernel.core :as K]))

(defmacro defop
  "Define a sugar op.
   - opkw:     keyword, e.g. :insert
   - opts:
     :doc      string doc
     :schema   Malli form using kernel.schemas registry keys
     :args     arg vector for method (default [db op])
   Body: must return a DB (pure), typically by composing core primitives."
  [opkw {:keys [doc schema args] :as _opts} & body]
  (let [args (or args '[db op])]
    `(do
       (S/register-sugar-op-schema! ~opkw ~schema)
       (defmethod K/apply-op ~opkw ~args
         ~@body))))