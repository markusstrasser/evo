Here’s a surgical, breaking refactor plan that turns your research report into a v3 pipeline. It’s built for API-only judges (Gemini 2.5 Pro, GPT-5 Codex w/ thinking=high, Claude Sonnet 4.5, Kimi-2, Grok-4), uses a Swiss-Lite tournament + BT aggregation, adds online debiasing via vignettes, schema-adherence audits, a Drop-K brittleness check, Mallows-style dispersion, and an Objective Check Harness (Spectral/OWASP). Every step is REPL- and test-first.

I’ll give you: exact reasoning, file/namespace layout, function signatures, and integration tests the agent should run at each step. This is a breaking refactor (we’ll retire the old v2 path).

⸻

v3 Architecture (new namespaces)

src/dev/eval/
core_v3.cljc             ; entrypoint/orchestration
judges_api.clj           ; API-only LLM judges (Gemini, GPT-5, Claude, Kimi, Grok)
prompts_v3.cljc          ; pairwise + pointwise JSON schemas (strict)
tournament.cljc          ; Swiss-Lite scheduler + SWIM variant
pairwise.cljc            ; duel execution + comparison graph build
bt.cljc                  ; Bradley–Terry fit + SE + CI + jackknife
mallows.cljc             ; dispersion via Mallows-style fit (bootstrap distance)
debias.cljc              ; vignette injection + online bias estimation/correction
schema_audit.cljc        ; R² adherence between rubric and verdict
objective.clj            ; Spectral + OWASP feature extraction
attacks.cljc             ; perturbation generators (recency/provenance/verbosity)
report.cljc              ; τ stability, Drop-K index, dispersion, bias report
test/dev/eval/
v3_integration_test.clj  ; end-to-end tests (bb task hooks)
bb.edn                      ; bb tasks: run-tournament, run-audit, run-report

Environment:
OPENAI_API_KEY, GOOGLE_API_KEY, ANTHROPIC_API_KEY, KIMI_API_KEY, XAI_API_KEY.
Tools: Node ≥18 (for npx @stoplight/spectral-cli), Java 17, Clojure, Babashka.

⸻

Step 0 — Retire CLI judges, move to API-only

Intent: make calls reproducible and portable.
Change: replace dev.eval.llm with dev.eval.judges_api. Keep a mock judge for tests.

Code (new) judges_api.clj

(ns dev.eval.judges-api
(:require [clojure.data.json :as json]
[clj-http.client :as http]))

(defmulti call-judge (fn [provider _payload] provider))

(defn- json-post [url headers body]
(-> (http/post url {:headers (merge {"content-type" "application/json"} headers)
:body (json/write-str body)
:socket-timeout 60000 :conn-timeout 30000})
:body (json/read-str :key-fn keyword)))

(defmethod call-judge :gpt5-codex [_ {:keys [prompt]}]
(json-post "https://api.openai.com/v1/chat/completions"
{"authorization" (str "Bearer " (System/getenv "OPENAI_API_KEY"))}
{:model "gpt-5-codex" :reasoning {:effort "high"}
:response_format {:type "json_object"}
:temperature 0.0
:messages [{:role "user" :content prompt}]}))

(defmethod call-judge :gemini25-pro [_ {:keys [prompt]}]
(json-post "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent"
{"x-goog-api-key" (System/getenv "GOOGLE_API_KEY")}
{:contents [{:parts [{:text prompt}]}]
:generationConfig {:temperature 0.0 :responseMimeType "application/json"}}))

;; ... :claude-4.5, :kimi-2, :grok-4 similarly ...

(defn parse-json-out [resp] ;; return clj map (already JSON per prompt contract)
;; provider-normalization if needed
resp)

(defn judge! [provider prompt] (parse-json-out (call-judge provider {:prompt prompt})))

