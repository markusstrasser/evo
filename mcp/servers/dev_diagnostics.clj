(ns servers.dev-diagnostics
  "Dev-diagnostics MCP server for evo project.
   Exposes dev tooling via MCP protocol for Claude Code integration."
  (:require [clojure.data.json :as json]
            [health :as health]
            [repl.session :as session])
  (:import [io.modelcontextprotocol.server McpServer McpServerFeatures
            McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.spec McpSchema$Tool McpSchema$CallToolResult
            McpSchema$TextContent McpSchema$ServerCapabilities]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper])
  (:gen-class))

;; Tool implementations - each takes params map and returns data

(defn health-check-tool
  "Run comprehensive health checks on dev environment"
  [_params]
  (let [result (health/preflight-check!)]
    {:status (if (:ok? result) "healthy" "issues-detected")
     :ok (:ok? result)
     :issues (:issues result)}))

(defn cache-stats-tool
  "Get shadow-cljs cache statistics"
  [_params]
  (let [stats (health/cache-stats)]
    {:shadow-cljs-cache (:shadow-cljs-cache stats)
     :out-dir (:out-dir stats)
     :target-dir (:target-dir stats)
     :total (:total stats)}))

(defn clear-caches-tool
  "Clear all shadow-cljs caches (WARNING: requires rebuild)"
  [_params]
  (if (health/clear-caches!)
    {:status "success" :message "Caches cleared - run 'npm run dev' to rebuild"}
    {:status "error" :message "Failed to clear caches"}))

(defn repl-health-tool
  "Quick REPL health check"
  [_params]
  (let [output (with-out-str (session/quick-health-check!))]
    {:output output
     :status "complete"}))

;; Tool registry
(def tool-implementations
  {"evo_health_check" health-check-tool
   "evo_cache_stats" cache-stats-tool
   "evo_clear_caches" clear-caches-tool
   "evo_repl_health" repl-health-tool})

;; Create Mono from callback (adapted from clojure-mcp pattern)
(defn create-tool-handler [tool-fn]
  (fn [_exchange arguments]
    (Mono/create
     (reify java.util.function.Consumer
       (accept [_this sink]
         (try
           (let [result (tool-fn arguments)
                 result-json (json/write-str result)
                 text-content (McpSchema$TextContent. result-json)
                 tool-result (McpSchema$CallToolResult. [text-content] false)]
             (.success sink tool-result))
           (catch Exception e
             (.error sink e))))))))

;; Create MCP tool specification
(defn create-tool [tool-name description]
  (let [tool-fn (get tool-implementations tool-name)
        mono-fn (create-tool-handler tool-fn)]
    (McpServerFeatures$AsyncToolSpecification.
     (McpSchema$Tool. tool-name description "{}")
     (reify java.util.function.BiFunction
       (apply [_this exchange arguments]
         (mono-fn exchange arguments))))))

;; Server entrypoint
(defn -main
  "Start the MCP dev-diagnostics server on stdio"
  [& _args]
  (binding [*out* *err*]  ;; CRITICAL: Stdio servers must not write to stdout
    (println "Starting evo dev-diagnostics MCP server...")

    (let [;; Create tools
          tools [(create-tool "evo_health_check"
                              "Run comprehensive health checks on shadow-cljs, REPL, and caches")
                 (create-tool "evo_cache_stats"
                              "Get statistics about shadow-cljs cache sizes")
                 (create-tool "evo_clear_caches"
                              "Clear all shadow-cljs caches (WARNING: requires rebuild)")
                 (create-tool "evo_repl_health"
                              "Quick REPL health diagnostic")]

          ;; Create transport provider
          transport-provider (StdioServerTransportProvider. (ObjectMapper.))

          ;; Build server
          ^McpServer server (-> (McpServer/async transport-provider)
                                (.serverInfo "evo-dev-diagnostics" "0.1.0")
                                (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                                   (.tools true)
                                                   (.build)))
                                (.build))]

      ;; Register tools
      (doseq [tool tools]
        (-> (.addTool server tool)
            (.subscribe)))

      (println "✓ Server configured with" (count tools) "dev tools")
      (println "Ready for Claude Code connection...")

      ;; Server runs on stdio - blocks naturally waiting for input
      server)))

(comment
  ;; Test tools locally before starting server
  (health-check-tool {})
  (cache-stats-tool {})
  (repl-health-tool {})
  )
