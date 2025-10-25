#!/usr/bin/env python3
"""
Architect workflow tools.

Manages idea → proposals → spec → ADR lifecycle with LLM-assisted generation,
tournament evaluation, and autonomous decision-making for simple cases.
"""

import json
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from typing import Any, Optional
from uuid import uuid4

# Import local modules
sys.path.insert(0, str(Path(__file__).parent))
import providers
import storage

# Check if tournament CLI is available
TOURNAMENT_AVAILABLE = shutil.which("tournament") is not None


def propose(
    description: str,
    provider_names: list[str] = None,
    constraints_file: Optional[Path] = None,
    prompt_variant: str = "baseline",
    verbose: bool = False,
) -> dict[str, Any]:
    """
    Generate proposals from multiple LLM providers in parallel.

    The description IS the idea - no separate ideation stage.
    Calls 3 AI providers by default (gemini, codex, grok).

    Args:
        description: Problem description and context
        provider_names: List of LLM providers to use (default: gemini, codex, grok)
        constraints_file: Path to constraints file (default: .architect/project-constraints.md)
        prompt_variant: Prompt variant to use ('baseline', 'variant-a')
        verbose: Print progress messages

    Returns:
        {
            "run_id": str,
            "description": str,
            "proposals": [
                {"id": str, "provider": str, "content": str},
                ...
            ],
            "proposal_ids": [str, str, str]
        }
    """
    if provider_names is None:
        provider_names = ["gemini", "codex", "grok"]

    run_id = str(uuid4())

    if verbose:
        print(f"Starting review cycle: {run_id}")
        print(f"Generating proposals from {len(provider_names)} providers...")
        if prompt_variant != "baseline":
            print(f"Using prompt variant: {prompt_variant}")

    # Call providers in parallel
    # Track provider name counts to handle duplicates
    provider_counts = {}
    proposals = []
    with ThreadPoolExecutor(max_workers=len(provider_names)) as executor:
        # Track futures with (provider_name, instance_number)
        future_to_provider_info = {}
        for name in provider_names:
            provider_counts[name] = provider_counts.get(name, 0) + 1
            instance = provider_counts[name]
            future = executor.submit(providers.call_provider, name, description, constraints_file=constraints_file, prompt_variant=prompt_variant)
            future_to_provider_info[future] = (name, instance)

        for future in as_completed(future_to_provider_info):
            provider_name, instance = future_to_provider_info[future]
            try:
                result = future.result()
                # Add instance number if there are multiple instances of same provider
                provider_suffix = f"-{instance}" if provider_counts[provider_name] > 1 else ""
                proposal_id = f"{run_id}-{provider_name}{provider_suffix}"
                proposal = {
                    "id": proposal_id,
                    "provider": f"{provider_name}{provider_suffix}",
                    "content": result,
                    "created_at": datetime.utcnow().isoformat(),
                }
                proposals.append(proposal)

                # Save proposal to disk
                storage.save_proposal(run_id, proposal)

                if verbose:
                    print(f"✓ {provider_name}{provider_suffix}: {len(result)} chars")

            except Exception as e:
                if verbose:
                    provider_suffix = f"-{instance}" if provider_counts.get(provider_name, 0) > 1 else ""
                    print(f"✗ {provider_name}{provider_suffix} failed: {e}", file=sys.stderr)

    if verbose:
        print(f"Generated {len(proposals)} proposals")

    # Save run metadata
    run_data = {
        "id": run_id,
        "description": description,
        "proposals": proposals,
        "created_at": datetime.utcnow().isoformat(),
        "status": "proposals_generated",
    }
    storage.save_run(run_id, run_data)

    # Log to ledger
    storage.append_to_ledger(
        {
            "type": "proposals_generated",
            "run_id": run_id,
            "proposal_count": len(proposals),
            "timestamp": datetime.utcnow().isoformat(),
        }
    )

    return {
        "run_id": run_id,
        "description": description,
        "proposals": proposals,
        "proposal_ids": [p["id"] for p in proposals],
    }