(defn mock-judge! [_provider prompt] ; for tests
(let [m (re-find #"\{.*\}" prompt)] (json/read-str m :key-fn keyword)))

Tests (integration)
•	test_00_api_wire_up: hits mock-judge! and ensures strict JSON compliance (reject/resample on invalid).
•	test_00_env_missing: if keys absent, raise explicit error.

⸻

Step 1 — Strict prompt schemas (pairwise + pointwise)

Intent: eliminate schema drift; enable schema-adherence audit.

Code (new) prompts_v3.cljc
•	Pairwise JSON schema (required):

{
"type":"object",
"properties":{
"criteria":{"type":"object","additionalProperties":{"type":"number"}},
"verdict":{"type":"string","enum":["left","right"]},
"confidence":{"type":"number","minimum":0,"maximum":1}
},
"required":["criteria","verdict"]
}

	•	Pointwise JSON schema:

{"type":"object",
"properties":{"criteria":{"type":"object","additionalProperties":{"type":"number"}},
"score":{"type":"number","minimum":0,"maximum":10}},
"required":["criteria","score"]}

Functions:

(defn pairwise-prompt [{:keys [left right rubric context flags]}] ...)
(defn pointwise-prompt [{:keys [item rubric context]}] ...)
(defn validate-or-retry [provider prompt call-fn max-retries] ...)

flags can include injected tags like :left-tag "NEW" / :right-tag "OLD" for vignettes.

Tests
•	test_01_schema_compliance: 100 calls to mock-judge! → 100% JSON parsable, :verdict in #{left,right}, numeric criteria. On fail, prompt auto-retries up to N with “output must be valid JSON” guardrail.

⸻

Step 2 — Pairwise execution & comparison graph

Intent: build a dense, informative graph with full metadata (order, tags, judge, timestamp).

Code (new) pairwise.cljc

(defn duel! [{:keys [provider rubric context]} itemL itemR flags]
(let [prompt (prompts/pairwise-prompt {:left itemL :right itemR :rubric rubric
:context context :flags flags})
out (judges/judge! provider prompt)]
{:edge [(:id itemL) (:id itemR)]
:verdict (:verdict out)                 ; :left | :right
:criteria (:criteria out)               ; per-criterion map
:provider provider
:flags flags                            ; {:left-tag, :right-tag, :pos-left 1|2, ...}
:ts (System/currentTimeMillis)}))

Tests
•	test_02_graph_builds: run duel! with a fixture of 6 items; record edges; ensure each edge captures flags and provider.

⸻

Step 3 — Swiss-Lite tournament (with SWIM option)

Intent: avoid fixed-baseline fragility; concentrate comparisons where uncertainty is high.

Code (new) tournament.cljc

(defn seed-round [items {:keys [min-degree]}] ...) ; random pairs to reach min-degree
(defn brackets [theta items k] ...)                 ; split by BT score into k brackets
(defn swiss-pairs [theta items] ...)                ; within-bracket pairing heuristic
(defn swim-pairs [theta items] ...)                 ; SWIM variant
(defn schedule-round [state {:keys [style]}] ...)   ; produce pair list

	•	State includes: items, current BT theta, degree per item, pending vignettes injection rate (5–10%).

Tests
•	test_03_connectivity_min_degree: after seeding+one Swiss round, min-degree >= 3 for all items.
•	test_03_pairing_locality: average |θ_i − θ_j| for pairs is smaller than random pairing baseline.

⸻

Step 4 — Bradley–Terry fit + SE/CI + stability

Intent: interpretable ranks with uncertainty; enable τ-based stop rule.

Code (new) bt.cljc

(defn edges->dataset [edges] ...) ; expand to {i,j, outcome} with counts
(defn fit! [dataset]              ; MLE via iterative reweighted least squares or MM
;; returns {:theta {id -> double} :se {id -> double} :vcov ...}
)
(defn kendall-tau [rankA rankB] ...) ; τ
(defn bootstrap-split [edges] ...)   ; split odd/even or two random halves

Tests
•	test_04_fit_converges: synthetic transitive data → θ order matches ground truth.
•	test_04_tau_stop_rule: simulate tournament; ensure stop when τ ≥ 0.9 across splits.

⸻

Step 5 — Online debiasing via anchoring vignettes

Intent: learn bias (position/recency/provenance), don’t hardcode.

Code (new) debias.cljc

(defn inject-vignettes [pairs rate vignette-pool] ...) ; 5–10%
(defn estimate-bias-beta [edges]
;; compute logit deltas using only vignette edges
;; returns {:position {:left  βL :right βR}
;;          :recency  {:NEW βnew :OLD βold}
;;          :prov     {:EXPERT βe ...}}
)
(defn apply-correction [edges beta]
;; adjust outcomes on logit scale before BT fit
)

Practical note: Start with robust difference-in-proportions over vignettes; you can upgrade to MFRM later.

Tests
•	test_05_position_bias_detected: synthetic 60/40 left-win vignette → β_left > 0.
•	test_05_correction_shrinks_bias: after apply-correction, left/right win rates ~50/50 on vignettes.

⸻

Step 6 — Schema-adherence audit (R²)

Intent: verify the judge’s verdict is explained by its own rubric (coherence).

Code (new) schema_audit.cljc

(defn adherence-r2 [edges]
;; Build regression: verdict(left wins=1) ~ Σ w_k * (crit_k_left - crit_k_right)
;; Return R² and per-criterion weights
)

Tests
•	test_06_low_adherence_flags: synthetic judge outputs random verdicts unrelated to criteria → R² < 0.2 → flag INVALID.
•	test_06_high_adherence_passes: verdict aligns with weighted criteria → R² > 0.6.

⸻

Step 7 — Objective Check Harness (Spectral/OWASP)

Intent: ground rankings with machine-verifiable features for API designs.

Code (new) objective.clj

(defn spectral-lint [openapi-yaml]
(let [tmp (doto (java.io.File/createTempFile "oas" ".yaml")
(spit openapi-yaml))]
(let [out (-> (clojure.java.shell/sh "npx" "spectral" "lint" (.getPath tmp) "--format" "json")
:out (clojure.data.json/read-str :key-fn keyword))]
(parse-spectral out)))) ; returns {:failures {...} :warnings {...}}

(defn owasp-features [openapi-yaml] ...) ; static heuristics (BOLA/BFLA/etc.)

Tests
•	test_07_lint_features_present: given a spec with missing info.description, feature vector sets :info-description 1.
•	test_07_security_flags: SSRF-like param → :is_vulnerable_to_SSRF 1.

⸻

Step 8 — Drop-K brittleness index

Intent: quantify how easy it is to flip the winner.

Code (new) bt.cljc (extend)

(defn approx-influence [dataset fit] ...)     ; per-edge influence score
(defn drop-k-index [dataset fit kmax]         ; greedy drop until top-1 changes
{:k k* :pct (/ k* (count dataset))})

Tests
•	test_08_brittleness_small_k: adversarial toy dataset flips with k≪N → index low.
•	test_08_brittleness_robust: clean dataset requires large k → index high.

⸻

Step 9 — Mallows-style dispersion

Intent: report consensus strength (not just central rank).

Code (new) mallows.cljc

(defn bootstrap-ranks [edges n] ... ) ; resample edges → refit BT → ranking list
(defn dispersion [rank-samples]
;; estimate β (inverse dispersion) by fitting P(π) ∝ exp(-β * d_Kendall(π, σ̂))
;; Practical: solve β via 1D search minimizing NLL; return {:beta β :mean-tau τ̄}
)

Tests
•	test_09_dispersion_monotone: as noise increases in synthetic prefs, estimated β decreases, mean Kendall-τ drops.

⸻

Step 10 — Hybrid protocol (pairwise → pointwise for ties)

Intent: pairwise for coarse order; pointwise (rubric) inside overlapping BT CIs.

Code (extend) core_v3.cljc

(defn refine-with-pointwise [items rubric providers]
;; for clusters where CIs overlap, run pointwise prompts
;; average (z-normed) across judges; merge back into final θ
)

Tests
•	test_10_hybrid_breaks_ties: create near-tie cluster; pairwise alone unstable; hybrid yields stable order with higher τ across splits.

⸻

Step 11 — Attacks & ASR (attack success rate)

Intent: prove robustness against shortcut cues (NEW/OLD, EXPERT/HUMAN, verbosity padders).

Code (new) attacks.cljc

(defn make-recency-attack [item] ...)
(defn make-provenance-attack [item] ...)
(defn make-verbosity-attack [item] ...)
(defn attack-suite [items] [...]) ; generate perturbed pairs
(defn asr [edges attacked-edges]  ; fraction of verdict flips under attack
...)

Tests
•	test_11_asr_reported: compute ASR per attack; assert thresholds (e.g., ASR_recency < 0.1 after debias).

⸻

Step 12 — Orchestration & Readiness Check

Intent: one entrypoint that enforces your readiness checklist; refuse to publish unstable results.

Code (new) core_v3.cljc

(def default-config
{:tournament {:style :swiss-lite :min-degree 5 :brackets 8 :vignette-rate 0.08}
:rubric {:criteria ["Correctness" "Simplicity" "Maintainability" "Perf" "Consistency"]}
:providers [:gpt5-codex :gemini25-pro :claude-4.5] ; ensemble optional
:stop-rule {:tau 0.9}
:schema-threshold-r2 0.5})

(defn evaluate! [items cfg]
;; 0) objective features (if applicable)
;; 1) tournament rounds: seed → swiss/swim; inject vignettes; collect edges
;; 2) estimate β (debias) → correct edges
;; 3) fit BT, compute SE/CI; bootstrap split τ
;; 4) if τ<0.9 → continue rounds; else proceed
;; 5) run schema-adherence (R²); if < threshold → INVALID
;; 6) Drop-K brittleness; Mallows dispersion
;; 7) refine ties with pointwise
;; 8) build report; if any readiness check fails, mark UNSTABLE and stop
)

