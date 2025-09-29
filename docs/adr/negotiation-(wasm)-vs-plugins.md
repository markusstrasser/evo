
negotiation ≠ “just plugins,” but it’s not magic either. It’s a typed, constraint-solved, multi-dispatch layer sitting above plugins that (a) chooses among competing handlers at runtime, (b) proves why something can’t run (capability gaps), and (c) records the choice so replays are deterministic. Classic plugins are “if intent X then call fn Y.” Negotiation is “given X + world W + constraints C, select a plan from many candidates whose requires ⊆ provides(W) and best satisfies C; else return a structured gap.” That unlocks fallback, optimization, and explainability. It’s not more “genius”; it’s a different contract.

> this is basically coercion graphs from my proseflow project in 2020

What changes concretely vs your current register-intent/derive/validate:
•	Multiplicity & ranking: many candidates per intent; selection is late-bound by context (latency budget, collab mode, device). Plugins today are single-target dispatch.
•	Typed requires/provides closure: candidates declare :requires #{:tree/order :graph/index}; the world declares :provides #{…}. The solver proves satisfiability (or returns a gap object). Plain plugins just “no handler.”
•	First-class failure semantics: gaps are structured deltas the LLM/adapters can act on (decompose, install capability, or ask human). That’s qualitatively different from a missing function.
•	Optimization surface: you can pick plans by cost (:ops-count, :reflow-cost, :remote?) and constraints (:≤8ms). Static plugins can’t trade off.
•	Introspection: “What can we do here?” becomes a query over manifests, not source spelunking.
•	Deterministic audit: the chosen plan + world snapshot is logged, so identical replays don’t depend on load order or accidents.

If you want the smallest possible delta from “plugins” to “negotiation,” do this and stop:
1.	Manifest per module (EDN/Malli):

