(ns kernel.opkit
  "Sugar-op definition kit: single source for impl + schema + docs."
  (:require [kernel.schemas :as S]
            ;; Remove kernel.core dependency
            ))

(defmacro defop
  "Define an op. Compiles the body into a handler and registers it centrally."
  [opkw {:keys [doc schema args axes] :as _opts} & body]
  (let [args (or args '[db op])
        ;; Create a unique local name for the handler function (better stack traces)
        handler-name (symbol (str (name opkw) "-handler"))]
    `(do
       ;; 1. Define the handler function locally
       (defn ~handler-name ~args
         ~@body)
       ;; 2. Register it in the central registry (kernel.schemas)
       (S/register-op! ~opkw
                       {:doc ~doc
                        :schema ~schema
                        :handler ~handler-name
                        :axes ~axes}))))