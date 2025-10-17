"""
LLM provider calls for proposal generation.

Wraps existing CLI tools (gemini, codex, grok) for synchronous use.
"""

import subprocess
from pathlib import Path


# Get project root
PROJECT_ROOT = Path.cwd()


def call_gemini(description: str) -> str:
    """
    Call Gemini to generate a proposal.

    Uses the gemini CLI wrapper.
    """
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


def call_codex(description: str) -> str:
    """
    Call OpenAI Codex (GPT-5) to generate a proposal.

    Uses the codex CLI wrapper.
    """
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
        raise RuntimeError(f"Codex call failed: {e.stderr}") from e
    except FileNotFoundError:
        raise RuntimeError("codex CLI not found - ensure it's in PATH") from None


def call_grok(description: str) -> str:
    """
    Call Grok to generate a proposal.

    Uses the grok CLI wrapper at scripts/grok
    """
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

        result = subprocess.run(
            f'echo {repr(prompt)} | {grok_script} -m grok-4-latest',
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
        raise RuntimeError("grok CLI not found - ensure scripts/grok exists") from None


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
        "codex": call_codex,
        "grok": call_grok,
    }

    if provider_name not in providers:
        raise ValueError(f"Unknown provider: {provider_name}. Available: {', '.join(providers.keys())}")

    return providers[provider_name](description)
