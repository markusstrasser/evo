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
;; Plugin Validation
;; ============================================================================

(defn- validate-plugin!
  "Validate plugin structure. Throws ex-info on invalid plugin."
  [{plugin-type :type :keys [compiler renderer] :as plugin}]
  (cond
    (not plugin-type)
    (throw (ex-info "Plugin must have :type" {:plugin plugin}))

    (not (keyword? plugin-type))
    (throw (ex-info "Plugin :type must be a keyword"
                    {:plugin plugin :type plugin-type}))

    (not (ifn? compiler))
    (throw (ex-info "Plugin :compiler must be invocable (fn/multimethod/var)"
                    {:plugin plugin :compiler compiler}))

    (not (ifn? renderer))
    (throw (ex-info "Plugin :renderer must be invocable (fn/multimethod/var)"
                    {:plugin plugin :renderer renderer}))

    :else plugin))

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
  [plugin]
  (let [validated (validate-plugin! plugin)]
    (swap! registry assoc (:type validated) validated)
    validated))

(defn get-plugin
  "Get registered plugin by card type. Returns nil if not found."
  [card-type]
  (@registry card-type))

(defn registered-types
  "Get all registered card types as a sorted vector."
  []
  (-> @registry keys sort vec))

;; ============================================================================
;; Plugin Compilation Hook
;; ============================================================================

(defn- extract-card-type
  "Extract card type from intent if it's a create-card operation."
  [{:keys [op card-type]}]
  (when (= op :srs/create-card)
    card-type))

(defn- plugin-compiler
  "Get plugin compiler function, or nil if not found."
  [card-type]
  (some-> card-type get-plugin :compiler))

(defn compile-with-plugin
  "Compile SRS intent using plugin compiler if available.
   Falls back to default compile-srs-intent.

   Plugin resolution flow:
   1. Extract card-type (only for :srs/create-card ops)
   2. Lookup plugin by card-type
   3. Use plugin compiler if found, else default compiler"
  [intent db]
  (let [card-type (extract-card-type intent)
        compiler (or (plugin-compiler card-type)
                     compile/compile-srs-intent)]
    (compiler intent db)))

;; ============================================================================
;; Built-in Card Types
;; ============================================================================

(defn- basic-card-renderer
  "Render basic card with front/back structure."
  [card]
  (let [{:keys [front back]} (:props card)]
    {:type :basic
     :html (str "<div class='card-basic'>"
                "<div class='front'>" front "</div>"
                "<div class='back'>" back "</div>"
                "</div>")}))

(defn register-basic-card!
  "Register the default :basic card type (already in compile.cljc)."
  []
  (register-card-type!
   {:type :basic
    :schema nil ; Uses default CreateCard schema
    :compiler compile/compile-srs-intent
    :renderer basic-card-renderer}))

;; ============================================================================
;; Example: Image Occlusion Plugin
;; ============================================================================

(defn- build-media-ops
  "Build create-node and place operations for occlusion masks.
   Each occlusion becomes a :media node child of the card."
  [occlusions card-id]
  (mapcat
   (fn [{:keys [id shape]}]
     (let [media-id (compile/gen-id "media")]
       [(compile/make-create-node media-id :media
                                  {:media/type :image-occlusion
                                   :media/mask-id id
                                   :media/shape shape})
        (compile/make-place media-id card-id :last)]))
   occlusions))

(defn- image-occlusion-compiler
  "Compile image-occlusion card intent to kernel operations.
   Creates card, content, and multiple media nodes for each occlusion mask."
  [{:keys [card-id deck-id markdown-file props]} _db]
  (let [content-id (compile/gen-id "content")

        ;; Extract occlusion-specific props
        {:keys [image-url occlusions question answer]
         :or {occlusions []
              question "Identify the highlighted region"
              answer ""}} props

        ;; Card props with SRS metadata
        card-props {:card-type :image-occlusion
                    :markdown-file markdown-file
                    :image-url image-url
                    :srs/interval-days 1
                    :srs/ease-factor 2.5
                    :srs/due-date (compile/now-instant)}

        ;; Content props for question/answer
        content-props {:question question
                       :answer answer}

        ;; Build media operations for all occlusion masks
        media-ops (build-media-ops occlusions card-id)]

    ;; Combine: create card/content, place in deck, add media children
    (concat
     [(compile/make-create-node card-id :card card-props)
      (compile/make-create-node content-id :card-content content-props)
      (compile/make-place card-id deck-id :last)
      (compile/make-place content-id card-id :last)]
     media-ops)))

(defn- image-occlusion-renderer
  "Render image-occlusion card with SVG overlay for masks."
  [card]
  (let [image-url (get-in card [:props :image-url])]
    {:type :image-occlusion
     :html (str "<div class='card-image-occlusion'>"
                "<img src='" image-url "'/>"
                "<svg class='occlusion-overlay'>...</svg>"
                "</div>")}))

(defn register-image-occlusion-plugin!
  "Register image-occlusion card type.
   Creates :media child nodes for occlusion masks (Codex innovation)."
  []
  (register-card-type!
   {:type :image-occlusion
    :schema nil
    :compiler image-occlusion-compiler
    :renderer image-occlusion-renderer}))

;; ============================================================================
;; Initialize Default Plugins
;; ============================================================================

(defn init-default-plugins!
  "Register all built-in card type plugins."
  []
  (register-basic-card!)
  (register-image-occlusion-plugin!))