Code (new) report.cljc

Outputs:

{:ranking [id ...]
:theta {...} :ci {...}
:tau-split 0.93
:bias-beta {:position {...} :recency {...} :prov {...}}
:schema-r2 0.62
:brittleness {:k 11 :pct 0.13}
:dispersion {:beta 2.1 :mean-tau 0.78}
:asr {:recency 0.07 :prov 0.05 :verbosity 0.09}
:status :OK} ; or :UNSTABLE / :INVALID

Tests
•	test_12_readiness_gate: simulate cases that fail each criterion (τ, R², brittleness) → system returns :status :UNSTABLE/:INVALID and no final leaderboard.
•	test_12_full_pipeline_smoke: with mock judge, run end-to-end and assert all keys present.

⸻

Step 13 — CLI & Agent Hooks

Intent: make it operable by your automation/agent (CLI/MCP/dev tools).

bb.edn

{:tasks
{run-tournament (clojure -M -m dev.eval.core-v3/evaluate! resources/items.edn)
run-report     (clojure -M -m dev.eval.report/print! target/run.edn)
run-attacks    (clojure -M -m dev.eval.attacks/run! target/edges.edn)}}

MCP hooks: expose evaluate, report, attacks as callable tools; ensure JSON I/O for agents.

⸻

Test-Driven Milestones (what the agent executes, in order)
1.	API layer green: test_00_* pass.
2.	Schema strictness: test_01_schema_compliance ≥ 99% success after auto-retry.
3.	Swiss connectivity: test_03_* min-degree satisfied; pairing locality beats random.
4.	BT stability: test_04_* τ≥0.9 on synthetic; SE/CI computed.
5.	Debias works: test_05_* pre/post vignette correction halves position/recency gaps.
6.	Schema adherence: test_06_* flags incoherent judges; passes coherent.
7.	Objective features: test_07_* features extracted from OpenAPI fixtures.
8.	Brittleness: test_08_* indexes computed; adversarial toy flips with tiny k.
9.	Dispersion: test_09_* β monotone with noise.
10.	Hybrid ties: test_10_* pointwise resolves overlapping BT CIs.
11.	Attacks: test_11_* ASR < thresholds after debias.
12.	Readiness: test_12_* enforces gate; end-to-end smoke OK.