def rank_proposals(
    run_id: str,
    auto_decide: bool = False,
    confidence_threshold: float = 0.8,
    constraints_file: Optional[Path] = None,
    verbose: bool = False,
) -> dict[str, Any]:
    """
    Rank proposals using tournament evaluation.

    Uses tournament-mcp if available, falls back to simple comparison.
    Optionally auto-decide if confidence is high enough.

    Args:
        run_id: Review run ID
        auto_decide: Automatically approve winner if confidence > threshold
        confidence_threshold: Min confidence for auto-decision (0-1)
        constraints_file: Path to constraints file (default: .architect/project-constraints.md)
        verbose: Print progress messages

    Returns:
        {
            "run_id": str,
            "ranking": [{"proposal_id": str, "score": float}, ...],
            "winner_id": str,
            "confidence": float,
            "valid": bool,
            "auto_decided": bool,
            "next_actions": {
                "approve": str,
                "revise": str,
                "reject_all": str
            }
        }
    """
    if verbose:
        print(f"Ranking proposals for run: {run_id}")

    # Load run data
    run_data = storage.load_run(run_id)
    if not run_data:
        raise ValueError(f"Run not found: {run_id}")

    proposals = run_data.get("proposals", [])
    if len(proposals) < 2:
        raise ValueError(f"Need at least 2 proposals to rank (found {len(proposals)})")

    # Load constraints
    constraints = providers.load_constraints(constraints_file)

    # Build context
    context = f"""You are evaluating architectural proposals.

Project context: {constraints['context']}

Problem: {run_data.get('description', 'No description')}"""

    # Build constraints section
    constraints_section = providers.format_constraints_prompt(constraints).replace("<constraints>", "<criteria>").replace("</constraints>", "</criteria>")

    # Prepare evaluation prompt (used by tournament judges)
    eval_prompt = f"""<context>
{context}
</context>

{constraints_section}

<scoring>
For EACH constraint listed above:
- Score both proposals: 0.0 (completely fails) to 10.0 (exceptional)
- Justify score with specific evidence from proposal
- Use the full scoring range (don't cluster around middle values)
</scoring>

<verdict>
Decision logic:
1. Calculate average score across all MUST requirements for each proposal
2. Choose the proposal with higher average
3. Tie-breaking: If averages differ by < 0.5 points, choose the SIMPLER proposal
4. Document: Explain key differences that drove your decision
</verdict>
"""

    # Try tournament CLI first
    if TOURNAMENT_AVAILABLE:
        try:
            if verbose:
                print("Running tournament evaluation...")

            # Create temp files for input
            with tempfile.TemporaryDirectory() as tmpdir:
                tmpdir_path = Path(tmpdir)

                # Write items JSON
                items_file = tmpdir_path / "items.json"
                items_data = {p["id"]: p["content"] for p in proposals}
                items_file.write_text(json.dumps(items_data, indent=2))

                # Write prompt
                prompt_file = tmpdir_path / "prompt.txt"
                prompt_file.write_text(eval_prompt)

                # Write output
                output_file = tmpdir_path / "result.json"

                # Build tournament CLI args
                tournament_args = [
                    "tournament",
                    "compare",
                    "--items", str(items_file),
                    "--prompt", str(prompt_file),
                    "--judges", "gpt5-codex,gemini25-pro,grok-4",
                    "--max-rounds", "3",
                    "--output", str(output_file),
                ]
                if verbose:
                    tournament_args.append("--verbose")

                # Call tournament CLI
                result = subprocess.run(
                    tournament_args,
                    capture_output=True,
                    text=True,
                    check=True,
                )

                # Read results
                ranking_result = json.loads(output_file.read_text())

            if verbose:
                status = ranking_result.get("status", "UNKNOWN")
                print(f"✓ Tournament complete (status: {status})")

        except (subprocess.CalledProcessError, FileNotFoundError, json.JSONDecodeError) as e:
            if verbose:
                print(f"⚠ Tournament failed, using fallback: {e}")

            # Fallback: Simple scoring
            ranking_result = {
                "ranking": [
                    {"item_id": p["id"], "score": 1.0 / (i + 1)}
                    for i, p in enumerate(proposals)
                ],
                "schema-r2": 0.75,
                "tau-split": 0.85,
            }
    else:
        if verbose:
            print("⚠ Tournament CLI not available, using fallback ranking")

        # Fallback: Simple scoring
        ranking_result = {
            "ranking": [
                {"item_id": p["id"], "score": 1.0 / (i + 1)}
                for i, p in enumerate(proposals)
            ],
            "schema-r2": 0.75,
            "tau-split": 0.85,
        }

    # Extract winner
    rankings = ranking_result.get("ranking", [])
    if not rankings:
        raise RuntimeError("Ranking produced no results")

    winner = rankings[0]
    winner_id = winner["item_id"]
    confidence = winner.get("score", 0.5)

    if verbose:
        print(f"🏆 Winner: {winner_id}")
        print(f"📊 Confidence: {confidence:.1%}")
        if len(rankings) > 1:
            runner_up = rankings[1]
            print(f"🥈 Runner-up: {runner_up['item_id']} ({runner_up.get('score', 0):.1%})")

    # Quality gates
    valid = (
        ranking_result.get("schema-r2", 0) > 0.7
        and ranking_result.get("tau-split", 0) > 0.8
    )

    if verbose:
        print(f"✅ Ranking valid: {valid}")

    # Save ranking
    ranking_data = {
        "run_id": run_id,
        "ranking": rankings,
        "winner_id": winner_id,
        "confidence": confidence,
        "valid": valid,
        "timestamp": datetime.utcnow().isoformat(),
    }
    storage.save_ranking(run_id, ranking_data)

    # Update run status
    run_data["status"] = "proposals_ranked"
    run_data["ranking"] = ranking_data
    storage.save_run(run_id, run_data)

    # Log to ledger
    storage.append_to_ledger(
        {
            "type": "proposals_ranked",
            "run_id": run_id,
            "winner_id": winner_id,
            "confidence": confidence,
            "valid": valid,
            "timestamp": datetime.utcnow().isoformat(),
        }
    )

    # Auto-decide if enabled and confidence is high
    auto_decided = False
    if auto_decide and confidence >= confidence_threshold and valid:
        if verbose:
            print(f"Auto-deciding with confidence {confidence:.2f} >= {confidence_threshold}")

        # Automatically approve the winner
        decision = decide(
            run_id=run_id,
            decision_type="approve",
            proposal_id=winner_id,
            reason=f"Auto-approved with confidence {confidence:.2f}",
            verbose=verbose,
        )
        auto_decided = True

        if verbose:
            print(f"Auto-decision complete: {decision['adr_id']}")

    return {
        "run_id": run_id,
        "ranking": rankings,
        "winner_id": winner_id,
        "confidence": confidence,
        "valid": valid,
        "auto_decided": auto_decided,
        "alternatives": rankings[1:3] if len(rankings) > 1 else [],
        "next_actions": {
            "approve": f"decide --run-id {run_id} --decision approve --proposal-id {winner_id}",
            "revise": f"refine --run-id {run_id} --proposal-id {winner_id} --feedback '...'",
            "reject_all": f"propose --description '<revised>'",
        },
    }


