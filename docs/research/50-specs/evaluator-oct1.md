Design Specification: Debiased LLM Evaluation of AI-Generated Architecture Proposals

Introduction and Background

Evaluating AI-generated software architecture proposals (e.g. API designs) requires careful mitigation of biases inherent in large language model (LLM) judges. Research has shown that LLM-based evaluators, much like humans, exhibit systematic cognitive biases – including position bias, verbosity bias, and others ￼. These biases can distort judgment, causing the “best” proposal to be mis-scored or overlooked. The goal of this design is to create a simple, robust evaluation system that leverages LLM judgments without succumbing to such biases, thereby fairly ranking multiple AI-written proposals and identifying the optimal design.

Key Challenges (Biases in LLM Evaluation):
LLM evaluators tend to display the following failure modes, which our system must address:
•	Position Bias: Tendency to favor a proposal due to its order in the prompt. For example, many LLM judges prefer whichever response is in a particular position (often the second/last) about 60–70% of the time ￼. One study found GPT-3.5 favored the first option ~50% of the time, while Claude-v1 favored it ~70% ￼ – a significant skew from an unbiased 50/50.
•	Verbosity (Length) Bias: Models often overrate longer, more verbose answers. In evaluations, GPT-3.5 and Claude-v1 chose a longer rephrased answer over a concise one >90% of the time ￼, even when no new information was added. This bias means more wordy proposals can be unfairly advantaged ￼.
•	Sycophancy (Agreeability Bias): In multi-agent or iterative settings, LLMs tend to agree with each other or with a perceived consensus rather than critiquing content ￼. This leads to premature convergence on potentially incorrect ideas ￼. In debates, sycophancy can collapse disagreement and reduce accuracy below even single-model performance ￼ ￼. Our design avoids direct multi-agent debates to minimize this effect.
•	Self-Preference Bias: An LLM judge may give higher scores to text that resembles its own style or content. Experiments showed GPT-4 rated answers it generated as best 10% more often (Claude-v1 by 25% more) ￼. Since our proposals are AI-written (possibly by similar models), we must ensure the evaluator doesn’t unduly favor one proposal just because it “sounds” like the judge.
•	Recency and Framing Effects: The order of criteria or context can also sway scores. For example, the last criterion scored may get about 3.5% lower ratings on average due to a recency bias ￼. Minor prompt wording differences or label choices can swing preferences by 5–10% ￼. The system must present prompts in a balanced way to reduce such effects.

Implication: Without mitigation, these biases can lead to inconsistent or unfair evaluations – e.g. a mediocre but long-winded proposal might outrank a concise superior one, or the first-listed proposal might be systematically under- or over-valued. Therefore, the evaluation system should explicitly neutralize these biases to fairly identify the best architecture proposal.

System Objectives

Our solution is guided by the following requirements:
•	Fairness: Ensure each proposal is judged on content merit, not its position or verbosity. Introduce deliberate debiasing steps (randomization, normalization, calibration) to counter known LLM evaluator biases.
•	Consistency: Obtain reliable rankings by aggregating multiple independent evaluations. Reduce variance in scoring so that the top-ranked proposal is truly robustly better across evaluations (not due to one lucky ordering).
•	Efficiency: Keep the approach lightweight. We favor a minimal number of LLM calls and simple post-processing over complex multi-agent consensus rounds. (Research suggests diminishing returns beyond a few evaluation passes ￼.) The design should work within reasonable token budgets and use parallelism for speed.
•	Simplicity and Practicality: Avoid over-engineered multi-agent orchestration. Use a single type of judge model repeatedly with straightforward concurrency, which is easier to implement and maintain. Only add complexity (e.g. multiple different agent judges or advanced voting schemes) if clearly justified by performance gains.
•	Adaptability: The system should be flexible to different criteria or prompt settings (especially tuned to software architecture/API design evaluation). It will allow custom evaluation criteria relevant to architecture quality (e.g. correctness, clarity, extensibility) and scale to varying numbers of proposals.

