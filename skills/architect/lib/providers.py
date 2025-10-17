"""
LLM provider calls for proposal generation.

Wraps existing CLI tools (gemini, codex, grok) for synchronous use.
"""

import subprocess
from pathlib import Path
from typing import Optional
import re


# Get project root
PROJECT_ROOT = Path.cwd()


# Default constraints if no file provided
DEFAULT_CONSTRAINTS = {
    "context": "Architectural decisions for a software project",
    "must": [
        "Focus on simplicity over cleverness",
        "Debuggability is critical (observable state, clear errors)",
        "Document tradeoffs clearly"
    ],
    "should": [
        "Prefer explicit over implicit",
        "Minimize dependencies"
    ]
}


def load_constraints(constraints_file: Optional[Path] = None) -> dict:
    """
    Load project constraints from markdown file.

    Args:
        constraints_file: Path to constraints markdown file
                         If None, looks for .architect/project-constraints.md

    Returns:
        Dict with keys: context, must, should

    Falls back to DEFAULT_CONSTRAINTS if file not found.
    """
    if constraints_file is None:
        constraints_file = PROJECT_ROOT / ".architect" / "project-constraints.md"

    if not constraints_file.exists():
        return DEFAULT_CONSTRAINTS

    content = constraints_file.read_text()

    # Parse markdown sections
    constraints = {
        "context": "",
        "must": [],
        "should": []
    }

    # Extract context (first paragraph after "## Context")
    context_match = re.search(r'## Context\s+(.+?)(?=\n##|\Z)', content, re.DOTALL)
    if context_match:
        constraints["context"] = context_match.group(1).strip()

    # Extract MUST requirements (bullet points after "## MUST Requirements" until next ##)
    must_match = re.search(r'## MUST Requirements[^\n]*\n(.*?)(?=\n##|\Z)', content, re.DOTALL)
    if must_match:
        must_section = must_match.group(1)
        constraints["must"] = [
            line.strip()[2:].strip() for line in must_section.split('\n')
            if line.strip().startswith('-')
        ]

    # Extract SHOULD preferences (bullet points after "## SHOULD Preferences" until next ##)
    should_match = re.search(r'## SHOULD Preferences[^\n]*\n(.*?)(?=\n##|\Z)', content, re.DOTALL)
    if should_match:
        should_section = should_match.group(1)
        constraints["should"] = [
            line.strip()[2:].strip() for line in should_section.split('\n')
            if line.strip().startswith('-')
        ]

    return constraints if constraints["must"] or constraints["should"] else DEFAULT_CONSTRAINTS


def format_constraints_prompt(constraints: dict) -> str:
    """Format constraints dict into XML prompt section."""
    must_items = '\n'.join(f'- {item}' for item in constraints['must'])
    should_items = '\n'.join(f'- {item}' for item in constraints['should'])

    return f"""<constraints>
MUST (hard requirements):
{must_items}

SHOULD (preferences):
{should_items}
</constraints>"""


def call_gemini(description: str, constraints: Optional[dict] = None, constraints_file: Optional[Path] = None) -> str:
    """
    Call Gemini to generate a proposal.

    Uses the gemini CLI wrapper.

    Args:
        description: Problem description
        constraints: Constraints dict (overrides file loading)
        constraints_file: Path to constraints file (default: .architect/project-constraints.md)
    """
    # Load constraints if not provided
    if constraints is None:
        constraints = load_constraints(constraints_file)

    # Build role based on context
    role = f"You are an architectural advisor.\n\nProject context: {constraints['context']}" if constraints['context'] else "You are an architectural advisor."

    # Structured prompt with clear hierarchy (universal best practice)
    prompt = f"""<role>
{role}
</role>

{format_constraints_prompt(constraints)}

<task>
Generate an implementation proposal for:

{description}

REQUIRED sections:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons (be honest about tradeoffs)
5. Red flags to watch for during implementation
</task>

<output_format>
Use markdown with clear headings.
Be concise - aim for 1-2 pages total.
Use bullet points over paragraphs where possible.
</output_format>
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


def call_codex(description: str, constraints: Optional[dict] = None, constraints_file: Optional[Path] = None) -> str:
    """
    Call OpenAI Codex (GPT-5) to generate a proposal.

    Uses the codex CLI wrapper with model_reasoning_effort="high".

    Args:
        description: Problem description
        constraints: Constraints dict (overrides file loading)
        constraints_file: Path to constraints file (default: .architect/project-constraints.md)
    """
    # Load constraints if not provided
    if constraints is None:
        constraints = load_constraints(constraints_file)

    # Build role based on context
    role = f"You are an architectural advisor.\n\nProject context: {constraints['context']}" if constraints['context'] else "You are an architectural advisor."

    # Structured prompt with clear hierarchy (universal best practice)
    # GPT-5 benefits from explicit MUST/SHOULD to avoid wasting reasoning tokens
    prompt = f"""<role>
{role}
</role>

{format_constraints_prompt(constraints)}

<task>
Generate an implementation proposal for:

{description}

REQUIRED sections:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons (be honest about tradeoffs)
5. Red flags to watch for during implementation
</task>

<output_format>
Use markdown with clear headings.
Be concise - aim for 1-2 pages total.
Use bullet points over paragraphs where possible.
</output_format>
"""

    try:
        # Use codex exec with --full-auto for non-interactive batch processing
        # model_reasoning_effort="high" for architectural decisions (GPT-5 specific)
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


def call_grok(description: str, constraints: Optional[dict] = None, constraints_file: Optional[Path] = None) -> str:
    """
    Call Grok to generate a proposal.

    Uses llmx CLI with xai provider and grok-4-latest model.

    Args:
        description: Problem description
        constraints: Constraints dict (overrides file loading)
        constraints_file: Path to constraints file (default: .architect/project-constraints.md)
    """
    # Load constraints if not provided
    if constraints is None:
        constraints = load_constraints(constraints_file)

    # Build role based on context
    role = f"You are an architectural advisor.\n\nProject context: {constraints['context']}" if constraints['context'] else "You are an architectural advisor."

    # Structured prompt with clear hierarchy (universal best practice)
    prompt = f"""<role>
{role}
</role>

{format_constraints_prompt(constraints)}

<task>
Generate an implementation proposal for:

{description}

REQUIRED sections:
1. Core approach (2-3 sentences)
2. Key components and their responsibilities
3. Data structures and storage
4. Pros and cons (be honest about tradeoffs)
5. Red flags to watch for during implementation
</task>

<output_format>
Use markdown with clear headings.
Be concise and direct - aim for 1-2 pages total.
Use bullet points over paragraphs where possible.
</output_format>
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


def call_provider(provider_name: str, description: str, constraints: Optional[dict] = None, constraints_file: Optional[Path] = None) -> str:
    """
    Call a provider by name.

    Args:
        provider_name: One of 'gemini', 'codex', 'grok'
        description: Problem description
        constraints: Constraints dict (overrides file loading)
        constraints_file: Path to constraints file (default: .architect/project-constraints.md)

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

    return providers[provider_name](description, constraints=constraints, constraints_file=constraints_file)
