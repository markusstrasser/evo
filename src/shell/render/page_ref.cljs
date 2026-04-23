(ns shell.render.page-ref
  "Handler for [[page-ref]] nodes. Delegates to the existing
   `components.page-ref/PageRef` component so click-to-navigate
   behavior is a single source of truth."
  (:require [shell.render-registry :refer [register-render!]]
            [components.page-ref :as page-ref]))

(register-render! :page-ref
  {:handler
   (fn [node ctx]
     (let [page-name (-> node (nth 1) :name)]
       (page-ref/PageRef {:db (:db ctx)
                          :page-name page-name
                          :on-intent (:on-intent ctx)})))})
