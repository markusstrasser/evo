(ns shell.render-registry
  "Registry mapping AST tag → render handler.

   Handler contract:
     (fn [node ctx]) → Replicant-consumable hiccup

   where `node` is a `parser.ast` vector `[:tag attrs content]` and `ctx`
   is a plain map (see `components.block` for the keys). Handlers MUST
   NOT mutate ctx. Children render via recursive `render-node` calls.

   Shape mirrors `kernel.intent/register-intent!` and
   `kernel.derived-registry/register!`: a `defonce` atom plus
   idempotent registration. Re-registering the same tag replaces (useful
   for hot reload).

   Unknown tags throw — there is NO fallback to plain text. Unknown
   tags are bugs, not content.")

(defonce ^:private registry (atom {}))

(defn register-render!
  "Register a render handler for an AST node tag.

   Spec: {:handler (fn [node ctx]) → hiccup}
   Optional: :doc, :fragment? (informational)

   Returns the tag."
  [tag spec]
  {:pre [(keyword? tag) (map? spec) (fn? (:handler spec))]}
  (swap! registry assoc tag spec)
  tag)

(defn registered-tags
  "Set of tags with registered handlers. REPL introspection."
  []
  (set (keys @registry)))

(defn render-node
  "Dispatch to the handler for NODE's tag. Throws on unknown tags."
  [node ctx]
  (let [tag (nth node 0)
        spec (get @registry tag)]
    (if spec
      ((:handler spec) node ctx)
      (throw (ex-info "No render handler for AST tag"
                      {:tag tag
                       :node node
                       :registered (set (keys @registry))
                       :hint "Did the corresponding shell.render.* namespace load?"})))))

(defn render-all
  "Map `render-node` across a sibling vector. Returns a vector of
   hiccup elements ready to splat into a parent container."
  [nodes ctx]
  (mapv #(render-node % ctx) nodes))

#?(:clj
   (defn clear!
     "Test-only: drop all handlers. Use sparingly — breaks live renders."
     []
     (reset! registry {})
     nil))
