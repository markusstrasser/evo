(ns evolver.commands
  (:require [evolver.kernel :as K]
            [evolver.middleware :as MW]))

;; ---------- 0) Tiny utilities ----------
(defn db [store] @store)
(defn sel [store] (or (get-in (db store) [:view :selected]) #{}))
(defn sel1 [store] (first (sel store)))
(defn pos-of [store id] (K/node-position (db store) id))
(defn set-sel! [store s] (swap! store assoc-in [:view :selected] s))
(defn toggle-in-set [S x] ((if (contains? S x) disj conj) S x))

(defn apply! [store cmd]
  (when cmd
    (reset! store (MW/safe-apply-command-with-middleware @store cmd))))

(defn modifiers [event]
  (when-let [e (:replicant/dom-event event)]
    {:shift? (.getModifierState e "Shift")
     :ctrl? (.getModifierState e "Control")
     :meta? (.getModifierState e "Meta")
     :stop! #(.stopPropagation e)}))

(defn ctx [store]
  (let [s (sel store)]
    {:db (db store)
     :selected s
     :selected1 (first s)
     :pos (fn [id] (K/node-position (db store) id))}))

;; ---------- 1) Pure intent → command builders ----------
(def intent->cmd
  {;; creation
   :create-child-block
   (fn [{:keys [selected1]}]
     (when selected1
       {:op :insert
        :parent-id selected1
        :node-id (K/gen-new-id)
        :node-data {:type :div :props {:text "New child"}}
        :position nil}))

   :create-sibling-above
   (fn [{:keys [selected1 pos]}]
     (when selected1
       (let [{:keys [parent index]} (pos selected1)]
         {:op :insert :parent-id parent :node-id (K/gen-new-id)
          :node-data {:type :div :props {:text "New sibling"}}
          :position index})))

   :create-sibling-below
   (fn [{:keys [selected1 pos]}]
     (when selected1
       (let [{:keys [parent index]} (pos selected1)]
         {:op :insert :parent-id parent :node-id (K/gen-new-id)
          :node-data {:type :div :props {:text "New sibling"}}
          :position (inc index)})))

   ;; structure moves
   :indent
   (fn [{:keys [selected1 pos]}]
     (when selected1
       (let [{:keys [index parent children]} (pos selected1)]
         (when (> index 0)
           {:op :move :node-id selected1
            :new-parent-id (nth children (dec index))
            :position nil}))))

   :outdent
   (fn [{:keys [selected1 pos]}]
     (when selected1
       (let [{:keys [parent]} (pos selected1)]
         (when (not= parent "root")
           (let [{p2 :parent i2 :index} (pos parent)]
             {:op :move :node-id selected1
              :new-parent-id p2
              :position (inc i2)})))))

   ;; references (requires exactly 2 selected)
   :add-reference
   (fn [{:keys [selected]}]
     (when (= 2 (count selected))
       (let [[from to] (vec selected)]
         {:op :add-reference :from-node-id from :to-node-id to})))

   :remove-reference
   (fn [{:keys [selected]}]
     (when (= 2 (count selected))
       (let [[from to] (vec selected)]
         {:op :remove-reference :from-node-id from :to-node-id to})))

   ;; reorder within parent
   :move-up
   (fn [{:keys [selected1 pos db]}]
     (when selected1
       (let [{:keys [parent index]} (pos selected1)
             new (max 0 (dec index))]
         (when (not= index new)
           {:op :reorder :node-id selected1 :parent-id parent
            :from-index index :to-index new}))))

   :move-down
   (fn [{:keys [selected1 pos db]}]
     (when selected1
       (let [{:keys [parent index]} (pos selected1)
             sibs (get-in db [:children-by-parent parent])
             new (min (dec (count sibs)) (inc index))]
         (when (not= index new)
           {:op :reorder :node-id selected1 :parent-id parent
            :from-index index :to-index new}))))

   ;; navigation helpers that return navigation info instead of commands
   :nav-up
   (fn [{:keys [selected1 db]}]
     (when selected1
       (K/get-prev db selected1)))

   :nav-down
   (fn [{:keys [selected1 db]}]
     (when selected1
       (K/get-next db selected1)))

   :nav-sibling-up
   (fn [{:keys [selected1 pos db]}]
     (when selected1
       (let [{:keys [parent index]} (pos selected1)]
         (when (> index 0)
           (let [siblings (get-in db [:children-by-parent parent])]
             (nth siblings (dec index)))))))

   :nav-sibling-down
   (fn [{:keys [selected1 pos db]}]
     (when selected1
       (let [{:keys [parent index]} (pos selected1)
             siblings (get-in db [:children-by-parent parent])]
         (when (< (inc index) (count siblings))
           (nth siblings (inc index))))))

   :nav-parent
   (fn [{:keys [selected1 db]}]
     (when selected1
       (let [parent-id (K/get-parent db selected1)]
         (when (and parent-id (not= parent-id "root"))
           parent-id))))

   :nav-first-child
   (fn [{:keys [selected1 db]}]
     (when selected1
       (K/get-first-child db selected1)))

   :nav-last-child
   (fn [{:keys [selected1 db]}]
     (when selected1
       (K/get-last-child db selected1)))})

;; ---------- 2) Thin UI handlers (effects kept here on purpose) ----------
(defn select-node [store event {:keys [node-id]}]
  (when-let [{:keys [shift? ctrl? meta? stop!]} (modifiers event)] (stop!))
  (let [cur (sel store)
        chord? (let [{:keys [shift? ctrl? meta?]} (modifiers event)] (or shift? ctrl? meta?))
        next (if chord? (toggle-in-set cur node-id) #{node-id})]
    (swap! store assoc-in [:view :selected] next)
    (swap! store K/log-operation {:op :select-node :node-id node-id})))

(defn set-selected-op [store event _]
  (let [v (some-> event :replicant/dom-event .-target .-value)]
    (swap! store assoc :selected-op (when (not= v "") (keyword v)))))

(defn apply-selected-op [store _ _]
  (when-let [op (:selected-op (db store))]
    (when-let [build (intent->cmd op)]
      (apply! store (build (ctx store))))))

(defn hover-node [store _event {:keys [node-id]}]
  (let [referencers (K/get-references @store node-id)]
    (swap! store assoc-in [:view :hovered-referencers] referencers)))

(defn unhover-node [store _event {:keys [node-id]}]
  (swap! store assoc-in [:view :hovered-referencers] #{}))

(defn undo! [store _ _] (reset! store (MW/safe-apply-command-with-middleware @store {:op :undo})))
(defn redo! [store _ _] (reset! store (MW/safe-apply-command-with-middleware @store {:op :redo})))

;; Navigation helpers that update selection
(defn navigate-to [store target-node]
  (when target-node
    (set-sel! store #{target-node})))

;; ---------- 3) Registry (now tiny & consistent) ----------
(def command-registry
  {:select-node select-node
   :set-selected-op set-selected-op
   :apply-selected-op apply-selected-op
   :hover-node hover-node
   :unhover-node unhover-node
   :undo undo!
   :redo redo!

   ;; view-only helpers
   :clear-selection (fn [s _ _] (set-sel! s #{}))
   :select-all-blocks (fn [s _ _] (set-sel! s (set (keys (:nodes (db s))))))

   ;; navigation commands (use nav helpers from intent->cmd)
   :navigate-sequential (fn [s _ {:keys [direction]}]
                          (let [build (intent->cmd (case direction :up :nav-up :down :nav-down))]
                            (navigate-to s (build (ctx s)))))

   :navigate-sibling (fn [s _ {:keys [direction]}]
                       (let [build (intent->cmd (case direction :up :nav-sibling-up :down :nav-sibling-down))]
                         (navigate-to s (build (ctx s)))))

   :select-parent (fn [s _ _]
                    (navigate-to s ((intent->cmd :nav-parent) (ctx s))))

   :select-first-child (fn [s _ _]
                         (navigate-to s ((intent->cmd :nav-first-child) (ctx s))))

   :select-last-child (fn [s _ _]
                        (navigate-to s ((intent->cmd :nav-last-child) (ctx s))))

   ;; direct intents (single node operations)
   :create-child-block (fn [s _ _] (apply! s ((intent->cmd :create-child-block) (ctx s))))
   :create-sibling-above (fn [s _ _] (apply! s ((intent->cmd :create-sibling-above) (ctx s))))
   :create-sibling-below (fn [s _ _] (apply! s ((intent->cmd :create-sibling-below) (ctx s))))
   :move-block (fn [s _ {:keys [direction]}]
                 (let [k (case direction :up :move-up :down :move-down)]
                   (apply! s ((intent->cmd k) (ctx s)))))

   ;; multi-node structural operations
   :indent-block (fn [s _ _]
                   (doseq [id (sel s)]
                     (let [build (intent->cmd :indent)
                           cmd (build (assoc (ctx s) :selected1 id))]
                       (when cmd (apply! s cmd)))))

   :outdent-block (fn [s _ _]
                    (doseq [id (sel s)]
                      (let [build (intent->cmd :outdent)
                            cmd (build (assoc (ctx s) :selected1 id))]
                        (when cmd (apply! s cmd)))))

   ;; multi-node operations
   :delete-selected-blocks
   (fn [s _ _]
     (doseq [id (sel s)]
       (apply! s {:op :delete :node-id id :recursive true}))
     (set-sel! s #{}))

   ;; toggle view state
   :toggle-collapse (fn [s _ _]
                      (let [selected (sel1 s)]
                        (when selected
                          (let [current-collapsed (get-in @s [:view :collapsed] #{})
                                new-collapsed (toggle-in-set current-collapsed selected)]
                            (swap! s assoc-in [:view :collapsed] new-collapsed)))))})

(defn dispatch-command [store event [k params]]
  (if-let [h (command-registry k)]
    (try (h store event params)
         (catch js/Error e
           (swap! store K/log-message :error (str "Command failed: " k)
                  {:command k :params params :error (.-message e)})))
    (swap! store K/log-message :error (str "Unknown command: " k)
           {:command k :params params})))

(defn dispatch-commands [store event actions]
  (doseq [a (if (sequential? (first actions)) actions [actions])]
    (dispatch-command store event a)))