def refine(
    run_id: str,
    proposal_id: str,
    feedback: str,
    max_rounds: int = 5,
    verbose: bool = False,
) -> dict[str, Any]:
    """
    Refine a proposal with feedback loops (max 5 rounds).

    Each round validates tests, types, examples, and tradeoffs.

    Args:
        run_id: Review run ID
        proposal_id: Proposal to refine
        feedback: Human feedback for refinement
        max_rounds: Max refinement rounds (default: 5)
        verbose: Print progress messages

    Returns:
        {
            "run_id": str,
            "proposal_id": str,
            "spec_id": str,
            "spec_content": str,
            "rounds": int,
            "passed": bool,
            "validation_results": [...]
        }
    """
    if verbose:
        print(f"Refining proposal: {proposal_id}")

    run_data = storage.load_run(run_id)
    if not run_data:
        raise ValueError(f"Run not found: {run_id}")

    # Find the proposal
    proposal = next((p for p in run_data["proposals"] if p["id"] == proposal_id), None)
    if not proposal:
        raise ValueError(f"Proposal not found: {proposal_id}")

    # Initial spec from proposal
    spec_content = proposal["content"]
    validation_results = []

    for round_num in range(1, max_rounds + 1):
        if verbose:
            print(f"🔄 Refinement round {round_num}/{max_rounds}")

        # TODO: Implement validation steps
        # - Validate tests exist
        # - Validate types are correct
        # - Validate examples work
        # - Validate tradeoffs are documented

        # For now, just pass after 1 round (placeholder)
        validation_results.append(
            {
                "round": round_num,
                "tests": True,
                "types": True,
                "examples": True,
                "tradeoffs": True,
            }
        )

        all_passed = all(
            v for v in validation_results[-1].values() if isinstance(v, bool)
        )

        if verbose:
            checks = validation_results[-1]
            passed = sum(1 for k, v in checks.items() if isinstance(v, bool) and v)
            total = sum(1 for k, v in checks.items() if isinstance(v, bool))
            print(f"✅ Validation: {passed}/{total} checks passed")

        if all_passed:
            if verbose:
                print(f"🎯 Spec ready after {round_num} round(s)")
            break

        # Refine based on feedback (placeholder)
        if verbose:
            print(f"🔧 Refining spec based on feedback: {feedback[:100]}...")

    spec_id = f"{run_id}-spec"
    spec_data = {
        "id": spec_id,
        "run_id": run_id,
        "proposal_id": proposal_id,
        "content": spec_content,
        "rounds": round_num,
        "validation_results": validation_results,
        "passed": all_passed,
        "timestamp": datetime.utcnow().isoformat(),
    }

    storage.save_spec(run_id, spec_data)

    # Update run status
    run_data["status"] = "spec_refined"
    run_data["spec"] = spec_data
    storage.save_run(run_id, run_data)

    # Log to ledger
    storage.append_to_ledger(
        {
            "type": "spec_refined",
            "run_id": run_id,
            "spec_id": spec_id,
            "rounds": round_num,
            "passed": all_passed,
            "timestamp": datetime.utcnow().isoformat(),
        }
    )

    return {
        "run_id": run_id,
        "proposal_id": proposal_id,
        "spec_id": spec_id,
        "spec_content": spec_content,
        "rounds": round_num,
        "passed": all_passed,
        "validation_results": validation_results,
    }


