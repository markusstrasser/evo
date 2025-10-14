"""Entry point for running review-flow MCP as a module."""
from .server import mcp

if __name__ == "__main__":
    mcp.run()
