(ns shell.render.image
  "Handler for inline :image nodes. Delegates to `components.image/Image`.

   Block-level image-only blocks (with resize handles and distinct click
   semantics) are rendered directly by `components.block/image-block-content`;
   this handler is for images that appear INSIDE prose."
  (:require [shell.render-registry :refer [register-render!]]
            [components.image :as image]))

(register-render! :image
  {:handler
   (fn [node _ctx]
     (let [{:keys [path alt width]} (nth node 1)]
       (image/Image {:path path
                     :alt alt
                     :width width
                     :block-level? false})))})
