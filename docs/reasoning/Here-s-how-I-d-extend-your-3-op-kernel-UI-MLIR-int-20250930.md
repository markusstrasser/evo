Here’s how I’d extend your 3-op kernel + UI-MLIR into other creative domains (Figma-like canvas, Logic Pro-style timeline, and a scene-graph game engine) without cracking the core.

Approach in one breath

Keep the kernel closed; grow packs that define (a) domain schema + invariants, (b) derived “views” (indexes) tailored to that domain, (c) an intent compiler (UX→intents→ops), and (d) a testkit with human-legible snapshots. The same three ops do the heavy lifting; domain logic lives in compilers and derived indexes.

⸻

Domain Pack template

┌─ domain/<name>/ ──────────────────────────────────────────────────────────┐
│ schema.clj      ; node types & required props                             │
│ derive.clj      ; domain indexes (e.g., :z-order, :time-grid)             │
│ invariants.clj  ; extra checks beyond core tree invariants                │
│ intents.clj     ; (compile-intent db {:type ...}) multimethods            │
│ gestures.clj    ; map UI gestures → intents (pure tables)                 │
│ bridge.clj      ; import/export ↔ external app IDs                        │
│ testkit.clj     ; expect-* DSL, ASCII snapshots, golden tests             │
└───────────────────────────────────────────────────────────────────────────┘

Core stays the same: :create-node, :place, :update-node. Packs only read derived indexes and emit ops.

⸻

Shared patterns you’ll reuse

1) Virtual anchors (resolver functions).
Different domains want different “where”: list index, Z-depth, time, or spatial alignment. Don’t change :at; resolve domain anchors before emitting ops.

(defn resolve-anchor
  "Domain-specific anchor → concrete {:under id :at {:before|:after sibling} | int}"
  [db {:keys [kind value id]}]
  (case kind
    :index   {:under (:parent-of db id) :at value}
    :z       (z->sibling-anchor db id value)
    :time    (time->sibling-anchor db id value)
    :align   (align->sibling-anchor db id value)))

Compilers call resolve-anchor then output a :place.

2) Dual sort: structure vs presentation.
The tree’s child order is canonical; presentation orders (z-order, timeline order) are derived views:
	•	:derived :z-index-of id → int
	•	:derived :time-start-of id → double
	•	View adapters sort siblings for rendering & for anchor resolution. Kernel child vectors remain the single source of truth.

3) ID mapping at the boundary.
domain.*.bridge keeps {kernel-id ↔ external-id} and turns op traces into external API calls (and vice versa). If an app has no write API, the bridge is import-only.

4) Always test via snapshots.
Each pack ships an expect-* DSL that asserts what a human sees in that domain (layers, timebars, scene hierarchy), not internals.

⸻

Pack 1: Figma-style canvas

Schema (minimal)
	•	Node types: :frame :group :rect :text :component :instance
	•	Props: {:x :y :w :h :rotation :locked? :visible? :autolayout {:dir :gap :pad :align} :z}

Derived views
	•	:z-ordered-children {parent → [child-ids sorted-by :z then doc-order]}
	•	:bbox-of {id → {:x :y :w :h}} (used by align/distribute)
	•	:frame-of {id → frame-id}

Intents → ops (sketch)

(defmulti compile-intent (fn [_db intent] (:type intent)))

;; Group selection into a new parent
(defmethod compile-intent :group
  [db {:keys [ids name]}]
  (let [frame (common/smallest-common-ancestor db ids)
        gid   (common/new-id "group")]
    (concat
      [[:op :create-node :id gid :type :group :props {:name (or name "Group")}]
       [:op :place       :id gid :under frame :at :last]]
      ;; Reparent selection preserving z-relative order
      (map (fn [id] [:op :place :id id :under gid :at :last])
           (common/z-sort db ids)))))