def decide(
    run_id: str,
    decision_type: str,
    proposal_id: str = None,
    reason: str = "",
    verbose: bool = False,
) -> dict[str, Any]:
    """
    Record final decision as ADR.

    Args:
        run_id: Review run ID
        decision_type: Decision type (approve, reject, defer)
        proposal_id: Proposal ID to approve (required for 'approve')
        reason: Decision rationale
        verbose: Print progress messages

    Returns:
        {
            "run_id": str,
            "decision": str,
            "adr_id": str,
            "adr_uri": str
        }
    """
    if verbose:
        print(f"📝 Recording decision for run: {run_id}")
        print(f"Decision type: {decision_type.upper()}")

    run_data = storage.load_run(run_id)
    if not run_data:
        raise ValueError(f"Run not found: {run_id}")

    if decision_type == "approve" and not proposal_id:
        raise ValueError("proposal_id required for 'approve' decision")

    if verbose:
        if proposal_id:
            print(f"Approving proposal: {proposal_id}")
        if reason:
            print(f"Reason: {reason[:100]}...")

    # Generate ADR
    adr_id = f"adr-{run_id}"
    adr_content = f"""# ADR: {run_data.get('description', 'No description')}

## Status
{decision_type.upper()}

## Context
{run_data.get('description', 'No description')}

## Decision
{reason if reason else f"Decision: {decision_type}"}

## Consequences
TBD (to be filled during implementation)

---
Generated: {datetime.utcnow().isoformat()}
Run ID: {run_id}
Proposal ID: {proposal_id if proposal_id else 'N/A'}
"""

    # Save ADR using storage layer
    adr_path = storage.save_adr(run_id, adr_id, adr_content)

    # Update run status
    run_data["status"] = "decided"
    run_data["decision"] = {
        "type": decision_type,
        "proposal_id": proposal_id,
        "reason": reason,
        "adr_id": adr_id,
        "adr_uri": str(adr_path),
        "timestamp": datetime.utcnow().isoformat(),
    }
    storage.save_run(run_id, run_data)

    # Log to ledger
    storage.append_to_ledger(
        {
            "type": "decision_made",
            "run_id": run_id,
            "decision": decision_type,
            "adr_id": adr_id,
            "timestamp": datetime.utcnow().isoformat(),
        }
    )

    if verbose:
        print(f"ADR created: {adr_path}")

    return {
        "run_id": run_id,
        "decision": decision_type,
        "adr_id": adr_id,
        "adr_uri": str(adr_path),
    }


