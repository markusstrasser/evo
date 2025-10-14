"""
LLM provider calls for proposal generation.

Wraps existing CLI tools (gemini, codex, grok) for async use.
"""

import asyncio
import os
import subprocess
from pathlib import Path
from typing import Any

from fastmcp import Context


# Get project root
PROJECT_ROOT = Path(__file__).parent.parent.parent


async def call_gemini(description: str, ctx: Context = None) -> str:
    """
    Call Gemini to generate a proposal.

    Uses the gemini CLI wrapper at scripts/gemini
    """
    if ctx:
        await ctx.info("Calling Gemini for proposal...")

    prompt = f"""Generate an implementation proposal for this problem:

{description}

Provide:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons
5. Red flags to watch for

Focus on simplicity and debuggability for a solo developer.
"""

    try:
        proc = await asyncio.create_subprocess_exec(
            "gemini",
            "--model", "gemini-2.0-flash-exp",
            "-y",
            "-p", prompt,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=PROJECT_ROOT,
        )

        stdout, stderr = await proc.communicate()

        if proc.returncode != 0:
            error_msg = stderr.decode() if stderr else "Unknown error"
            raise RuntimeError(f"Gemini call failed: {error_msg}")

        result = stdout.decode().strip()

        if ctx:
            await ctx.info(f"Gemini response: {len(result)} chars")

        return result

    except Exception as e:
        if ctx:
            await ctx.error(f"Gemini error: {e}")
        raise


async def call_codex(description: str, ctx: Context = None) -> str:
    """
    Call OpenAI Codex (GPT-5) to generate a proposal.

    Uses the codex CLI wrapper at scripts/codex
    """
    if ctx:
        await ctx.info("Calling Codex for proposal...")

    prompt = f"""Generate an implementation proposal for this problem:

{description}

Provide:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons
5. Red flags to watch for

Focus on simplicity and debuggability for a solo developer.
"""

    try:
        # Use codex exec with --full-auto for non-interactive batch processing
        proc = await asyncio.create_subprocess_shell(
            f'echo {repr(prompt)} | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -',
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=PROJECT_ROOT,
        )

        stdout, stderr = await proc.communicate()

        if proc.returncode != 0:
            error_msg = stderr.decode() if stderr else "Unknown error"
            raise RuntimeError(f"Codex call failed: {error_msg}")

        result = stdout.decode().strip()

        if ctx:
            await ctx.info(f"Codex response: {len(result)} chars")

        return result

    except Exception as e:
        if ctx:
            await ctx.error(f"Codex error: {e}")
        raise


async def call_grok(description: str, ctx: Context = None) -> str:
    """
    Call Grok to generate a proposal.

    Uses the grok CLI wrapper at scripts/grok
    """
    if ctx:
        await ctx.info("Calling Grok for proposal...")

    prompt = f"""Generate an implementation proposal for this problem:

{description}

Provide:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons
5. Red flags to watch for

Focus on simplicity and debuggability for a solo developer.
"""

    try:
        grok_script = PROJECT_ROOT / "scripts" / "grok"

        proc = await asyncio.create_subprocess_shell(
            f'echo {repr(prompt)} | {grok_script} -m grok-4-latest',
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=PROJECT_ROOT,
            env={**os.environ, "PATH": os.environ.get("PATH", "")},
        )

        stdout, stderr = await proc.communicate()

        if proc.returncode != 0:
            error_msg = stderr.decode() if stderr else "Unknown error"
            raise RuntimeError(f"Grok call failed: {error_msg}")

        result = stdout.decode().strip()

        if ctx:
            await ctx.info(f"Grok response: {len(result)} chars")

        return result

    except Exception as e:
        if ctx:
            await ctx.error(f"Grok error: {e}")
        raise
