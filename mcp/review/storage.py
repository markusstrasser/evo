"""
Storage layer for review workflow.

Uses flat files and JSONL append-only ledger for simplicity.
"""

import json
from pathlib import Path
from typing import Any


# Storage paths
REVIEW_RUNS_DIR = Path("research/review-runs")
LEDGER_PATH = Path("research/review-ledger.jsonl")


def ensure_dirs():
    """Ensure storage directories exist."""
    REVIEW_RUNS_DIR.mkdir(parents=True, exist_ok=True)
    LEDGER_PATH.parent.mkdir(parents=True, exist_ok=True)


def save_run(run_id: str, data: dict[str, Any]) -> None:
    """Save run metadata to disk."""
    ensure_dirs()
    run_dir = REVIEW_RUNS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    run_file = run_dir / "run.json"
    run_file.write_text(json.dumps(data, indent=2))


def load_run(run_id: str) -> dict[str, Any] | None:
    """Load run metadata from disk."""
    run_file = REVIEW_RUNS_DIR / run_id / "run.json"
    if not run_file.exists():
        return None
    return json.loads(run_file.read_text())


def save_proposal(run_id: str, proposal: dict[str, Any]) -> None:
    """Save proposal to disk."""
    ensure_dirs()
    run_dir = REVIEW_RUNS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    proposal_file = run_dir / f"proposal-{proposal['provider']}.json"
    proposal_file.write_text(json.dumps(proposal, indent=2))


def save_ranking(run_id: str, ranking: dict[str, Any]) -> None:
    """Save ranking results to disk."""
    ensure_dirs()
    run_dir = REVIEW_RUNS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    ranking_file = run_dir / "ranking.json"
    ranking_file.write_text(json.dumps(ranking, indent=2))


def save_spec(run_id: str, spec: dict[str, Any]) -> None:
    """Save spec to disk."""
    ensure_dirs()
    run_dir = REVIEW_RUNS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    spec_file = run_dir / "spec.json"
    spec_file.write_text(json.dumps(spec, indent=2))


def append_to_ledger(event: dict[str, Any]) -> None:
    """Append event to JSONL ledger."""
    ensure_dirs()

    with open(LEDGER_PATH, "a") as f:
        f.write(json.dumps(event) + "\n")


def read_ledger() -> list[dict[str, Any]]:
    """Read all events from ledger."""
    if not LEDGER_PATH.exists():
        return []

    events = []
    with open(LEDGER_PATH) as f:
        for line in f:
            line = line.strip()
            if line:
                events.append(json.loads(line))
    return events
