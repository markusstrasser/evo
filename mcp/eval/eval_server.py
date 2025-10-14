#!/usr/bin/env python3
"""
MCP Server for Tournament-Based Evaluation.

Provides flexible AI-judge comparison using Swiss-Lite tournament with debiasing.
"""

import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
from fastmcp import Context, FastMCP

# Load environment variables
load_dotenv()

# Get project root (two levels up from this file)
PROJECT_ROOT = Path(__file__).parent.parent.parent
EVAL_SCRIPT = PROJECT_ROOT / "scripts" / "run-eval.clj"

mcp = FastMCP("evo-eval")


async def _call_clojure_eval(
    items: list[dict[str, str]],
    evaluation_prompt: str,
    judges: list[str],
    max_rounds: int,
    ctx: Context | None = None,
) -> dict[str, Any]:
    """
    Call the Clojure evaluation code via subprocess.

    Args:
        items: List of items to evaluate, each with :id and :text
        evaluation_prompt: Full context with goals/tradeoffs/red-flags
        judges: List of judge providers (e.g., ["gpt5-codex", "gemini25-pro"])
        max_rounds: Maximum tournament rounds
        ctx: Optional FastMCP context for logging

    Returns:
        Tournament results dict with rankings, quality metrics, etc.
    """
    # Create temp files for input/output
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".edn", delete=False
    ) as items_file, tempfile.NamedTemporaryFile(
        mode="w", suffix=".txt", delete=False
    ) as prompt_file, tempfile.NamedTemporaryFile(
        mode="r", suffix=".edn", delete=False
    ) as result_file:

        items_path = items_file.name
        prompt_path = prompt_file.name
        result_path = result_file.name

        try:
            # Write items as EDN
            items_edn = "[\n"
            for item in items:
                items_edn += f'  {{:id "{item["id"]}" :text "{item["text"]}"}}\n'
            items_edn += "]\n"
            items_file.write(items_edn)
            items_file.flush()

            # Write evaluation prompt
            prompt_file.write(evaluation_prompt)
            prompt_file.flush()

            # Build judge provider list
            judges_edn = "[" + " ".join(f":{j}" for j in judges) + "]"

            if ctx:
                await ctx.info(
                    f"Running tournament: {len(items)} items, {len(judges)} judges, {max_rounds} rounds"
                )

            # Call Clojure evaluation script
            cmd = [
                "clojure",
                "-M",
                "-m",
                "dev.eval.cli",
                "--items",
                items_path,
                "--prompt",
                prompt_path,
                "--judges",
                judges_edn,
                "--max-rounds",
                str(max_rounds),
                "--output",
                result_path,
            ]

            if ctx:
                await ctx.info(f"Command: {' '.join(cmd)}")

            result = subprocess.run(
                cmd,
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
                timeout=300,  # 5 minute timeout
            )

            if result.returncode != 0:
                error_msg = f"Evaluation failed: {result.stderr}"
                if ctx:
                    await ctx.error(error_msg)
                raise RuntimeError(error_msg)

            # Read results
            with open(result_path) as f:
                result_edn = f.read()

            if ctx:
                await ctx.info("Tournament complete")

            # Parse EDN to JSON (simplified - assumes eval outputs JSON)
            # In reality, you'd want a proper EDN parser
            return json.loads(result_edn)

        finally:
            # Cleanup temp files
            for path in [items_path, prompt_path, result_path]:
                try:
                    os.unlink(path)
                except Exception:
                    pass


@mcp.tool
async def compare_items(
    left: str,
    right: str,
    evaluation_prompt: str,
    judges: list[str] = ["gpt5-codex", "gemini25-pro", "grok-4"],
    max_rounds: int = 3,
    ctx: Context = None,
) -> dict[str, Any]:
    """
    Compare two items using tournament-based AI judge evaluation.

    The evaluation_prompt should provide full context about:
    - Project goals and priorities
    - Acceptable tradeoffs
    - Red flags to watch for

    Example:
        ```
        This is for a local-first Anki clone with event sourcing.

        Project goals:
        - Correctness first (this is user study data)
        - Easy to debug (event log must be inspectable)
        - Simple to reason about (avoid clever tricks)

        Acceptable tradeoffs:
        - Slower performance for clearer code
        - More memory for immutability

        Red flags:
        - Mutation of shared state
        - Complex abstractions without clear benefit

        Judge which implementation better fits these priorities.
        ```

    Args:
        left: First item text
        right: Second item text
        evaluation_prompt: Full context with goals/tradeoffs/red-flags
        judges: AI judges to use (default: ["gpt5-codex", "gemini25-pro", "grok-4"])
        max_rounds: Max tournament rounds (default: 3)
        ctx: FastMCP context (injected automatically)

    Returns:
        Tournament results including:
        - ranking: Ordered list of items with scores
        - quality metrics (kendall-τ, R², brittleness, dispersion)
        - bias estimates
        - attack success rates
        - status: :VALID or :INVALID
    """
    if ctx:
        await ctx.info("Starting tournament evaluation...")

    items = [
        {"id": "left", "text": left},
        {"id": "right", "text": right},
    ]

    result = await _call_clojure_eval(items, evaluation_prompt, judges, max_rounds, ctx)

    if ctx:
        status = result.get("status", "UNKNOWN")
        await ctx.info(f"Evaluation complete: {status}")

    return result


@mcp.tool
async def compare_multiple(
    items: dict[str, str],
    evaluation_prompt: str,
    judges: list[str] = ["gpt5-codex", "gemini25-pro", "grok-4"],
    max_rounds: int = 5,
    ctx: Context = None,
) -> dict[str, Any]:
    """
    Compare multiple items using tournament-based evaluation.

    Args:
        items: Dict mapping item IDs to their text content
        evaluation_prompt: Full context with goals/tradeoffs/red-flags
        judges: AI judges to use
        max_rounds: Max tournament rounds (scales with item count)
        ctx: FastMCP context (injected automatically)

    Returns:
        Tournament results with full rankings
    """
    if ctx:
        await ctx.info(f"Starting tournament with {len(items)} items...")

    items_list = [{"id": k, "text": v} for k, v in items.items()]

    result = _call_clojure_eval(items_list, evaluation_prompt, judges, max_rounds, ctx)

    if ctx:
        status = result.get("status", "UNKNOWN")
        ranking = result.get("ranking", [])
        await ctx.info(
            f"Evaluation complete: {status}, winner: {ranking[0] if ranking else 'N/A'}"
        )

    return result


@mcp.resource("eval://templates/code-comparison")
def code_comparison_template() -> str:
    """
    Template for code comparison evaluation prompts.
    """
    return """
Project: {project_name}
Context: {project_context}

Project goals (in priority order):
{goals}

Acceptable tradeoffs:
{tradeoffs}

Red flags to watch for:
{red_flags}

Task: Compare these two implementations and judge which better fits the project priorities.
"""


@mcp.resource("eval://templates/writing-comparison")
def writing_comparison_template() -> str:
    """
    Template for writing/content comparison evaluation prompts.
    """
    return """
Content type: {content_type}
Audience: {audience}

Quality criteria (in priority order):
{criteria}

Acceptable tradeoffs:
{tradeoffs}

Red flags to avoid:
{red_flags}

Task: Compare these two pieces and judge which better meets the criteria.
"""


if __name__ == "__main__":
    # Run the server
    mcp.run()