By meeting these objectives, the system aims to yield an 80/20 solution – achieving ~80% of the benefit of very elaborate evaluation schemes with only ~20% of the complexity. In fact, many research papers note that simpler methods (like a few shuffled evaluations with calibration) often perform as well as far more complex multi-agent debates in practice ￼ ￼.

High-Level Solution Overview

At a high level, the evaluation will consist of multiple parallel LLM scoring passes over the proposals, with randomized order and result aggregation. Below is the overall workflow:

graph TB;
subgraph "Input Preparation";
A[AI-Generated Proposals] --> B[Length Normalizer];
end;
subgraph "Multiple Evaluation Passes";
B --> P1[Shuffle Order 1];
B --> P2[Shuffle Order 2];
B --> P3[Shuffle Order 3];
%% Three shuffles (configurable count)
P1 --> E1[LLM Evaluation 1];
P2 --> E2[LLM Evaluation 2];
P3 --> E3[LLM Evaluation 3];
%% Each Ei is an independent API call to the LLM
end;
subgraph "Debiasing & Aggregation";
E1 --> C[Position Score Calibration];
E2 --> C;
E3 --> C;
C --> M[Median Score Fusion];
M --> D[Consensus & Tie-Breaking];
end;
D --> W[Best Proposal Selected];

Figure: System Pipeline. Each proposal list is normalized for length then fed to the LLM in multiple random orders (parallel calls). After scoring, position biases are calibrated before combining results (median) into a final ranking. If needed, a cycle-detection or Condorcet check can handle ties or ambiguities.

Rationale for This Design
•	Multiple Shuffled Evaluations: By shuffling proposal order for each LLM evaluation round, we “average out” position and framing biases ￼. One round’s bias (e.g. always favoring whatever is option B) will be counteracted by other rounds where that same proposal appears in different positions. Empirically, using a small number of random-order passes can eliminate a large portion of positional and labeling bias ￼. We choose 3 rounds as a default, which prior studies and internal tests indicate captures the majority of bias reduction, without too many extra API calls. (Beyond ~3–5 rounds yields diminishing returns in accuracy gain.)
•	Length Normalization: Before evaluation, we trim or tag long responses to neutralize verbosity bias. Truncating each proposal to a fixed maximum length (and appending an indicator of original length) prevents an LLM from simply favoring the longest text ￼. This forces the model to focus on content quality rather than length. Note: We don’t discard length info entirely – sometimes longer can mean more detailed – but we ensure every proposal is evaluated on equal footing up to a length threshold (e.g. first 500 characters, with a note of total length beyond that).
•	Single-Model Consensus (vs Multi-Model): Rather than orchestrating multiple different agent judges (which could collude or echo each other’s errors ￼), we use one strong model (or API) for all evaluations, applied multiple times. This avoids inter-model sycophancy (agents agreeing without independent thought) and variability. Each evaluation pass is independent and stateless – the model is not told about previous rounds’ outputs. This prevents context contamination, where an LLM might be influenced by its own prior judgments or discussions in a multi-turn debate. Essentially, we prefer a vote-by-sampling from one judge model over a chat among many judges.
•	Aggregation via Median Voting: Once we have several scored lists from the LLM, we aggregate them into a final ranking. Using the median score for each proposal (across rounds) is robust to outliers – if one round was noisy or biased for a particular item, the median dampens its effect. Median or majority voting has been found to be a simple and effective consensus method, often outperforming complicated weighting schemes in practice ￼. We will also compute a measure of agreement (e.g. the standard deviation of the scores) as a confidence indicator – low variance means the rank is more reliable.
•	Position Score Calibration: As an extra debiasing measure, we apply a slight positional calibration to each round’s scores before aggregation. Recent research (e.g. CalibraEval) shows that LLM judges’ preferences can be made more consistent by adjusting for their known positional biases ￼ ￼. For example, if a model tends to give the first-listed option an undeserved boost, we down-weight the score in the round whenever a proposal appeared first. Conversely, we might up-weight scores for proposals that were last in the list, if last position is usually disadvantaged. These weight factors can be empirically tuned. In our prototype, we use a preset correction vector (e.g. [0.65, 0.85, 0.95, 1.0, 1.05, 1.10] for positions 1 through 6) derived from bias studies – meaning, the first item’s score would be multiplied by 0.65 (a penalty to counter over-scoring) while the last item gets a 10% boost ￼. This calibration improved fairness by roughly 20–30% in evaluation benchmarks ￼ ￼.
•	Consensus and Tie-Breaking: In most cases, the median ranking will produce a clear winner (highest median score). However, if proposals are very close or the votes are inconsistent (e.g. A beats B in two rounds but loses in one, forming a cycle), we include a final Condorcet check. This step pairwise compares top contenders to see if one proposal wins against all others (Condorcet winner). If a cycle is detected (no clear winner), the system can flag a tie among top tier proposals instead of arbitrarily picking one. This is a safeguard for transparency – in the rare event of a ranking ambiguity, a human or additional specialized criteria might break the tie. (Such scenarios are expected to be <5% if our scoring is well-calibrated.)

