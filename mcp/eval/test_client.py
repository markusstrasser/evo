#!/usr/bin/env python3
"""
Test client for the eval MCP server.
Run this to test the server locally.
"""

import asyncio

from fastmcp import Client

from eval_server import mcp


async def test_compare_items():
    """Test the compare_items tool."""
    print("Testing compare_items...")

    async with Client(mcp) as client:
        result = await client.call_tool(
            "compare_items",
            {
                "left": "def add(a, b):\n    return a + b",
                "right": "add = lambda a, b: a + b",
                "evaluation_prompt": """
This is for teaching Python to beginners.

Project goals:
- Readability first
- Easy to understand for beginners
- Clear structure

Acceptable tradeoffs:
- More verbose for clarity
- Less "clever" code

Red flags:
- Lambda functions (confusing for beginners)
- Implicit behavior
- Cryptic syntax

Judge which is better for teaching.
""",
                "judges": ["gpt5-codex", "gemini25-pro"],
                "max_rounds": 2,
            },
        )

        print("\n=== RESULT ===")
        print(result.content[0].text)


async def test_templates():
    """Test the template resources."""
    print("\nTesting templates...")

    async with Client(mcp) as client:
        # List resources
        resources = await client.list_resources()
        print(f"\nAvailable templates: {len(resources)}")

        for resource in resources:
            print(f"  - {resource.uri}: {resource.name}")

        # Read a template
        template = await client.read_resource("eval://templates/code-comparison")
        print("\n=== CODE COMPARISON TEMPLATE ===")
        print(template[0].text)


async def main():
    """Run all tests."""
    await test_templates()

    print("\n" + "=" * 60)
    print("NOTE: test_compare_items requires API keys and will")
    print("      call real judges. Uncomment to test.")
    print("=" * 60)

    # Uncomment to test with real API calls:
    # await test_compare_items()


if __name__ == "__main__":
    asyncio.run(main())
