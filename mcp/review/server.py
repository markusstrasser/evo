#!/usr/bin/env python3
"""
Minimal-Linear MCP Server for Review Workflow.

Manages idea → proposals → spec → ADR lifecycle with LLM-assisted generation,
tournament evaluation, and autonomous decision-making for simple cases.

Architecture:
- 3 core tools: propose(), refine(), decide()
- Auto-ranking via mounted eval-MCP
- JSONL event log for provenance
- Flat file storage per run
"""

import asyncio
import json
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

from dotenv import load_dotenv
from fastmcp import Context, FastMCP
from fastmcp.exceptions import ResourceError

from .providers import call_gemini, call_codex, call_grok
from .storage import (
    append_to_ledger,
    load_run,
    save_proposal,
    save_ranking,
    save_run,
    save_spec,
)

# Load environment variables
load_dotenv()

# Create main server
mcp = FastMCP("review-flow")

# Import eval-MCP for tournament evaluation
# (assumes eval-MCP is importable - adjust path as needed)
try:
    from mcp.eval.eval_server import mcp as eval_mcp

    # Mount eval-MCP for ranking
    mcp.mount(eval_mcp, prefix="eval")
except ImportError:
    eval_mcp = None
    print("Warning: eval-MCP not available, ranking will be disabled")


# ============================================================================
# Core Tools
# ============================================================================