In summary, the design shuffles (to remove position effects), normalizes length (to remove verbosity advantage), repeats independent evaluations (to reduce randomness and bias), calibrates known biases, and then aggregates to find the truly best proposal with high confidence. It does this with minimal rounds and a single model, avoiding the need for heavyweight multi-agent deliberation.

Detailed Implementation Plan

This section outlines how to implement the evaluation system in practice. We focus on a pragmatic approach using Clojure (a good fit for concurrent API calls and functional data handling), but the logic can be adapted to any language.

1. Preprocessing Proposals

Each proposal (typically a text description of an API or architecture design) is first normalized to mitigate length bias and format consistently:
•	Length Normalization: We set a maximum length (e.g. 500 characters) to consider from each proposal when prompting the LLM judge. If a proposal is longer, we truncate it and append an ellipsis with a note of remaining length (e.g. “... [+350 chars]”). This way, every proposal has roughly similar visible length in the prompt ￼. The truncated part’s presence is acknowledged but not fully shown, preventing overly long proposals from dominating by sheer volume.
•	Labeling and Order: Assign neutral labels to proposals (like numeric IDs or placeholder names) to avoid any priming effects. For instance, label them “Proposal 1, Proposal 2, …” in the prompt. Avoid value-laden labels (“Best”, “Worst”, etc.) and keep ordering flexible. We will generate multiple random orderings later, so initially just store proposals in an array or list.

Implementation snippet (Clojure pseudocode):

(def max-len 500)

(defn normalize-proposal [text]
"Trim the proposal to max-len and append length note if truncated."
(if (<= (count text) max-len)
text
(let [visible (subs text 0 max-len)]
(str visible "... [+" (- (count text) max-len) " chars]"))))

All proposals are processed with normalize-proposal to yield a list of normalized texts.

2. Prompt Design for LLM Evaluation

The core of each evaluation round is prompting the LLM to score or rank the proposals. We use a single-turn prompt that presents all proposals (in a certain order) and asks the LLM to evaluate them against defined criteria and produce a score for each.

Key considerations for the prompt:
•	Include a brief instruction of the evaluation criteria relevant to software architecture quality. This ensures consistent criteria usage across rounds (preventing “criteria drift”). For example, we might instruct: “Evaluate each proposal for API design quality on a scale of 1 to 10, considering factors like functionality, clarity, complexity, and maintainability. Higher score = better.”
•	Ask for output in a structured format for easy parsing. For instance: “Output your scores in a JSON object mapping proposal IDs to scores, without additional commentary.” Structured output mitigates risk of the LLM giving a biased explanation or changing its format in different rounds.
•	Keep temperature low (e.g. 0 or very small) to reduce randomness in scoring. We want the model to apply the criteria as deterministically as possible. The variation will come from shuffling, not from high randomness in the model’s behavior.

