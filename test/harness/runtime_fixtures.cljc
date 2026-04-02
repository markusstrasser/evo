(ns harness.runtime-fixtures
  "Shared bootstrap for isolated integration tests.

   Integration namespaces exercise api/dispatch and intent/session flows, so
   they must load the shipped plugin manifest explicitly instead of depending on
   unrelated namespace load order."
  (:require [plugins.manifest :as plugins]))

(defn bootstrap-runtime
  "Load the shipped plugin registrations once for an integration namespace."
  [f]
  (plugins/init!)
  (f))
