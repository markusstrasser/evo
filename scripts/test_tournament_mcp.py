#!/usr/bin/env python3
"""Quick test of tournament MCP to identify hanging issues."""

import asyncio
from pathlib import Path
import sys

# Add tournament-mcp to path
sys.path.insert(0, str(Path.home() / "Projects/tournament-mcp"))

from tournament.evaluator import evaluate
from tournament.models import Item


async def test_simple_comparison():
    """Test with obviously different items."""
    print("Testing tournament MCP with simple comparison...")

    items = [
        Item(id="good", text="Clean, well-tested code with proper error handling"),
        Item(id="bad", text="Spaghetti code with no tests and random global mutations"),
    ]

    evaluation_prompt = """
Project goals:
- Code quality
- Maintainability
- Testing

Judge which is better.
"""

    try:
        print("Starting evaluation (3 rounds max)...")
        result = await asyncio.wait_for(
            evaluate(items, evaluation_prompt, max_rounds=1),  # Just 1 round for speed
            timeout=60.0  # 60 second timeout
        )

        print(f"\n✅ Success!")
        print(f"Status: {result.status}")
        print(f"Ranking: {result.ranking}")
        print(f"R²: {result.schema_r2}")
        return True

    except asyncio.TimeoutError:
        print("\n❌ TIMEOUT after 60 seconds - MCP is stuck!")
        return False
    except Exception as e:
        print(f"\n❌ Error: {e}")
        import traceback
        traceback.print_exc()
        return False


async def test_identical_items():
    """Test with identical items (should return INVALID)."""
    print("\nTesting with identical items...")

    items = [
        Item(id="a", text="Hello world"),
        Item(id="b", text="Hello world"),
    ]

    evaluation_prompt = "Judge which is better."

    try:
        print("Starting evaluation...")
        result = await asyncio.wait_for(
            evaluate(items, evaluation_prompt, max_rounds=1),
            timeout=30.0
        )

        print(f"\n✅ Success!")
        print(f"Status: {result.status}")
        print(f"Ranking: {result.ranking}")
        return True

    except asyncio.TimeoutError:
        print("\n❌ TIMEOUT after 30 seconds - MCP is stuck!")
        return False
    except Exception as e:
        print(f"\n❌ Error: {e}")
        return False


if __name__ == "__main__":
    print("=" * 60)
    print("TOURNAMENT MCP DRY RUN TEST")
    print("=" * 60)

    # Test 1: Simple comparison
    result1 = asyncio.run(test_simple_comparison())

    # Test 2: Identical items
    result2 = asyncio.run(test_identical_items())

    print("\n" + "=" * 60)
    print("RESULTS:")
    print(f"  Simple comparison: {'✅ PASS' if result1 else '❌ FAIL'}")
    print(f"  Identical items:   {'✅ PASS' if result2 else '❌ FAIL'}")
    print("=" * 60)
