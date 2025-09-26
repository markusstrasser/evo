(ns evolver.agent-integration-test
  "Integration tests using agent utilities with Chrome DevTools"
  (:require [cljs.test :refer [deftest is testing]]
            [agent.core :as agent]))

(defn browser-store-inspector
  "JavaScript function to inject into browser for store inspection"
  []
  "
  function inspectEvolverStore() {
    const store = evolver.core.store.cljs$core$IDeref$_deref$arity$1();
    
    function extractMapData(cljsMap) {
      if (!cljsMap || !cljsMap.arr) return {};
      const result = {};
      for (let i = 0; i < cljsMap.arr.length; i += 2) {
        const key = cljsMap.arr[i];
        const value = cljsMap.arr[i + 1];
        if (typeof key === 'string') {
          result[key] = value;
        }
      }
      return result;
    }
    
    function getStoreSection(store, sectionName) {
      if (!store.root || !store.root.arr) return null;
      for (let i = 0; i < store.root.arr.length; i += 2) {
        const key = store.root.arr[i];
        if (key && key.name === sectionName) {
          return store.root.arr[i + 1];
        }
      }
      return null;
    }
    
    const nodes = getStoreSection(store, 'nodes');
    const view = getStoreSection(store, 'view');
    const references = getStoreSection(store, 'references');
    
    const nodeMap = nodes ? extractMapData(nodes) : {};
    const viewMap = view ? extractMapData(view) : {};
    const refMap = references ? extractMapData(references) : {};
    
    return {
      summary: {
        nodeCount: Object.keys(nodeMap).length,
        selectedCount: Object.keys(viewMap.selected || {}).length,
        highlightedCount: Object.keys(viewMap.highlighted || {}).length,
        referenceCount: Object.keys(refMap).length
      },
      nodes: Object.keys(nodeMap),
      selected: Object.keys(viewMap.selected || {}),
      highlighted: Object.keys(viewMap.highlighted || {}),
      references: Object.keys(refMap),
      integrity: {
        allSelectedNodesExist: Object.keys(viewMap.selected || {}).every(id => nodeMap.hasOwnProperty(id)),
        allHighlightedNodesExist: Object.keys(viewMap.highlighted || {}).every(id => nodeMap.hasOwnProperty(id)),
        allReferencesExist: Object.keys(refMap).every(id => nodeMap.hasOwnProperty(id))
      }
    };
  }
  
  return inspectEvolverStore();
  ")

(defn reference-integrity-checker
  "JavaScript function to check reference integrity"
  []
  "
  function checkReferenceIntegrity() {
    const store = evolver.core.store.cljs$core$IDeref$_deref$arity$1();
    const inspection = inspectEvolverStore();
    
    const issues = [];
    
    // Check if all selected nodes exist
    inspection.selected.forEach(nodeId => {
      if (!inspection.nodes.includes(nodeId)) {
        issues.push(`Selected node '${nodeId}' does not exist in nodes`);
      }
    });
    
    // Check if all highlighted nodes exist  
    inspection.highlighted.forEach(nodeId => {
      if (!inspection.nodes.includes(nodeId)) {
        issues.push(`Highlighted node '${nodeId}' does not exist in nodes`);
      }
    });
    
    // Check if all referenced nodes exist
    inspection.references.forEach(nodeId => {
      if (!inspection.nodes.includes(nodeId)) {
        issues.push(`Referenced node '${nodeId}' does not exist in nodes`);
      }
    });
    
    return {
      healthy: issues.length === 0,
      issues: issues,
      summary: inspection.summary
    };
  }
  
  return checkReferenceIntegrity();
  ")

(defn performance-metrics-collector
  "JavaScript function to collect performance metrics"
  []
  "
  function collectPerformanceMetrics() {
    const start = performance.now();
    const inspection = inspectEvolverStore();
    const inspectionTime = performance.now() - start;
    
    return {
      inspectionTime: inspectionTime,
      nodeCount: inspection.summary.nodeCount,
      storeSize: JSON.stringify(evolver.core.store.cljs$core$IDeref$_deref$arity$1()).length,
      timestamp: Date.now()
    };
  }
  
  return collectPerformanceMetrics();
  ")

;; Test functions that would be called from ClojureScript
;; These are placeholders since we can't directly use Chrome DevTools from ClojureScript tests

(deftest agent-integration-test
  (testing "Agent utilities integration structure"
    ;; Test that agent functions exist and can be called
    (is (fn? agent/validate-transaction))

    ;; Test help function
    (is (string? (with-out-str (agent/help))))))

(deftest browser-inspector-functions
  (testing "Browser inspector function definitions"
    ;; Test that our JavaScript functions are well-formed
    (is (string? (browser-store-inspector)))
    (is (string? (reference-integrity-checker)))
    (is (string? (performance-metrics-collector)))

    ;; Verify functions contain expected keywords
    (let [inspector-code (browser-store-inspector)]
      (is (clojure.string/includes? inspector-code "inspectEvolverStore"))
      (is (clojure.string/includes? inspector-code "extractMapData"))
      (is (clojure.string/includes? inspector-code "integrity")))

    (let [checker-code (reference-integrity-checker)]
      (is (clojure.string/includes? checker-code "checkReferenceIntegrity"))
      (is (clojure.string/includes? checker-code "issues"))
      (is (clojure.string/includes? checker-code "healthy")))

    (let [metrics-code (performance-metrics-collector)]
      (is (clojure.string/includes? metrics-code "collectPerformanceMetrics"))
      (is (clojure.string/includes? metrics-code "performance.now"))
      (is (clojure.string/includes? metrics-code "inspectionTime")))))

;; TODO: Add Chrome DevTools integration when MCP tools are available in test context
;; These would be the actual integration tests:
;; 
;; (deftest chrome-devtools-integration
;;   (testing "Store inspection via Chrome DevTools"
;;     (let [result (chrome-devtools/evaluate-script (browser-store-inspector))]
;;       (is (map? result))
;;       (is (contains? result :summary))
;;       (is (contains? result :integrity)))))
;;
;; (deftest ui-interaction-integrity
;;   (testing "Store integrity after UI interactions"
;;     ;; Take initial snapshot
;;     (let [initial (chrome-devtools/evaluate-script (browser-store-inspector))]
;;       ;; Perform click action
;;       (chrome-devtools/click "element-id")
;;       ;; Check integrity after interaction
;;       (let [after-click (chrome-devtools/evaluate-script (reference-integrity-checker))]
;;         (is (:healthy after-click))
;;         (is (empty? (:issues after-click)))))))
;;
;; (deftest performance-monitoring
;;   (testing "Performance metrics collection"
;;     (let [metrics (chrome-devtools/evaluate-script (performance-metrics-collector))]
;;       (is (number? (:inspectionTime metrics)))
;;       (is (pos? (:nodeCount metrics)))
;;       (is (pos? (:storeSize metrics))))))