Example Prompt (pseudo):

System: You are a software architecture expert tasked with evaluating proposals.

User: There are N proposals for an API design. Evaluate each proposal on a scale of 1 to 10 (10 = best) based on:
- Functional completeness and correctness
- Clarity and documentation
- Complexity (lower is better, simpler design)
- Maintainability and extensibility

Provide a score for each proposal ID, in JSON format like {"1": score, "2": score, ...}. No extra commentary.
Proposals:
1. <Proposal 1 text...>
2. <Proposal 2 text...>
   ...
   N. <Proposal N text...>

In each round, the order of proposals (and their ID numbers) will be shuffled, and the prompt regenerated accordingly. The criteria list can be adjusted – e.g., for API design we might emphasize consistency with standards, performance considerations, etc. The key is that it’s explicit and the same each time, to avoid the LLM subtly shifting its evaluation focus mid-way. Research advises using measurable, specific criteria rather than open-ended “which is better?” to get more objective scores ￼ ￼.

3. Parallel Evaluation Rounds

We will perform several evaluation rounds (three by default) in parallel, each with a different random ordering of proposals in the prompt. Concurrency is important to keep latency low since each round is an API call that could take a few seconds; running them simultaneously cuts total wait time. Clojure’s core.async or futures are ideal for this.

Steps per round:
1.	Shuffle the normalized proposals list (e.g. using a random seed or shuffle function) to create a new ordering.
2.	Construct the prompt with this ordering and send the API request to the LLM.
3.	Parse the returned scores (e.g. the JSON string) into a data structure (map of proposal ID -> score).
4.	Re-map scores back to the original proposal IDs: Since we shuffled the IDs for the prompt, we need to translate the scores to the correct original proposal. Essentially, keep track of which original proposal corresponded to “Proposal 1” in this round’s prompt, etc.

Using Clojure pseudocode with core.async threads:

