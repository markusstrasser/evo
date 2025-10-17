"""
GPT-5 API Integration Examples

Demonstrates preferred API-first patterns using OpenAI SDK and PydanticAI.
"""

from openai import OpenAI
from pydantic import BaseModel, Field
from pydantic_ai import Agent


# Example 1: Basic API call with high reasoning
def simple_api_call():
    """Direct API call for architecture question."""
    client = OpenAI()

    response = client.responses.create(
        model="gpt-5-codex",
        input=[
            {
                "role": "developer",
                "content": "You are an architectural advisor focused on simplicity."
            },
            {
                "role": "user",
                "content": "How should we implement event sourcing for a solo developer?"
            }
        ],
        reasoning={"effort": "high"},  # Critical for architecture
        text={"verbosity": "medium"}
    )

    return response.output_text


# Example 2: Structured output with PydanticAI
class ArchitecturalProposal(BaseModel):
    """Structured proposal schema."""
    approach: str = Field(description="Core approach in 2-3 sentences")
    components: list[str] = Field(description="Key components")
    pros: list[str]
    cons: list[str]
    red_flags: list[str]


async def structured_proposal():
    """Generate proposal with validated structure."""
    agent = Agent(
        "openai:gpt-5-codex",
        output_type=ArchitecturalProposal,
        system_prompt="""Generate architectural proposals.

        Focus on:
        - Simplicity for solo developers
        - Debuggability (observable state, clear errors)
        - Avoiding over-engineering
        """
    )

    result = await agent.run(
        "How should we implement undo/redo?",
        model_settings={"reasoning_effort": "high"}
    )

    print(f"Approach: {result.output.approach}")
    print(f"Red flags: {result.output.red_flags}")


# Example 3: Minimal reasoning for extraction
def extract_json_minimal():
    """Fast extraction with minimal reasoning."""
    client = OpenAI()

    log = "User login: timestamp=2025-10-17T10:00:00Z user_id=12345 status=success"

    response = client.responses.create(
        model="gpt-5-mini",  # Cheaper for simple tasks
        input=[
            {"role": "user", "content": f"Extract JSON from: {log}"}
        ],
        reasoning={"effort": "minimal"},  # Speed-critical
        text={"verbosity": "low"}
    )

    return response.output_text


# Example 4: Verbosity control (Cursor pattern)
def cursor_verbosity_pattern():
    """Low verbosity globally, high for code."""
    client = OpenAI()

    response = client.responses.create(
        model="gpt-5-codex",
        input=[
            {
                "role": "developer",
                "content": """
                Use high verbosity for writing code and code tools.
                Write code for clarity first. Prefer readable solutions.
                """
            },
            {
                "role": "user",
                "content": "Implement a binary search function"
            }
        ],
        reasoning={"effort": "medium"},
        text={"verbosity": "low"}  # Global low, overridden in prompt
    )

    return response.output_text


# Example 5: Agentic persistence
def agentic_prompt():
    """Persistent agent that doesn't stop early."""
    client = OpenAI()

    system_prompt = """
    <persistence>
    - You are an agent - keep going until completely resolved
    - Only terminate when problem is solved
    - Never stop at uncertainty - research and continue
    - Don't ask for confirmation - act and adjust later
    </persistence>

    <tool_preambles>
    - Rephrase user's goal clearly
    - Outline plan before acting
    - Narrate each step as you execute
    - Finish with summary
    </tool_preambles>
    """

    response = client.responses.create(
        model="gpt-5-codex",
        input=[
            {"role": "developer", "content": system_prompt},
            {"role": "user", "content": "Fix all type errors in the codebase"}
        ],
        reasoning={"effort": "high"},  # Thorough exploration
        text={"verbosity": "low"}
    )

    return response.output_text


if __name__ == "__main__":
    import asyncio

    # Run examples
    print("Example 1: Simple API call")
    print(simple_api_call())

    print("\nExample 2: Structured output")
    asyncio.run(structured_proposal())

    print("\nExample 3: Minimal reasoning extraction")
    print(extract_json_minimal())

    print("\nExample 4: Verbosity control")
    print(cursor_verbosity_pattern())

    print("\nExample 5: Agentic persistence")
    print(agentic_prompt())
