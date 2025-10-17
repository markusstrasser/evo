"""
LLM provider calls for proposal generation using APIs (preferred).

API-first implementation with CLI fallbacks only when necessary.
"""

import os
import subprocess
from pathlib import Path

# Get project root
PROJECT_ROOT = Path.cwd()


def call_codex_api(description: str) -> str:
    """
    Call GPT-5 Codex via OpenAI API (preferred over CLI).

    Uses OpenAI SDK directly for better reliability and type safety.
    """
    try:
        from openai import OpenAI
        client = OpenAI()

        # Improved prompt with clear hierarchy
        system_prompt = """You are an architectural advisor for solo developers.

MUST (hard requirements):
- Focus on simplicity over cleverness
- Debuggability is critical (observable state, clear errors)
- Solutions must be REPL-friendly (easy to test interactively)
- Avoid hidden automation or complex orchestration

SHOULD (preferences):
- Prefer explicit over implicit
- Minimize dependencies
- Document tradeoffs clearly
"""

        user_prompt = f"""Generate an implementation proposal for this problem:

{description}

REQUIRED sections:
1. Core approach (2-3 sentences explaining the fundamental strategy)
2. Key components and their responsibilities
3. Data structures and storage choices
4. Pros and cons (be honest about tradeoffs)
5. Red flags to watch for during implementation

Focus on what a solo developer "who can barely keep track of things" needs to know.
"""

        response = client.responses.create(
            model="gpt-5-codex",
            input=[
                {"role": "developer", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
            reasoning={"effort": "high"},  # Architecture requires deep thought
            text={"verbosity": "medium"}
        )

        return response.output_text

    except ImportError:
        raise RuntimeError(
            "OpenAI SDK not available. Install with: pip install openai"
        ) from None
    except Exception as e:
        raise RuntimeError(f"Codex API call failed: {e}") from e


def call_codex_cli(description: str) -> str:
    """
    Call Codex via CLI (fallback only).

    Use only when API integration not available or for legacy scripts.
    """
    # Improved prompt with clear hierarchy
    prompt = f"""Generate an implementation proposal for this problem:

{description}

MUST (hard requirements):
- Focus on simplicity over cleverness
- Debuggability is critical (observable state, clear errors)
- Solutions must be REPL-friendly

SHOULD (preferences):
- Prefer explicit over implicit
- Minimize dependencies

REQUIRED sections:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons
5. Red flags to watch for

Focus on what a solo developer needs.
"""

    try:
        result = subprocess.run(
            f'echo {repr(prompt)} | codex exec -m gpt-5-codex -c model_reasoning_effort="high" --full-auto -',
            capture_output=True,
            text=True,
            check=True,
            shell=True,
            cwd=PROJECT_ROOT,
        )
        return result.stdout.strip()

    except subprocess.CalledProcessError as e:
        raise RuntimeError(f"Codex CLI call failed: {e.stderr}") from e
    except FileNotFoundError:
        raise RuntimeError("codex CLI not found - ensure it's in PATH") from None


def call_codex(description: str) -> str:
    """
    Call GPT-5 Codex - tries API first, falls back to CLI.

    Preferred approach: API (more reliable, type-safe)
    Fallback: CLI (only when API unavailable)
    """
    # Try API first if OpenAI SDK available
    try:
        return call_codex_api(description)
    except RuntimeError as e:
        if "not available" in str(e):
            # SDK not installed, fall back to CLI
            return call_codex_cli(description)
        else:
            # Other error, re-raise
            raise


def call_gemini(description: str) -> str:
    """
    Call Gemini to generate a proposal.

    Uses the gemini CLI wrapper (API integration TODO).
    """
    prompt = f"""Generate an implementation proposal for this problem:

{description}

MUST (hard requirements):
- Focus on simplicity over cleverness
- Debuggability is critical
- REPL-friendly solutions

REQUIRED sections:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons
5. Red flags to watch for

Focus on solo developer needs.
"""

    try:
        result = subprocess.run(
            ["gemini", "--model", "gemini-2.0-flash-exp", "-y", "-p", prompt],
            capture_output=True,
            text=True,
            check=True,
            cwd=PROJECT_ROOT,
        )
        return result.stdout.strip()

    except subprocess.CalledProcessError as e:
        raise RuntimeError(f"Gemini call failed: {e.stderr}") from e
    except FileNotFoundError:
        raise RuntimeError("gemini CLI not found - ensure it's in PATH") from None


def call_grok(description: str) -> str:
    """
    Call Grok to generate a proposal.

    Uses llmx CLI with xai provider.
    """
    prompt = f"""Generate an implementation proposal for this problem:

{description}

MUST (hard requirements):
- Focus on simplicity over cleverness
- Debuggability is critical
- REPL-friendly solutions

REQUIRED sections:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons
5. Red flags to watch for

Focus on solo developer needs.
"""

    try:
        result = subprocess.run(
            f'echo {repr(prompt)} | llmx --provider xai -m grok-4-latest --no-stream',
            capture_output=True,
            text=True,
            check=True,
            shell=True,
            cwd=PROJECT_ROOT,
        )
        return result.stdout.strip()

    except subprocess.CalledProcessError as e:
        raise RuntimeError(f"Grok call failed: {e.stderr}") from e
    except FileNotFoundError:
        raise RuntimeError("llmx CLI not found - ensure it's installed (uv tool install ~/Projects/llmx)") from None


def call_provider(provider_name: str, description: str) -> str:
    """
    Call a provider by name.

    Args:
        provider_name: One of 'gemini', 'codex', 'grok'
        description: Problem description

    Returns:
        Proposal text

    Raises:
        ValueError: If provider_name is unknown
        RuntimeError: If provider call fails
    """
    providers = {
        "gemini": call_gemini,
        "codex": call_codex,  # API-first with CLI fallback
        "grok": call_grok,
    }

    if provider_name not in providers:
        raise ValueError(f"Unknown provider: {provider_name}. Available: {', '.join(providers.keys())}")

    return providers[provider_name](description)
