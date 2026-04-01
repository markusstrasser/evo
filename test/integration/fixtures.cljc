(ns integration.fixtures
  "Shared integration-test bootstrap.

   Integration tests exercise api/dispatch and intent/session flows, so they
   need the shipped plugin registrations loaded explicitly instead of relying on
   ambient side effects from broader test runs."
  (:require [plugins.manifest :as plugins]))

(defn bootstrap-runtime
  "Load the explicit plugin manifest once for an integration test namespace."
  [f]
  (plugins/init!)
  (f))
