(ns shell.render.link
  "Handler for :link.

   Dispatches on `parse-evo-target` to pick intent and CSS class:
     - evo://page/<name>     → :navigate-to-page
     - evo://block/<id>      → :zoom-in
     - evo://journal/<iso>   → :go-to-journal
   External links open in a new tab; a plain click inside a link does
   not propagate to the block click handler."
  (:require [parser.markdown-links :as md-links]
            [shell.render-registry :refer [register-render! render-all]]))

(defn- internal-click [on-intent intent]
  (fn [e]
    (.preventDefault e)
    (.stopPropagation e)
    (when on-intent
      (on-intent intent))))

(defn- external-click [e]
  (.stopPropagation e))

(register-render! :link
  {:handler
   (fn [node ctx]
     (let [attrs (nth node 1)
           children (nth node 2)
           target (:target attrs)
           on-intent (:on-intent ctx)
           label-children (render-all children ctx)
           evo (md-links/parse-evo-target target)]
       (case (:type evo)
         :page
         (into [:a.evo-link.evo-page-link
                {:href target
                 :title (str "Open page: " (:page-name evo))
                 :on {:click (internal-click on-intent
                                             {:type :navigate-to-page
                                              :page-name (:page-name evo)})}}]
               label-children)

         :block
         (into [:a.evo-link.evo-block-link
                {:href target
                 :title (str "Zoom to block: " (:block-id evo))
                 :on {:click (internal-click on-intent
                                             {:type :zoom-in
                                              :block-id (:block-id evo)})}}]
               label-children)

         :journal
         ;; :go-to-journal's handler forwards its :journal-title to
         ;; navigate-or-create-page, which calls `journal-page?` to
         ;; classify. `journal-page?` already accepts ISO form, so
         ;; passing the raw iso-date works and keeps the handler
         ;; pure-cljc (no js/Date formatting in the render path).
         (into [:a.evo-link.evo-journal-link
                {:href target
                 :title (str "Go to journal: " (:iso-date evo))
                 :on {:click (internal-click on-intent
                                             {:type :go-to-journal
                                              :journal-title (:iso-date evo)})}}]
               label-children)

         ;; Unknown evo:// or plain URL → external link
         (into [:a.markdown-link
                {:href target
                 :target "_blank"
                 :rel "noopener noreferrer"
                 :title target
                 :on {:click external-click}}]
               label-children))))})
