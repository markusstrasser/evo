(ns lab.srs.plugin
  "Plugin registry for extensible card types.
   Based on Codex proposal: plugins register schema, compiler, and renderer."
  (:require [lab.srs.compile :as compile]))

;; ============================================================================
;; Plugin Registry (atom-based, simple)
;; ============================================================================

(defonce ^:private registry
  (atom {}))

;; ============================================================================
;; Plugin Registration
;; ============================================================================

(defn register-card-type!
  "Register a new card type plugin.

   Example:
   (register-card-type!
     {:type :cloze
      :schema ClozeCardSchema
      :compiler (fn [intent db] [...kernel-ops...])
      :renderer (fn [card] {:html ...})})"
  [{plugin-type :type :as plugin}]
  (when-not plugin-type
    (throw (ex-info "Plugin must have :type" {:plugin plugin})))
  (swap! registry assoc plugin-type plugin)
  plugin)

(defn get-plugin
  "Get registered plugin by card type."
  [card-type]
  (get @registry card-type))

(defn registered-types
  "Get all registered card types."
  []
  (keys @registry))

;; ============================================================================
;; Plugin Compilation Hook
;; ============================================================================

(defn compile-with-plugin
  "Compile SRS intent using plugin compiler if available.
   Falls back to default compile-srs-intent."
  [intent db]
  (if-let [card-type (and (= (:op intent) :srs/create-card)
                          (:card-type intent))]
    (if-let [plugin (get-plugin card-type)]
      ;; Use plugin compiler
      ((:compiler plugin) intent db)
      ;; Fall back to default
      (compile/compile-srs-intent intent db))
    ;; Non-create-card intents use default
    (compile/compile-srs-intent intent db)))

;; ============================================================================
;; Built-in Card Types
;; ============================================================================

(defn register-basic-card!
  "Register the default :basic card type (already in compile.cljc)."
  []
  (register-card-type!
   {:type :basic
    :schema nil ; Uses default CreateCard schema
    :compiler compile/compile-srs-intent
    :renderer (fn [card]
                {:type :basic
                 :html (str "<div class='card-basic'>"
                            "<div class='front'>" (get-in card [:props :front]) "</div>"
                            "<div class='back'>" (get-in card [:props :back]) "</div>"
                            "</div>")})}))

;; ============================================================================
;; Example: Image Occlusion Plugin
;; ============================================================================

(defn register-image-occlusion-plugin!
  "Register image-occlusion card type.
   Creates :media child nodes for occlusion masks (Codex innovation)."
  []
  (register-card-type!
   {:type :image-occlusion
    :schema nil
    :compiler
    (fn [{:keys [card-id deck-id markdown-file props]} _db]
      (let [content-id (compile/gen-id "content")
            image-url (get props :image-url)
            occlusions (get props :occlusions [])
            card-props {:card-type :image-occlusion
                        :markdown-file markdown-file
                        :image-url image-url
                        :srs/interval-days 1
                        :srs/ease-factor 2.5
                        :srs/due-date (compile/now-instant)}
            content-props {:question (get props :question "Identify the highlighted region")
                           :answer (get props :answer "")}
            media-ops (mapcat
                       (fn [occ]
                         (let [media-id (compile/gen-id "media")]
                           [(compile/make-create-node media-id :media
                                                      {:media/type :image-occlusion
                                                       :media/mask-id (:id occ)
                                                       :media/shape (:shape occ)})
                            (compile/make-place media-id card-id :last)]))
                       occlusions)]
        (concat
         [(compile/make-create-node card-id :card card-props)
          (compile/make-create-node content-id :card-content content-props)
          (compile/make-place card-id deck-id :last)
          (compile/make-place content-id card-id :last)]
         media-ops)))
    :renderer
    (fn [card]
      {:type :image-occlusion
       :html (str "<div class='card-image-occlusion'>"
                  "<img src='" (get-in card [:props :image-url]) "'/>"
                  "<svg class='occlusion-overlay'>...</svg>"
                  "</div>")})}))

;; ============================================================================
;; Initialize Default Plugins
;; ============================================================================

(defn init-default-plugins!
  "Register all built-in card type plugins."
  []
  (register-basic-card!)
  (register-image-occlusion-plugin!))
