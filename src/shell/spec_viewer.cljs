(ns shell.spec-viewer
  "Standalone entry point for the spec handbook.

   Keeping this out of shell.editor keeps the main outliner bundle from
   importing the large documentation/browser surface."
  (:require [replicant.dom :as d]
            [components.spec-viewer :as spec-viewer]
            [plugins.manifest :as plugins]))

(defn main []
  (plugins/init!)
  (d/render (js/document.getElementById "root")
            (spec-viewer/SpecViewer)))