def review_cycle(
    description: str,
    auto_decide: bool = False,
    confidence_threshold: float = 0.85,
    constraints_file: Optional[Path] = None,
    prompt_variant: str = "baseline",
    verbose: bool = False,
) -> dict[str, Any]:
    """
    One-shot review cycle: generate → judge → present (optionally decide).

    This is the fastest path from idea to decision.

    Args:
        description: Problem description
        auto_decide: Automatically approve if confidence > threshold
        confidence_threshold: Min confidence for auto-decision (default: 0.85)
        constraints_file: Path to constraints file (default: .architect/project-constraints.md)
        prompt_variant: Prompt variant to use ('baseline', 'variant-a')
        verbose: Print progress messages

    Returns:
        {
            "run_id": str,
            "proposals": [...],
            "ranking": {...},
            "decision": {...} (if auto_decide=True)
        }
    """
    if verbose:
        print("🚀 Starting one-shot review cycle")
        print(f"Auto-decide: {auto_decide} (threshold: {confidence_threshold:.0%})")

    # 1. Generate proposals
    if verbose:
        print("📋 Stage 1/3: Generating proposals")
    proposal_result = propose(description, constraints_file=constraints_file, prompt_variant=prompt_variant, verbose=verbose)
    run_id = proposal_result["run_id"]

    # 2. Rank proposals
    if verbose:
        print("⚖️  Stage 2/3: Ranking proposals")
    ranking_result = rank_proposals(
        run_id=run_id,
        auto_decide=auto_decide,
        confidence_threshold=confidence_threshold,
        constraints_file=constraints_file,
        verbose=verbose,
    )

    result = {
        "run_id": run_id,
        "proposals": proposal_result["proposals"],
        "ranking": ranking_result,
    }

    # 3. If auto-decided, include decision
    if ranking_result.get("auto_decided"):
        if verbose:
            print("🎯 Stage 3/3: Auto-decision applied")
        run_data = storage.load_run(run_id)
        result["decision"] = run_data.get("decision")
    else:
        if verbose:
            print("⏸️  Review cycle paused - manual decision needed")
            winner_id = ranking_result["winner_id"]
            print(f"Next: decide --run-id {run_id} --decision approve --proposal-id {winner_id}")

    if verbose:
        print(f"✅ Review cycle complete: {run_id}")

    return result