(require '[clojure.core.async :as async])

(def rounds 3)

(defn evaluate-round [id->proposal order]
"Call LLM API with proposals in the given order, return a scores map {orig_id: score}."
(let [prompt (make-evaluation-prompt order)      ; build prompt text from ordered proposals
response (call-llm-api prompt {:temperature 0})  ; synchronous API call (pseudo)
scores-json (parse-json (:text response))]  ; assume response has a JSON text
;; Convert scores-json keys (which are "1","2",... as strings) back to original IDs
(into {}
(for [[rank-id score] scores-json]
(let [orig-id (nth order (dec (Integer. rank-id)))]
[orig-id score])))))

;; Launch parallel evaluations
(let [proposal-ids (keys proposals-map)
eval-chans (for [_ (range rounds)]
(let [order (shuffle proposal-ids)]
(async/thread (evaluate-round proposals-map order))))]
;; Merge results from all rounds
(async/<!! (async/merge eval-chans)))

In this snippet, proposals-map might be a map of {id -> normalized_proposal_text}. We shuffle the keys (IDs) for each round, call the LLM, and parse the results. We use async/merge to collect results from the rounds threads. The outcome will be a sequence of score maps, one per round.

Token Budget Consideration: Each round’s prompt includes all proposals. If there are many proposals or they are long, the token usage grows. To manage this, max-len for normalization is set so that even if we have, say, 10 proposals, the combined prompt stays within a reasonable token limit (for example, 10 * 500 chars ≈ 5000 chars plus instructions, which is within context for most models). If proposals are extremely large or numerous, a preliminary filtering step or batching might be necessary, but in typical use (perhaps 5–10 candidate designs), it’s fine. The user can also adjust rounds based on the available budget (fewer rounds if each call is very expensive). A simple rule is to evenly divide a total token budget across rounds, e.g. if budget is 10k tokens, and each prompt is ~3k, that limits to 3 rounds.

Determinism: We use shuffle without a fixed seed to simulate independent random orderings. This introduces some nondeterministic variance, which is intentional to sample different biases. In testing, if perfectly repeatable results are needed, one could fix the random seed for reproducibility, but generally slight randomness is acceptable and even beneficial here.

4. Bias Mitigation: Position Calibration

After receiving the scores from each round, we apply positional bias correction. This involves adjusting the scores based on the rank position a proposal had in that round’s prompt. If our bias research or calibration tests reveal a consistent pattern (e.g. item at index 1 tends to be overrated or underrated), we multiply that item’s score by a corrective factor.

For example, say we have 5 proposals. We might define a weight array for positions 1–5 as [0.9, 0.95, 1.0, 1.03, 1.07] – meaning we suspect a slight first-position penalty (0.9), a neutral middle, and a slight boost for last position. These numbers can come from prior calibration experiments or defaults from literature (like those in CalibraEval ￼). In the earlier Python snippet, more aggressive weights were shown (0.65 and 1.10 for extremes) – the exact values can be tuned per model. Initially, using mild corrections is safer to avoid overcompensation.

Applying calibration: We know each round’s scores map associates original IDs with a score. But we also know the order list we used. To calibrate: iterate through each proposal in the order used for that round, look up its raw score, multiply by the weight for that position, and update the score.

Pseudo-code:

(def position-weights [0.9 0.95 1.0 1.05 1.10])  ; example for 5 positions

(defn calibrate-round [scores-map order]
(->> order
(map-indexed (fn [idx prop-id]
(let [raw-score (scores-map prop-id)
w (nth position-weights idx nil)]
(if w (* raw-score w) raw-score))))  ; if we have more proposals than weights list, leave as is
(zipmap order)))  ; return a map of id -> adjusted score

We would call calibrate-round for each round’s result using the same order that round had. This yields calibrated score maps. If a proposal was first in a round and got a score 8, and weight[0]=0.9, it becomes 7.2; if it was last with score 7 and weight[4]=1.10, it becomes 7.7, etc. Over multiple rounds, these adjustments counteract any consistent positional bias the model might have ￼.

Note: If the model is perfectly unbiased, these tweaks will just add a bit of noise, which the median will anyway temper. If the model was biased, this should pull scores closer to a true reflection of content quality rather than position. We will monitor the effect; these weights can be disabled or adjusted if not helpful.

5. Aggregating Scores and Determining Winner

Now we have (calibrated) scores for each proposal from each round. We aggregate them to produce a final ranking:
•	Median Score for Each Proposal: Collect the array of scores for proposal X across the 3 rounds and take the median. The median is robust to one round being an outlier ￼. For example, if proposal X got scores [7.2, 8.0, 7.5] in three rounds, the median is 7.5. Similarly compute for all proposals. This yields a final score list. If an even number of rounds is used, we could use mean or middle-two average – but with 3 it’s straightforward.
•	Ranking: Sort proposals by median score (descending). This gives an initial ranking from best to worst. If two proposals share the exact same median, use the next step (tie-break) or secondary criteria if available.
•	Confidence metric: Also compute the spread or variance of each proposal’s scores across rounds. If a proposal’s scores vary widely (high standard deviation), it means the evaluation was unstable for that case – perhaps it was very sensitive to prompt context or borderline in quality. A low stddev indicates the proposal consistently scored around the same, increasing trust that the score is accurate. We will output a confidence measure, e.g. the standard deviation or a qualitative label (high/medium/low confidence) based on it. This helps users know if the “winner” is clear-cut or if it was a close call. For instance, if the top proposal and second proposal differ by 0.1 in median and the confidence is low, one might consider them nearly tied.
•	Condorcet Cycle Check (Tie-breaking): In rare cases with multiple proposals, you might have a situation where A’s median = 8, B’s = 8, C’s = 7.9 – A and B tied. Or more complex: A vs B vs C each won one round head-to-head (rock-paper-scissors scenario). To handle this systematically, we can perform a pairwise preference check: for each pair of proposals, count in how many rounds one outranked the other. If one proposal beats all others (majority of rounds), it’s a Condorcet winner and is chosen ￼. If no Condorcet winner exists (cycle), our system can default to the median ranking or declare a tie among the top tier. Because we already use numeric scores, a simpler approach is: if two top proposals have equal median, look at their mean score across rounds as a tiebreak, or even the highest single-round score as a tiebreak. These are heuristics, but given our bias mitigation, a true tie should be uncommon unless proposals are nearly equivalent.

Finally, the best proposal is the one ranked #1 after this process. The system will output, for example, the proposal ID or content that won, along with the final scores for all proposals and perhaps the confidence metrics.

6. Implementation in Clojure (Async Orchestration)

Bringing it all together, here’s a sketch of the core evaluation function in Clojure combining the above steps. Note this is a conceptual illustration; actual API integration and error handling would need to be added:

(ns evaluator.core
(:require [clojure.core.async :as async]))

(def position-weights [0.9 0.95 1.0 1.05 1.10])  ; adjust length as needed

(defn evaluate-proposals
[proposals {:keys [rounds criteria]
:or {rounds 3
criteria default-criteria-set}}]
"Evaluate a set of proposals (map of id->text). Returns final rankings and scores."
(let [ids (keys proposals)
norm-props (zipmap ids (map normalize-proposal (vals proposals)))]
;; launch parallel evaluation rounds
(let [chans (for [_ (range rounds)]
(async/thread
(let [order (shuffle ids)
scores (evaluate-round norm-props order criteria)  ; call LLM
calibrated (if position-weights
;; apply positional weights if available
(into {} (map-indexed
(fn [i pid]
[pid (* (scores pid)
(nth position-weights i 1.0))])
order))
scores)]
calibrated)))]
(let [all-results (async/<!! (async/merge chans))    ; collect all rounds
;; Transpose the list of maps into map of lists: pid -> [score_r1, score_r2, ...]
scores-by-id (reduce (fn [acc round-map]
(reduce (fn [acc2 [pid sc]]
(update acc2 pid (fnil conj []) sc))
acc round-map))
(zipmap ids (repeat []))
all-results)
final-scores (into {}
(for [[pid score-list] scores-by-id]
[pid {:median (median score-list)
:mean   (average score-list)
:stddev (std-dev score-list)
:scores score-list}]))
ranking (->> final-scores
(sort-by (comp :median val) >)    ; sort descending by median
(map first))]
{:ranking ranking
:details final-scores}))))

In this pseudo-code:
•	evaluate-round would encapsulate prompt creation, API call, and JSON parsing as described earlier (omitted here for brevity; it would use the criteria to fill in the prompt template).
•	We use core.async/thread to spawn each round concurrently.
•	We then merge the channels and block (<!!) to collect all results. This yields a sequence of maps (each map is scores from one round).
•	We reorganize that into scores-by-id, then compute median, mean, stddev for each proposal.
•	Finally we sort proposals by median to produce the ranking list.
•	The result includes the sorted ranking and a details map of each proposal’s stats (which could be used for tie-breaking or reporting).

This implementation focuses on functional purity (no shared mutable state between rounds) and leverages Clojure’s strengths:
•	Immutability: Each round’s data is independent, avoiding any accidental carry-over of state (so no context pollution between rounds).
•	Concurrency: core.async and threads make it straightforward to parallelize API calls, so the overall latency is roughly the slowest single API call (not 3× sequential).
•	Data manipulation: Using maps and sequences to aggregate scores makes it easy to add or remove proposals and rounds.
•	Interactivity: In a REPL, one could tweak parameters (like weights or criteria) and re-run quickly to see effects, facilitating iterative refinement of the evaluation logic.

7. Early Stopping Optimization (Optional)

If the first two rounds already produce a very clear winner (e.g. one proposal consistently scores much higher), the system could optionally abort running further rounds to save time/tokens. For instance, after 2 rounds, if one proposal is ranked first in both and its scores are well above others with low variance, a third round might be unnecessary. A simple check: if the top proposal’s median after 2 rounds is ≥ a threshold and no other is close, then stop. However, this complicates concurrency (since we fire rounds in parallel). It might be easier to run rounds sequentially and decide on the fly, but that sacrifices parallel speed. Given typical usage (3 rounds max), we might skip this unless cost savings are critical. Our design assumes full rounds are done, but it’s a tunable aspect.

8. Handling Ties and Final Decision

After computing final medians, ties are handled as discussed: either via slight differences in mean or a Condorcet method if needed. If using Condorcet: build a pairwise win matrix from the rounds data. For example, count how many rounds each proposal was ranked higher than each other proposal. If one beats all others in >50% of rounds, that’s a winner. This is likely overkill for small N, so a simpler approach: if two proposals tie on median and other stats are also very close, the system can output both as top contenders and possibly request an additional evaluation focusing on differences between those two only. Since the prompt criteria are well-defined, one could do a head-to-head comparative prompt for the tied proposals as a follow-up (using the same LLM judge) to break the tie. This one-off comparison would mimic a mini debate between the two designs, specifically asking for which is better and why, thereby giving a decisive outcome.

Criteria for Evaluation (API Design Use-Case)

It’s important that the LLM judge is evaluating the proposals on the right aspects. For software architecture or API design proposals, we define criteria that are as objective and measurable as possible, rather than vague “quality” impressions ￼. For example, we might use:
•	Functional Fit/Correctness: Does the proposed design satisfy the requirements and use cases? (e.g. all needed endpoints are present, behavior is correct)
•	Complexity: Is the design as simple as possible? (Lower complexity is better – measured by number of components, dependencies, or cyclomatic complexity if code-like)
•	Performance & Scalability: Does the design account for efficiency and scaling? (e.g. proper caching, load balancing considerations)
•	Maintainability: How easy is it to understand, document, and extend the design? (e.g. follows clean architecture principles, low coupling, clear naming)
•	Consistency & Best Practices: Does it follow API design best practices and conventions? (e.g. RESTful principles if applicable, consistent error handling, etc.)

Each criterion can be given a weight to indicate its importance. For instance, if functional correctness is paramount, weight it higher than, say, performance (which might be secondary for a prototype). An example weight distribution:

(def default-criteria-set
[{:name "Functional Correctness" :weight 0.3}
{:name "Complexity (Simplicity)" :weight 0.2}
{:name "Maintainability" :weight 0.2}
{:name "Performance & Scalability" :weight 0.2}
{:name "Consistency/Best-Practices" :weight 0.1}])

(The above weights sum to 1.0; adjust as needed.)

These can be used in the prompt: we can instruct the LLM to consider each aspect when scoring, possibly even ask for sub-scores per aspect. However, asking the model to produce multiple numbers (one per criterion) might increase complexity and token usage. A compromise is to list the aspects in the instruction so the model internalizes them when deciding a single score. If a more granular view is desired, we could request the LLM to output a breakdown, for example:

{
"1": {"Functional Correctness": 8, "Complexity": 7, ... , "Overall": 8},
"2": { ... }
}

and then combine those with weights ourselves. This provides transparency on why a proposal scored well or poorly. The downside is a larger prompt and response. For a focused evaluation like API proposals, a single overall score with criteria in mind may suffice, possibly supplemented by a brief justification from the LLM (if we want explainability). In our primary design we did not ask for explanations (to avoid verbosity bias and keep output simple), but in a real scenario a short rationale for the top choice could be useful for humans. If adding that, it should be done after the scoring is complete, to not let explanation style influence the scoring itself.

Avoiding Common Pitfalls

To ensure our simple system remains effective, we explicitly avoid certain flawed approaches that one might be tempted to use:
•	No cascading self-refinement loops: We do not feed the LLM’s own evaluations back into it in multiple turns (e.g. asking it to “reflect” on its scores or revise them). While it sounds good for an LLM to self-correct, in practice this often amplifies biases or causes the model to rationalize its initial flawed judgment instead of truly correcting it ￼. Our approach resets context every round and never says “previously you scored X”. Each round is fresh.
•	No tournament-style bracket eliminations: Eliminating proposals in a bracket can propagate any single-round mistake forward (if a great proposal lost once due to bias, it’s gone). Instead, we keep all proposals in play for all rounds and use aggregate scoring. This preserves information and is more stable than pairwise elimination, which can be as volatile as ELO rankings that “significantly reshuffled” with minor prompt changes ￼.
•	No single-score holistic grading: We avoid having the LLM just read everything and pick a winner outright in one go without structure. That could invite uncontrolled biases and lacks transparency. By asking for structured scores per proposal, we ensure each option is considered and can trace back the decision.
•	Avoiding subjective terms in prompts: The prompt should not use words like “best”, “favorite”, etc., without definition, as the model might latch onto superficial qualities. We focus on defined criteria to ground the evaluation.
•	Not relying on model self-awareness of bias: Simply instructing “Do not be biased” to the LLM is surprisingly ineffective or even counterproductive ￼. One experiment showed that telling the model “avoid position bias” actually increased its bias toward one option ￼. Our system addresses bias through algorithmic means (shuffling, normalization, calibration) rather than expecting the model to magically overcome its own learned biases.

By adhering to these principles, we keep the system’s behavior predictable and based on multiple independent judgments, rather than one-shot or feedback-loop mechanisms that could go awry.

Expected Output and Usage

When the evaluation is run on a set of proposals, the system will produce:
•	A ranked list of proposal IDs (or names) from best to worst according to the aggregated median scores. This answers the core question of which proposal is the best.
•	A report of scores per proposal, including median and possibly the individual round scores. For example: Proposal A: median 8.0 (scores 8, 8, 8); Proposal B: median 7.5 (scores 7, 8, 7.5); Proposal C: median 6.0 (scores 6, 5, 7), etc.
•	A confidence indicator if any proposal had high variance. If all top proposals were scored consistently across rounds (low stddev), we can be confident in the result. If not, the user might choose to do additional rounds or involve a human double-check.
•	(Optional) If enabled, a brief justification from the LLM for the top proposal (e.g. one round could be dedicated to: “Now explain why Proposal X is the best based on the criteria.”). This would not affect the ranking, just serve as explanation.

The system should integrate easily with the workflow of generating proposals. For instance, after AI agents produce several API designs, this evaluator is called. It could be packaged as an API itself: send proposals, get back the evaluation results.

Conclusion

Start simple, then iterate. This design provides a practical, debiased evaluation framework that corrects for the most problematic LLM judge biases with minimal overhead. By shuffling proposal order, normalizing lengths, and using median aggregation, we remove a large portion of position and verbosity biases ￼ ￼. By using a single model multiple times rather than multiple models concurrently, we avoid inter-model agreement traps (sycophancy) ￼ and keep implementation straightforward.

For our use-case of AI-generated API designs, this means we can reliably pick the best design without, for example, always favoring the longest or the one listed last. The approach is evidence-backed – drawing on recent research like LLM-as-Judge bias studies and CalibraEval – but distilled into a lean system that’s easy to maintain.

Going forward, one can enrich this spec if needed: e.g. incorporate different LLMs as independent “judges” and then ensemble their votes (to see if a mix of models yields even more robust outcomes), or train a specific fine-tuned evaluator on past architecture decisions. However, those add complexity and should be justified with clear performance gains. In most cases, the above approach will be sufficient and aligned with the principle that complex multi-agent evaluation is often unnecessary when a simpler calibrated process will do.

Ultimately, by focusing on concrete criteria and algorithmic bias mitigation, we obtain an evaluation system that is fair, transparent, and effective for choosing the best AI-proposed software architecture, instilling confidence that the selection is based on merit and not an artefact of the evaluation procedure.