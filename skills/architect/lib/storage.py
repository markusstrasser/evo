"""
Storage layer for architect workflow.

Uses flat files and JSONL append-only ledger for simplicity.

All paths are relative to Path.cwd() - the project where architect is being used.
This means files are written directly into the host project's directory structure.
"""

import json
from pathlib import Path
from typing import Any


def get_project_root() -> Path:
    """Get the project root (where architect is being used).

    When running in Claude Code, Path.cwd() is the project root.
    This makes all storage paths project-relative, so files appear
    exactly where the user expects them.
    """
    return Path.cwd()


# Storage paths (project-relative)
def get_review_runs_dir() -> Path:
    """Get review runs directory relative to project root."""
    return get_project_root() / ".architect" / "review-runs"


def get_reports_dir() -> Path:
    """Get research reports directory relative to project root."""
    return get_project_root() / ".architect" / "reports"


def get_ledger_path() -> Path:
    """Get ledger path relative to project root."""
    return get_project_root() / ".architect" / "review-ledger.jsonl"


def ensure_dirs():
    """Ensure storage directories exist."""
    get_review_runs_dir().mkdir(parents=True, exist_ok=True)
    get_reports_dir().mkdir(parents=True, exist_ok=True)
    get_ledger_path().parent.mkdir(parents=True, exist_ok=True)


def save_run(run_id: str, data: dict[str, Any]) -> Path:
    """Save run metadata to disk.

    Returns:
        Path to the saved run.json file
    """
    ensure_dirs()
    run_dir = get_review_runs_dir() / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    run_file = run_dir / "run.json"
    run_file.write_text(json.dumps(data, indent=2))
    return run_file


def load_run(run_id: str) -> dict[str, Any] | None:
    """Load run metadata from disk."""
    run_file = get_review_runs_dir() / run_id / "run.json"
    if not run_file.exists():
        return None
    return json.loads(run_file.read_text())


def save_proposal(run_id: str, proposal: dict[str, Any]) -> Path:
    """Save proposal to disk.

    Returns:
        Path to the saved proposal file
    """
    ensure_dirs()
    run_dir = get_review_runs_dir() / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    proposal_file = run_dir / f"proposal-{proposal['provider']}.json"
    proposal_file.write_text(json.dumps(proposal, indent=2))
    return proposal_file


def save_ranking(run_id: str, ranking: dict[str, Any]) -> Path:
    """Save ranking results to disk.

    Returns:
        Path to the saved ranking file
    """
    ensure_dirs()
    run_dir = get_review_runs_dir() / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    ranking_file = run_dir / "ranking.json"
    ranking_file.write_text(json.dumps(ranking, indent=2))
    return ranking_file


def save_spec(run_id: str, spec: dict[str, Any]) -> Path:
    """Save spec to disk.

    Returns:
        Path to the saved spec file
    """
    ensure_dirs()
    run_dir = get_review_runs_dir() / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    spec_file = run_dir / "spec.json"
    spec_file.write_text(json.dumps(spec, indent=2))
    return spec_file


def save_adr(run_id: str, adr_id: str, content: str) -> Path:
    """Save ADR (Architectural Decision Record) to disk.

    Args:
        run_id: Review run ID
        adr_id: ADR identifier
        content: Markdown content of the ADR

    Returns:
        Path to the saved ADR file (project-relative)
    """
    ensure_dirs()
    run_dir = get_review_runs_dir() / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    adr_path = run_dir / f"{adr_id}.md"
    adr_path.write_text(content)
    return adr_path


def append_to_ledger(event: dict[str, Any]) -> None:
    """Append event to JSONL ledger."""
    ensure_dirs()

    with open(get_ledger_path(), "a") as f:
        f.write(json.dumps(event) + "\n")


def read_ledger() -> list[dict[str, Any]]:
    """Read all events from ledger."""
    ledger_path = get_ledger_path()
    if not ledger_path.exists():
        return []

    events = []
    with open(ledger_path) as f:
        for line in f:
            line = line.strip()
            if line:
                events.append(json.loads(line))
    return events