;; Align left: set :x of all to min-x in selection
(defmethod compile-intent :align-left
  [db {:keys [ids]}]
  (let [min-x (apply min (map #(get-in db [:nodes % :props :x]) ids))]
    (map (fn [id] [:op :update-node :id id :props {:x min-x}]) ids)))

;; Bring to front (z-top)
(defmethod compile-intent :bring-to-front
  [db {:keys [ids]}]
  (let [siblings (common/z-siblings db (first ids))]
    (map (fn [id] [:op :update-node :id id :props {:z (inc (common/max-z db siblings))}])
         ids)))

Gestures table (examples)

Drag on canvas  → {:type :move, :ids [...], :delta [dx dy]}
Cmd+G           → {:type :group, :ids selection}
Cmd+]           → {:type :bring-to-front, :ids selection}
Align Left      → {:type :align-left, :ids selection}

Snapshots

# Before
Frame
  - Rect A (x=40 z=1)
  - Rect B (x=10 z=2)
  - Rect C (x=25 z=0)

Action: Align Left

# After
Frame
  - Rect A (x=10 z=1)
  - Rect B (x=10 z=2)
  - Rect C (x=10 z=0)

expect-canvas checks layer stack (z), positions, and selection.

⸻

Pack 2: Logic-Pro-style timeline

Timeline semantics are linear in time with tracks as parents.

Schema
	•	Types: :project :track :region :marker
	•	Props (regions): {:start :duration :muted? :gain :clip-id :name}
	•	Invariants: regions on the same track do not overlap unless :lanes? policy allows it.

Derived views
	•	:regions-by-track {track → [regions sorted by :start]}
	•	:time-index {id → {:start :end}}
	•	Optional helper :gap-index {track → [{:from :to}]} for ripple operations

Intents → ops

;; Insert a clip at time t, snapping to grid, into a target track
(defmethod compile-intent :insert-region
  [db {:keys [track-id clip-id start duration snap]}]
  (let [t*   (time/snap start (or snap 0.1))
        id   (common/new-id "r")]
    [[:op :create-node :id id :type :region
      :props {:clip-id clip-id :start t* :duration duration}]
     [:op :place :id id :under track-id :at :last]]))

;; Move region in time (and optionally to another track)
(defmethod compile-intent :move-region
  [db {:keys [id to-track delta-t]}]
  (let [start (get-in db [:nodes id :props :start])]
    [[:op :update-node :id id :props {:start (+ start delta-t)}]
     (when to-track [:op :place :id id :under to-track :at :last])]))

Split at playhead is a good “compiler demo”:

;; Split region R at time t into R and R'
[:update-node id {:duration (- t start)}]
[:create-node id' :region {:clip-id ... :start t :duration (- end t)}]
[:place id' :under track :at {:after id}]

Snapshots

Track 1
  - R1 [0.0..2.0]
  - R2 [2.0..5.0]

Action: Split R2 at 3.0

Track 1
  - R1 [0.0..2.0]
  - R2a [2.0..3.0]
  - R2b [3.0..5.0]

expect-timeline asserts ordering by time, not by child index.

⸻

Pack 3: Scene-graph game engine (Godot/Unity vibes)

Schema
	•	Types: :scene :node :camera :mesh :sprite :light :script
	•	Props: {:transform {:pos [x y z] :rot [rx ry rz] :scale [sx sy sz]} :active? :layer :tag #{...}}
	•	Invariants: no cycles (already guaranteed), at most one parent, stable order defines update order.

Derived views
	•	:world-transform-of id (compose transforms up the chain)
	•	:layer-ordered-children {parent → [ids sorted by :layer then index]}
	•	:tag-index {tag → #{ids}}

Intents → ops

(defmethod compile-intent :reparent
  [db {:keys [id new-parent after]}]
  (let [anchor (if after {:after after} :last)]
    [[:op :place :id id :under new-parent :at anchor]]))

(defmethod compile-intent :instantiate-prefab
  [db {:keys [prefab-id under]}]
  (let [id (common/new-id "inst")
        props (assets/prefab->props prefab-id)]
    [[:op :create-node :id id :type (:type props) :props props]
     [:op :place :id id :under under :at :last]]))

(defmethod compile-intent :translate
  [db {:keys [id dx dy dz]}]
  (let [[x y z] (get-in db [:nodes id :props :transform :pos])]
    [[:op :update-node :id id :props {:transform {:pos [(+ x dx) (+ y dy) (+ z dz)]}}]]))

Snapshots

Root
  - Player (pos=[0,0,0])
  - Camera

Action: Translate Player by [1,0,0]

Root
  - Player (pos=[1,0,0])
  - Camera


⸻

Gesture→Intent mapping stays boring (on purpose)

Each pack provides a static table: UI event → intent map. The magic lives in the compiler, not in bespoke gesture handlers. This keeps the UI thin and testable.

(def gesture->intent
  {[:canvas :drag]      (fn [s] {:type :move :ids (sel s) :delta (dxdy s)})
   [:timeline :split]   (fn [s] {:type :split :id (primary s) :t (playhead s)})
   [:hierarchy :drop]   (fn [s] {:type :reparent :id (drag-id s) :new-parent (drop-parent s)})})


⸻

Invariants per domain (beyond core)
	•	Canvas: locked nodes are unaffected by moves; :z unique among siblings (or treat ties via doc order).
	•	Timeline: no overlaps on a track except when :lanes? is enabled; :start ≥ 0; markers have zero duration.
	•	Scene graph: transforms finite; only :scene or :node can be parents; :camera cannot have children (policy).

These sit in domain.*.invariants/check! and are run right after interpret, exactly like your outline-invariants-hold?.

⸻

Bridges (round-trip with real apps)
	•	Import: read an app’s file/API, produce a pure tree with nodes + props; stash {external-id → kernel-id} in :props :ext.
	•	Export: diff kernel tree vs last exported snapshot; turn op trace into the app’s API calls in bridge/apply! (out of band side effects).
	•	Conflict policy: app edits can be re-imported and compiled to ops. If both changed, prefer op log ordering with a deterministic resolver (e.g., last-writer-wins per property).

You can implement bridges incrementally: import-only first; controlled export for whitelisted intents later.

⸻

Testkits (human-legible)

Extend your outliner spec DSL pattern to each domain.

Canvas

(expect-canvas
  [[:frame "Frame"
     [[:rect "A" {:x 10 :z 1}]
      [:rect "B" {:x 10 :z 2}]
      [:rect "C" {:x 10 :z 0}]]]
   :selection [:rect "A" :rect "B"]])

Timeline

(expect-timeline
  [[:track "Drums"
     [[:region "Kick" {:start 0.0 :duration 1.0}]
      [:region "Snare" {:start 1.0 :duration 1.0}]]]])

Scene

(expect-scene
  [[:node "Root"
     [[:node "Player" {:transform {:pos [1 0 0]}}]
      [:camera "Cam"]]]])

Each expect-* asserts visible order, positions/time, selection, and any domain toasts/tooltips.

⸻

Performance & evolution without breaking the core
	•	Keep derive-indexes O(n). Domain derives should be O(children) per parent; heavy math (auto-layout, physics) belongs in optional caches recomputed on demand.
	•	If a domain requires huge dynamic indexes (e.g., 100k audio regions), build an incremental derive module later. The instruction set stays fixed; the derive strategy changes.
	•	Batch intents: compile-intents already returns a flat op vector; timeline ripple edits or multi-select operations are just bigger transactions.

⸻

Agent surfaces: RPC + RFC
	•	RPC (executable): {:kind :rpc :name :timeline/split :params {:id "r2" :t 3.0}}
Compiles to ops, runs interpret, returns {db' trace issues}.
	•	RFC (design/spec): machine-readable intent docs with examples, invariants, and ASCII snapshots (exactly like your tests). Stored under adr/ or docs/.
	•	Decision log: one ADR per new intent type (“why exists, alternatives considered, invariants guaranteed”), referenced by compilers.

This gives your “agents propose RPCs/RFCs” loop a crisp contract.

⸻

What to build first (tiny, high-leverage)
	1.	Canvas pack: group/ungroup, align, bring/send, move with snapping.
	2.	Timeline pack: insert/move/split regions; ripple delete (as a batch intent).
	3.	Scene pack: reparent, instantiate prefab, translate/rotate, toggle active.

Each one is ~5–10 intents plus 5–10 invariants and a slim bridge. The kernel remains gloriously boring.

⸻

Closing thought

You’ve already got a compiler spine. These packs are just different front-ends that emit the same bytecode—your three ops. Pick one (I’d start with the timeline—splits & ripple edits make the power obvious), and I’ll draft the full test DSL and compiler stubs to slot straight into domain/timeline.