{:module :structure/wrap
:provides #{:intent/structure.wrap}
:requires #{:cap/tree :cap/order}
:selector (fn [ctx] {:cost 3 :ok? true})           ;; ranking hook
:lower    (fn [db intent] [{:op :k/create ...} ...])}

	2.	World = union of :provides from loaded modules + adapters.
	3.	Solver = filter candidates where requires ⊆ world; rank via :selector using ctx (latency, collab, platform); pick the argmin; if none, return {:gap :missing-capability :required #{…}}.
	4.	Trace the chosen candidate + world hash. Property-test: same world+intent ⇒ same plan.

When is this worth it?
•	You target multiple shells (TUI/Web/Native) or modes (solo/collab) where the best lowering varies at runtime.
•	You want LLMs to repair plans using machine-readable gaps.
•	You need policy (e.g., “no network in this workspace”) to prune candidates without changing handlers.
•	You care about explainability and reproducibility under changing extensions.

When is it overkill?
•	Single renderer, single policy, and one obvious handler per intent. Then “plugins” (direct dispatch) are fine; add negotiation later.

TL;DR: negotiation is a plugin system that treats handlers as plans with preconditions and costs and makes the planner explicit. That buys you late binding, optimality, and actionable failures—all things plain plugins don’t give you.


> ok so it's an abstraction ... can we bolt it on later and no do simple plugins (struct editing , graph etc)?


Yes—but only if you treat “plugins now” as a degenerate negotiation (exactly one candidate) and freeze a few contracts so you don’t paint yourself into a corner.

Do this minimal no-regret scaffolding now (cheap), and full negotiation becomes a drop-in later:
•	Single coordinator: all intents go through one function, not direct calls. (evaluate db intent ctx).
•	World registry: keep a tiny world map today (namespaced capability keys). It can be inert now, real later.
•	Handler shape: handlers return plans, not naked ops. Allow the trivial form to keep code short.
•	Accept: (fn [db intent ctx] [{:op ...} ...]) or (fn [...] {:ops [...], :requires #{:cap/tree}, :cost 0, :id :structure/wrap.basic}).
•	Wrapper lifts the vector form to the map form.
•	Requires check + gap object: if :requires ⊄ world, return {:gap :missing-capability :required #{…}} (today you just surface an error; tomorrow the LLM can decompose).
•	Deterministic tie-break: store :cost (default 0) and a stable :id. Today selection is “first”; tomorrow it’s argmin by :cost.
•	Trace the decision: log {:candidate :structure/wrap.basic :world-hash …} so replays don’t depend on load order.
•	Pure, total handlers: no hidden IO, no direct DB mutation—only core ops; time/random come via ctx.
•	Versioned, namespaced caps: :graph/backlinks.v1, not strings or ad-hoc flags.
•	Derived/validate stay pure and separate: extensions can add derive passes and policy validators, but never mutate.
•	Property tests now: (1) derive==recompute after each applied op, (2) tx associativity, (3) no-op place idempotence.

Tiny wrapper (sketch):

(defonce world (atom #{:cap/tree :cap/order}))

(defn register! [intent-id handler] (swap! registry assoc intent-id handler))

(defn evaluate [db intent ctx]
(let [cands (keep (fn [[id h]]
(when (= id (:intent intent))
(let [p (normalize-plan (h db intent ctx))]
(assoc p :id (or (:id p) id) :cost (or (:cost p) 0)))))
@registry)
ok    (filter #(clojure.set/subset? (:requires % #{}) @world) cands)]
(if-let [plan (first (sort-by :cost ok))]
(-> (apply-ops db (:ops plan))
(attach-trace {:candidate (:id plan) :world-hash (hash @world)}))
{:gap :missing-capability :required (reduce clojure.set/union #{} (map :requires cands))})))

Net: you can ship “simple plugins” (structure, graph, etc.) immediately. By enforcing plan+requires+cost and a world placeholder now, you’ve already installed the socket for real negotiation later—no refactors, just add multiple candidates and a selector.



## Action

Label the underlying problem: Selection, not dispatch. Plugins give handlers; negotiation chooses plans with preconditions and costs under a typed world.

Freeze these contracts (tiny, testable):
•	Capability manifest (per module)
{:module :structure/wrap   :provides #{:intent/structure.wrap}   :requires #{:cap/tree :cap/order}   :cost (fn [ctx] 3) ; pure, deterministic   :lower (fn [db intent ctx] [{:op :k/create ...} ...])}
•	World (host state): #{:cap/tree :cap/order :cap/view-diff ...} (namespaced, versioned).
•	Plan / Gap types
Plan := {:id kw :requires set :ops vector :cost int}
Gap  := {:gap :missing-capability :required set :context ctx-snapshot}
•	Evaluator (single choke point):
(negotiate db intent ctx world) -> {plan|gap} then (interpret db plan.ops)
Log {:candidate plan.id :world-hash (hash world) :ctx-fingerprint ...}.
•	Laws/tests: (1) deterministic selection under same world, intent, ctx; (2) tx associativity; (3) place idempotence; (4) derive==recompute after each op.

Minimal algorithm (ranked filter, no solver theater):
1.	collect candidates for intent
2.	filter by requires ⊆ world
3.	compute pure cost(ctx); pick arg-min with stable tie-break (:id)
4.	none? return Gap (machine-actionable)
5.	apply plan; trace decision.

Where your text should land

Replace most of Part IV with a 1-page “Negotiation Core Spec” (the above). Then keep a short “Formalisms appendix”:
•	Operads → your architectural wiring diagrams (legal composition patterns).
•	Session types → protocol for multi-round intents (e.g., collab merge) if/when you add them.
•	Refinement types → capability constraints you want statically checked later.
Everything else is optional frosting.

Why this isn’t “just plugins”

In one line: plugins provide handlers; negotiation proves satisfiability and optimality under a typed world and returns actionable gaps. That buys fallback, policy pruning, and reproducible plans—things a dispatch table can’t do.