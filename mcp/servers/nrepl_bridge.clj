(ns servers.nrepl-bridge
  "Thin nREPL client bridging the evo-live MCP server to the shadow-cljs
   browser runtime (build :blocks-ui, port 55449).

   One persistent connection is cached and reused. Each MCP tool call issues
   a single CLJ eval that wraps `shadow.cljs.devtools.api/cljs-eval`, so the
   CLJ session stays in Clojure mode and we never depend on piggieback-style
   session switching.

   Returned shape is normalized:
     {:ok? true  :value <pr-str of the CLJS result>}
     {:ok? false :error <message> :stderr <captured stderr if any>}

   Failure modes surfaced cleanly:
     - shadow-cljs not running on :port        → :error \"connect: ...\"
     - no browser runtime attached to build    → :error \"no runtime ...\"
     - CLJS threw                              → :error from the exception"
  (:require [clojure.string :as str]
            [nrepl.core :as nrepl]))

(def ^:private default-port 55449)
(def ^:private default-build :blocks-ui)
(def ^:private eval-timeout-ms 10000)

(defonce ^:private !conn (atom nil))

(defn- open-connection! [port]
  (let [c (nrepl/connect :host "localhost" :port port)]
    (reset! !conn c)
    c))

(defn- conn [port]
  (or @!conn (open-connection! port)))

(defn- close-conn! []
  (when-let [c @!conn]
    (try (.close c) (catch Throwable _))
    (reset! !conn nil)))

(defn- build-wrapper
  "Wrap a CLJS code string in a CLJ call to shadow.cljs.devtools.api/cljs-eval.
   The wrapper pr-strs the result so the nREPL :value field carries a readable
   EDN string back."
  [build cljs-code]
  (format
    (str "(try "
         "  (let [r# (shadow.cljs.devtools.api/cljs-eval %s %s {})] "
         "    (pr-str r#)) "
         "  (catch Throwable t# "
         "    (pr-str {:shadow-error (ex-message t#) "
         "             :type (.getSimpleName (class t#))})))")
    build
    (pr-str cljs-code)))

(defn- parse-shadow-result
  "Unwrap the two-layer string encoding from the wrapper.
     1. nREPL :value is the pr-str of our wrapper's return value (a string),
        so it arrives as a doubly-quoted Clojure string literal.
     2. read-string strips the outer quotes → we get the shadow map literal
        (or a {:shadow-error ...} map) as a plain string.
     3. read-string again → parse to actual Clojure data.
   Returns the inner map, or the raw string if either read fails."
  [raw]
  (let [once (try (read-string raw) (catch Throwable _ raw))
        twice (if (string? once)
                (try (read-string once) (catch Throwable _ once))
                once)]
    twice))

(defn- normalize-shadow
  "Turn a parsed shadow-cljs response map into our normalized output.
   Shadow returns {:results [\"pr-str-value\" ...] :out \"\" :err \"\" :ns sym}
   or our wrapper returns {:shadow-error ... :type ...} on API failure."
  [parsed]
  (cond
    (not (map? parsed))
    {:ok? false :error "bridge: non-map shadow response" :raw (pr-str parsed)}

    (:shadow-error parsed)
    {:ok? false :error (:shadow-error parsed) :type (:type parsed)}

    (contains? parsed :results)
    (let [{:keys [results out err ns]} parsed
          err-str (some-> err not-empty)
          out-str (some-> out not-empty)]
      (cond
        ;; CLJS eval couldn't run — no runtime attached, compile error, etc.
        (and (empty? results) err-str)
        (cond-> {:ok? false :error err-str :ns (str ns)}
          (re-find #"No available JS runtime" err-str)
          (assoc :hint "start the dev server: `npm run dev` and open http://localhost:8080/blocks.html"))

        (empty? results)
        {:ok? false :error "no result from cljs-eval" :ns (str ns)}

        :else
        ;; Success. `first results` is the pr-str of the CLJS value.
        (let [v-str (first results)
              v (try (read-string v-str) (catch Throwable _ v-str))]
          (cond-> {:ok? true :value v :ns (str ns)}
            out-str (assoc :stdout out-str)
            err-str (assoc :stderr err-str)))))

    :else
    {:ok? false :error "unexpected shadow shape" :raw (pr-str parsed)}))

(defn cljs-eval
  "Evaluate cljs-code-string in the browser runtime. Returns a normalized map:
     {:ok? true  :value <clj data> :ns \"...\" [:stdout ...] [:stderr ...]}
     {:ok? false :error <msg>      [:hint ...] [:ns ...]}"
  ([cljs-code]
   (cljs-eval cljs-code {}))
  ([cljs-code {:keys [port build timeout-ms]
               :or {port default-port build default-build timeout-ms eval-timeout-ms}}]
   (try
     (let [c (conn port)
           msg {:op "eval" :code (build-wrapper build cljs-code)}
           resps (doall (-> (nrepl/client c timeout-ms)
                            (nrepl/message msg)))
           values (keep :value resps)
           errs (str/join "" (keep :err resps))
           exs (keep :ex resps)]
       (cond
         (seq exs)
         {:ok? false
          :error (str "nrepl-exception: " (first exs))
          :stderr errs}

         (seq values)
         (normalize-shadow (parse-shadow-result (first values)))

         :else
         {:ok? false :error "no value returned" :raw (pr-str resps)}))
     (catch java.net.ConnectException e
       (close-conn!)
       {:ok? false
        :error (format "connect: shadow-cljs nREPL not reachable on :%d (%s)"
                       port (ex-message e))
        :hint "start the build: `npm run dev` (shadow-cljs nREPL binds on :55449)"})
     (catch Throwable t
       (close-conn!)
       {:ok? false :error (str "bridge: " (ex-message t))}))))

(defn healthcheck
  "Cheap call used to detect whether the browser runtime is attached.
   Returns {:ok? true ...} when shadow can reach CLJS, false otherwise."
  []
  (cljs-eval "(+ 1 2 3)"))