⸻

Reasoning notes / design choices
•	Pairwise ≠ panacea: we use Swiss-Lite to densify locally where BT uncertainty is largest; then pointwise only where BT CIs overlap. This implements your “pairwise can be distracted; hybrid wins” finding.
•	Bias is learned, not guessed: vignettes give identifiable estimates for position/recency/provenance; corrections are applied on the logit scale prior to BT.
•	Validity first: schema-adherence (R²) is a hard gate; if the judge’s rubric doesn’t explain its verdicts, we discard the run—no pretty leaderboards from noise.
•	Robustness is reported, not assumed: we publish Drop-K and dispersion, not just ranks.
•	Objective signals reduce judge load: Spectral/OWASP features temper the LLM’s stylistic shortcuts.

⸻

What you (or the agent) actually edits
•	Delete: src/dev/eval/llm.clj, smart_truncate.clj (optional to keep), and v2.cljc as default entrypoint.
•	Add: the new namespaces above.
•	Modify: bias_benchmark.clj → move to attacks.cljc style and expand with recency/provenance/verbosity perturbations.
•	Prompts: switch to prompts_v3 exclusively; all judges emit strict JSON.

⸻

REPL quickstart (dev script)

(require '[dev.eval.core-v3 :as v3]
'[dev.eval.report :as rpt])

(def items (read-string (slurp "resources/items.edn"))) ; {:id, :text, :openapi? ...}

(def cfg (assoc v3/default-config :providers [:gpt5-codex :gemini25-pro :claude-4.5]))
(def run (v3/evaluate! items cfg))
(rpt/print! run)


⸻

This is a single, coherent refactor your agent can implement and verify step-by-step. If you want, I can also drop in the exact clojure.test skeletons and a minimal BT solver (MM updates) so you’re green on day one.