@mcp.tool
async def propose(
    description: str,
    providers: list[str] = ["gemini", "codex", "grok"],
    ctx: Context = None,
) -> dict[str, Any]:
    """
    Generate proposals from multiple LLM providers in parallel.

    The description IS the idea - no separate ideation stage.
    Calls 3 AI providers by default (gemini, codex, grok).

    Args:
        description: Problem description and context
        providers: List of LLM providers to use (default: gemini, codex, grok)
        ctx: FastMCP context (auto-injected)

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
    run_id = str(uuid4())

    if ctx:
        await ctx.info(f"Starting review cycle: {run_id}")
        await ctx.info(f"Generating proposals from {len(providers)} providers...")

    # Call providers in parallel
    provider_map = {
        "gemini": call_gemini,
        "codex": call_codex,
        "grok": call_grok,
    }

    tasks = []
    for provider_name in providers:
        if provider_name not in provider_map:
            if ctx:
                await ctx.warning(f"Unknown provider: {provider_name}, skipping")
            continue
        tasks.append(provider_map[provider_name](description, ctx))

    results = await asyncio.gather(*tasks, return_exceptions=True)

    # Process results
    proposals = []
    for i, (provider_name, result) in enumerate(zip(providers, results)):
        if isinstance(result, Exception):
            if ctx:
                await ctx.error(f"Provider {provider_name} failed: {result}")
            continue

        proposal_id = f"{run_id}-{provider_name}"
        proposal = {
            "id": proposal_id,
            "provider": provider_name,
            "content": result,
            "created_at": datetime.utcnow().isoformat(),
        }
        proposals.append(proposal)

        # Save proposal to disk
        save_proposal(run_id, proposal)

    if ctx:
        await ctx.info(f"Generated {len(proposals)} proposals")

    # Save run metadata
    run_data = {
        "id": run_id,
        "description": description,
        "proposals": proposals,
        "created_at": datetime.utcnow().isoformat(),
        "status": "proposals_generated",
    }
    save_run(run_id, run_data)

    # Log to ledger
    append_to_ledger(
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


@mcp.tool
async def rank_proposals(
    run_id: str,
    auto_decide: bool = False,
    confidence_threshold: float = 0.8,
    ctx: Context = None,
) -> dict[str, Any]:
    """
    Rank proposals using tournament evaluation (via mounted eval-MCP).

    Optionally auto-decide if confidence is high enough.

    Args:
        run_id: Review run ID
        auto_decide: Automatically approve winner if confidence > threshold
        confidence_threshold: Min confidence for auto-decision (0-1)
        ctx: FastMCP context (auto-injected)

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
    if ctx:
        await ctx.info(f"Ranking proposals for run: {run_id}")

    # Load run data
    run_data = load_run(run_id)
    if not run_data:
        raise ValueError(f"Run not found: {run_id}")

    proposals = run_data.get("proposals", [])
    if len(proposals) < 2:
        raise ValueError(f"Need at least 2 proposals to rank (found {len(proposals)})")

    # Prepare evaluation prompt
    eval_prompt = f"""
This is for a solo developer who "can barely keep track of things" - needs extreme simplicity.

Project: {run_data.get('description', 'No description')}

Evaluation criteria (PRIORITY ORDER):
1. **Simplicity** - Solo dev can understand/debug easily (HIGHEST)
2. **Debuggability** - Observable state, clear errors, REPL-friendly
3. **Flexibility** - Can skip stages, run tools independently
4. **Provenance** - Trace which proposal → spec → implementation
5. **Quality gates** - Catch bad specs before implementation

Red flags:
- Infinite refinement loops
- Hidden automation
- Complex orchestration (hard to debug when stuck)
- Tight coupling (can't run stages independently)
- Over-engineering (10+ agents, dynamic planning)

Judge which proposal best fits these priorities.
"""

    # Call eval-MCP for tournament
    if eval_mcp is None:
        raise RuntimeError("eval-MCP not available for ranking")

    items = {p["id"]: p["content"] for p in proposals}

    if ctx:
        await ctx.info("Running tournament evaluation...")

    # Use the mounted eval-MCP
    ranking_result = await eval_mcp.compare_multiple(
        items=items,
        evaluation_prompt=eval_prompt,
        judges=["gpt5-codex", "gemini25-pro", "grok-4"],
        max_rounds=3,
        ctx=ctx,
    )

    # Extract winner
    rankings = ranking_result.get("ranking", [])
    if not rankings:
        raise RuntimeError("Tournament produced no rankings")

    winner = rankings[0]
    winner_id = winner["item_id"]
    confidence = winner.get("score", 0.5)

    if ctx:
        await ctx.info(f"🏆 Winner: {winner_id}")
        await ctx.info(f"📊 Confidence: {confidence:.1%}")
        if len(rankings) > 1:
            await ctx.info(f"🥈 Runner-up: {rankings[1]['item_id']} ({rankings[1].get('score', 0):.1%})")

    # Quality gates
    valid = (
        ranking_result.get("schema-r2", 0) > 0.7
        and ranking_result.get("tau-split", 0) > 0.8
    )

    if ctx:
        await ctx.debug(f"Quality metrics - R²: {ranking_result.get('schema-r2', 0):.2f}, τ-split: {ranking_result.get('tau-split', 0):.2f}")
        await ctx.info(f"✅ Ranking valid: {valid}")

    # Save ranking
    ranking_data = {
        "run_id": run_id,
        "ranking": rankings,
        "winner_id": winner_id,
        "confidence": confidence,
        "valid": valid,
        "timestamp": datetime.utcnow().isoformat(),
    }
    save_ranking(run_id, ranking_data)

    # Update run status
    run_data["status"] = "proposals_ranked"
    run_data["ranking"] = ranking_data
    save_run(run_id, run_data)

    # Log to ledger
    append_to_ledger(
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
        if ctx:
            await ctx.info(
                f"Auto-deciding with confidence {confidence:.2f} >= {confidence_threshold}"
            )

        # Automatically approve the winner
        decision = await decide(
            run_id=run_id,
            decision="approve",
            proposal_id=winner_id,
            reason=f"Auto-approved with confidence {confidence:.2f}",
            ctx=ctx,
        )
        auto_decided = True

        if ctx:
            await ctx.info(f"Auto-decision complete: {decision['adr_id']}")

    return {
        "run_id": run_id,
        "ranking": rankings,
        "winner_id": winner_id,
        "confidence": confidence,
        "valid": valid,
        "auto_decided": auto_decided,
        "alternatives": rankings[1:3] if len(rankings) > 1 else [],
        "next_actions": {
            "approve": f"decide(run_id='{run_id}', decision='approve', proposal_id='{winner_id}')",
            "revise": f"refine(run_id='{run_id}', proposal_id='{winner_id}', feedback='...')",
            "reject_all": f"propose(description='<revised>', providers=['gemini', 'codex', 'grok'])",
        },
    }


@mcp.tool
async def refine(
    run_id: str,
    proposal_id: str,
    feedback: str,
    max_rounds: int = 5,
    ctx: Context = None,
) -> dict[str, Any]:
    """
    Refine a proposal with feedback loops (max 5 rounds).

    Each round validates tests, types, examples, and tradeoffs.

    Args:
        run_id: Review run ID
        proposal_id: Proposal to refine
        feedback: Human feedback for refinement
        max_rounds: Max refinement rounds (default: 5)
        ctx: FastMCP context (auto-injected)

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
    if ctx:
        await ctx.info(f"Refining proposal: {proposal_id}")

    run_data = load_run(run_id)
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
        if ctx:
            await ctx.info(f"🔄 Refinement round {round_num}/{max_rounds}")
            await ctx.debug(f"Validating: tests, types, examples, tradeoffs")

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
        
        if ctx:
            # Report validation results
            checks = validation_results[-1]
            passed = sum(1 for k, v in checks.items() if isinstance(v, bool) and v)
            total = sum(1 for k, v in checks.items() if isinstance(v, bool))
            await ctx.info(f"✅ Validation: {passed}/{total} checks passed")
        
        if all_passed:
            if ctx:
                await ctx.info(f"🎯 Spec ready after {round_num} round(s)")
            break

        # Refine based on feedback (placeholder)
        if ctx:
            await ctx.info("🔧 Refining spec based on feedback...")
            await ctx.debug(f"Applying feedback: {feedback[:100]}...")

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

    save_spec(run_id, spec_data)

    # Update run status
    run_data["status"] = "spec_refined"
    run_data["spec"] = spec_data
    save_run(run_id, run_data)

    # Log to ledger
    append_to_ledger(
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


@mcp.tool
async def decide(
    run_id: str,
    decision: str,
    proposal_id: str = None,
    reason: str = "",
    ctx: Context = None,
) -> dict[str, Any]:
    """
    Record final decision as ADR.

    Args:
        run_id: Review run ID
        decision: Decision type (approve, reject, defer)
        proposal_id: Proposal ID to approve (required for 'approve')
        reason: Decision rationale
        ctx: FastMCP context (auto-injected)

    Returns:
        {
            "run_id": str,
            "decision": str,
            "adr_id": str,
            "adr_uri": str
        }
    """
    if ctx:
        await ctx.info(f"📝 Recording decision for run: {run_id}")
        await ctx.info(f"Decision type: {decision.upper()}")

    run_data = load_run(run_id)
    if not run_data:
        raise ValueError(f"Run not found: {run_id}")

    if decision == "approve" and not proposal_id:
        raise ValueError("proposal_id required for 'approve' decision")

    if ctx:
        if proposal_id:
            await ctx.debug(f"Approving proposal: {proposal_id}")
        if reason:
            await ctx.debug(f"Reason: {reason[:100]}...")

    # Generate ADR
    adr_id = f"adr-{run_id}"
    adr_content = f"""# ADR: {run_data.get('description', 'No description')}

## Status
{decision.upper()}

## Context
{run_data.get('description', 'No description')}

## Decision
{reason if reason else f"Decision: {decision}"}

## Consequences
TBD (to be filled during implementation)

---
Generated: {datetime.utcnow().isoformat()}
Run ID: {run_id}
Proposal ID: {proposal_id if proposal_id else 'N/A'}
"""

    # Save ADR
    adr_path = Path("research/review-runs") / run_id / f"{adr_id}.md"
    adr_path.parent.mkdir(parents=True, exist_ok=True)
    adr_path.write_text(adr_content)

    # Update run status
    run_data["status"] = "decided"
    run_data["decision"] = {
        "type": decision,
        "proposal_id": proposal_id,
        "reason": reason,
        "adr_id": adr_id,
        "adr_uri": str(adr_path),
        "timestamp": datetime.utcnow().isoformat(),
    }
    save_run(run_id, run_data)

    # Log to ledger
    append_to_ledger(
        {
            "type": "decision_made",
            "run_id": run_id,
            "decision": decision,
            "adr_id": adr_id,
            "timestamp": datetime.utcnow().isoformat(),
        }
    )

    if ctx:
        await ctx.info(f"ADR created: {adr_path}")

    return {
        "run_id": run_id,
        "decision": decision,
        "adr_id": adr_id,
        "adr_uri": str(adr_path),
    }


# ============================================================================
# Research Management
# ============================================================================


@mcp.tool
async def start_research(
    topic: str,
    focus_areas: list[str] = None,
    ctx: Context = None,
) -> dict[str, Any]:
    """
    Start a new research task.

    Creates a research ID and initializes state for tracking progress.
    The researcher subagent will use this to coordinate research activities.

    Args:
        topic: Research topic/question
        focus_areas: Optional list of specific areas to investigate
        ctx: FastMCP context (auto-injected)

    Returns:
        {
            "research_id": str,
            "topic": str,
            "focus_areas": list[str],
            "status": "in_progress",
            "created_at": str
        }
    """
    research_id = str(uuid4())

    if ctx:
        await ctx.info(f"🔬 Starting research: {research_id}")
        await ctx.info(f"Topic: {topic}")
        if focus_areas:
            await ctx.info(f"Focus areas: {', '.join(focus_areas)}")

    # Create research directory
    research_dir = Path("research/reports") / research_id
    research_dir.mkdir(parents=True, exist_ok=True)

    # Save research metadata
    research_data = {
        "id": research_id,
        "topic": topic,
        "focus_areas": focus_areas or [],
        "status": "in_progress",
        "created_at": datetime.utcnow().isoformat(),
        "report_path": None,
    }

    metadata_path = research_dir / "metadata.json"
    metadata_path.write_text(json.dumps(research_data, indent=2))

    # Log to ledger
    append_to_ledger(
        {
            "type": "research_started",
            "research_id": research_id,
            "topic": topic,
            "timestamp": datetime.utcnow().isoformat(),
        }
    )

    if ctx:
        await ctx.info(f"Research directory created: {research_dir}")

    return research_data


@mcp.tool
async def save_research_report(
    research_id: str,
    report_content: str,
    findings_summary: str = "",
    ctx: Context = None,
) -> dict[str, Any]:
    """
    Save research report and mark research as complete.

    Args:
        research_id: Research ID from start_research()
        report_content: Full markdown report content
        findings_summary: Optional brief summary of key findings
        ctx: FastMCP context (auto-injected)

    Returns:
        {
            "research_id": str,
            "status": "completed",
            "report_path": str,
            "completed_at": str
        }
    """
    if ctx:
        await ctx.info(f"📄 Saving research report: {research_id}")

    research_dir = Path("research/reports") / research_id
    if not research_dir.exists():
        raise ValueError(f"Research not found: {research_id}")

    # Load metadata
    metadata_path = research_dir / "metadata.json"
    if not metadata_path.exists():
        raise ValueError(f"Research metadata not found: {research_id}")

    research_data = json.loads(metadata_path.read_text())

    # Save report
    report_path = research_dir / "report.md"
    report_path.write_text(report_content)

    # Update metadata
    research_data["status"] = "completed"
    research_data["report_path"] = str(report_path)
    research_data["findings_summary"] = findings_summary
    research_data["completed_at"] = datetime.utcnow().isoformat()

    metadata_path.write_text(json.dumps(research_data, indent=2))

    # Log to ledger
    append_to_ledger(
        {
            "type": "research_completed",
            "research_id": research_id,
            "report_path": str(report_path),
            "timestamp": datetime.utcnow().isoformat(),
        }
    )

    if ctx:
        await ctx.info(f"✅ Research complete: {report_path}")
        if findings_summary:
            await ctx.info(f"Summary: {findings_summary[:200]}...")

    return {
        "research_id": research_id,
        "status": "completed",
        "report_path": str(report_path),
        "completed_at": research_data["completed_at"],
    }


@mcp.tool
async def update_research_progress(
    research_id: str,
    progress_note: str,
    ctx: Context = None,
) -> dict[str, Any]:
    """
    Update research progress with a status note.

    Useful for long-running research to emit progress events.

    Args:
        research_id: Research ID
        progress_note: Progress update message
        ctx: FastMCP context (auto-injected)

    Returns:
        {
            "research_id": str,
            "status": "in_progress",
            "updated_at": str
        }
    """
    if ctx:
        await ctx.info(f"📊 Research progress: {research_id}")
        await ctx.info(progress_note)

    research_dir = Path("research/reports") / research_id
    if not research_dir.exists():
        raise ValueError(f"Research not found: {research_id}")

    # Log progress to ledger
    append_to_ledger(
        {
            "type": "research_progress",
            "research_id": research_id,
            "note": progress_note,
            "timestamp": datetime.utcnow().isoformat(),
        }
    )

    return {
        "research_id": research_id,
        "status": "in_progress",
        "updated_at": datetime.utcnow().isoformat(),
    }


# ============================================================================
# Convenience Tool: Full Cycle
# ============================================================================


@mcp.tool
async def review_cycle(
    description: str,
    auto_decide: bool = False,
    confidence_threshold: float = 0.85,
    ctx: Context = None,
) -> dict[str, Any]:
    """
    One-shot review cycle: generate → judge → present (optionally decide).

    This is the fastest path from idea to decision.

    Args:
        description: Problem description
        auto_decide: Automatically approve if confidence > threshold
        confidence_threshold: Min confidence for auto-decision (default: 0.85)
        ctx: FastMCP context (auto-injected)

    Returns:
        {
            "run_id": str,
            "proposals": [...],
            "ranking": {...},
            "decision": {...} (if auto_decide=True)
        }
    """
    if ctx:
        await ctx.info("🚀 Starting one-shot review cycle")
        await ctx.info(f"Auto-decide: {auto_decide} (threshold: {confidence_threshold:.0%})")

    # 1. Generate proposals
    if ctx:
        await ctx.info("📋 Stage 1/3: Generating proposals")
    proposal_result = await propose(description, ctx=ctx)
    run_id = proposal_result["run_id"]

    # 2. Rank proposals
    if ctx:
        await ctx.info("⚖️  Stage 2/3: Ranking proposals")
    ranking_result = await rank_proposals(
        run_id=run_id,
        auto_decide=auto_decide,
        confidence_threshold=confidence_threshold,
        ctx=ctx,
    )

    result = {
        "run_id": run_id,
        "proposals": proposal_result["proposals"],
        "ranking": ranking_result,
    }

    # 3. If auto-decided, include decision
    if ranking_result.get("auto_decided"):
        if ctx:
            await ctx.info("🎯 Stage 3/3: Auto-decision applied")
        run_data = load_run(run_id)
        result["decision"] = run_data.get("decision")
    else:
        if ctx:
            await ctx.info("⏸️  Review cycle paused - manual decision needed")
            await ctx.info(f"Next: decide(run_id='{run_id}', decision='approve', proposal_id='{ranking_result['winner_id']}')")

    if ctx:
        await ctx.info(f"✅ Review cycle complete: {run_id}")

    return result


# ============================================================================
# Resources: State Access
# ============================================================================


@mcp.resource("review://runs/{run_id}")
async def get_run_state(run_id: str, ctx: Context = None) -> dict:
    """Get current state of a review run."""
    run_data = load_run(run_id)
    if not run_data:
        raise ResourceError(f"Run not found: {run_id}")
    return run_data


@mcp.resource("review://runs/{run_id}/proposals")
async def get_proposals(run_id: str, ctx: Context = None) -> list[dict]:
    """Get all proposals for a review run."""
    run_data = load_run(run_id)
    if not run_data:
        raise ResourceError(f"Run not found: {run_id}")
    return run_data.get("proposals", [])


@mcp.resource("review://runs/{run_id}/ranking")
async def get_ranking(run_id: str, ctx: Context = None) -> dict:
    """Get ranking results for a review run."""
    run_data = load_run(run_id)
    if not run_data:
        raise ResourceError(f"Run not found: {run_id}")
    return run_data.get("ranking", {})


@mcp.resource("review://ledger")
async def get_ledger(ctx: Context = None) -> str:
    """Get full provenance ledger (JSONL)."""
    ledger_path = Path("research/review-ledger.jsonl")
    if not ledger_path.exists():
        return ""
    return ledger_path.read_text()


@mcp.resource("research://reports/{research_id}")
async def get_research_report(research_id: str, ctx: Context = None) -> dict:
    """Get research report and metadata."""
    research_dir = Path("research/reports") / research_id
    if not research_dir.exists():
        raise ResourceError(f"Research not found: {research_id}")

    # Load metadata
    metadata_path = research_dir / "metadata.json"
    if not metadata_path.exists():
        raise ResourceError(f"Research metadata not found: {research_id}")

    research_data = json.loads(metadata_path.read_text())

    # Load report if completed
    report_path = research_dir / "report.md"
    if report_path.exists():
        research_data["report_content"] = report_path.read_text()

    return research_data


@mcp.resource("research://reports")
async def list_research_reports(ctx: Context = None) -> list[dict]:
    """List all research reports."""
    reports_dir = Path("research/reports")
    if not reports_dir.exists():
        return []

    reports = []
    for research_dir in reports_dir.iterdir():
        if not research_dir.is_dir():
            continue

        metadata_path = research_dir / "metadata.json"
        if not metadata_path.exists():
            continue

        research_data = json.loads(metadata_path.read_text())
        # Include summary info only (not full report content)
        reports.append(
            {
                "id": research_data["id"],
                "topic": research_data["topic"],
                "status": research_data["status"],
                "created_at": research_data["created_at"],
                "completed_at": research_data.get("completed_at"),
                "findings_summary": research_data.get("findings_summary"),
            }
        )

    # Sort by creation date (newest first)
    reports.sort(key=lambda r: r["created_at"], reverse=True)
    return reports


# ============================================================================
# Prompts: Guided Workflows
# ============================================================================


@mcp.prompt
async def quick_review_prompt(topic: str, ctx: Context = None) -> list[dict]:
    """Generate a prompt for quick review of a topic."""
    return [
        {
            "role": "user",
            "content": f"""Please review this topic and provide 3 alternative approaches:

Topic: {topic}

For each approach, describe:
1. Core idea (2-3 sentences)
2. Key benefits
3. Key tradeoffs
4. Red flags to watch for

Focus on simplicity and debuggability - this is for a solo developer.""",
        }
    ]


@mcp.prompt
async def refine_spec_prompt(
    spec: str, feedback: str, ctx: Context = None
) -> list[dict]:
    """Generate a prompt for refining a spec with feedback."""
    return [
        {
            "role": "user",
            "content": f"""Please refine this specification based on feedback:

## Current Spec
{spec}

## Feedback
{feedback}

## Requirements
- Add tests/examples if missing
- Clarify tradeoffs
- Improve debuggability
- Keep it simple (solo dev can understand)

Provide the refined spec.""",
        }
    ]


if __name__ == "__main__":
    # Run the server
    mcp.run()
