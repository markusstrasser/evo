#!/usr/bin/env python3
"""
llmx - Unified API wrapper using LiteLLM

Simple tool for calling LLM APIs from scripts and skills.
NOT a replacement for agent CLIs (gemini, codex).

Usage:
    llmx "your prompt"
    llmx --provider openai "your prompt"
    cat prompt.txt | llmx
    llmx --compare "question"
"""

import sys
import os
import json
from pathlib import Path
import click
from dotenv import load_dotenv
from rich.console import Console
from rich.markdown import Markdown

# Load .env files if they exist (for development/testing)
# Priority: current dir > parent > grandparent
# This allows project-specific .env files to override global ones
#
# NOTE: We preprocess the .env file to handle shell-style "export VAR=value" format
# since python-dotenv only handles "VAR=value" format
def load_env_with_export_support(env_path: Path) -> None:
    """Load .env file with support for 'export VAR=value' shell format"""
    if not env_path.exists():
        return

    # Read the file and process export statements
    env_vars = {}
    with open(env_path) as f:
        for line in f:
            line = line.strip()
            # Skip comments and empty lines
            if not line or line.startswith('#'):
                continue
            # Handle "export VAR=value" format
            if line.startswith('export '):
                line = line[7:]  # Remove "export " prefix
            # Parse VAR=value
            if '=' in line:
                key, value = line.split('=', 1)
                # Remove quotes if present
                value = value.strip('"').strip("'")
                env_vars[key.strip()] = value

    # Set environment variables
    for key, value in env_vars.items():
        os.environ[key] = value

for env_path in [
    Path.cwd().parent.parent / ".env",
    Path.cwd().parent / ".env",
    Path.cwd() / ".env"
]:
    load_env_with_export_support(env_path)

from . import __version__
from .providers import chat, compare as compare_providers, list_providers, infer_provider_from_model
from .logger import configure_logger, logger

console = Console()


@click.command()
@click.argument("prompt", nargs=-1, required=False)
@click.option(
    "-p",
    "--provider",
    default=None,
    help="Provider (openai, google, anthropic, xai, deepseek) - auto-inferred from model if not specified",
)
@click.option("-m", "--model", help="Model name (overrides provider default)")
@click.option(
    "-t", "--temperature", default=0.7, type=float, help="Temperature 0-1 (default: 0.7)"
)
@click.option("--stream/--no-stream", default=False, help="Enable/disable streaming (default: no-stream)")
@click.option(
    "--compare",
    is_flag=True,
    help="Compare multiple providers (use --providers to specify)",
)
@click.option(
    "--providers",
    help="Comma-separated list of providers for comparison (default: google,openai,xai)",
)
@click.option("--debug", is_flag=True, help="Enable debug logging to stderr")
@click.option("--json", "json_output", is_flag=True, help="Output in JSON format")
@click.option("--list-providers", "list_providers_flag", is_flag=True, help="List available providers and exit")
@click.version_option(version=__version__)
def main(
    prompt,
    provider,
    model,
    temperature,
    stream,
    compare,
    providers,
    debug,
    json_output,
    list_providers_flag,
):
    """llmx - Unified API wrapper for LLM providers

    Simple tool for calling LLM APIs from scripts and skills.
    NOT a replacement for agent CLIs (gemini, codex).

    Examples:
        llmx "What is 2+2?"
        llmx --provider openai "Explain Python"
        cat prompt.txt | llmx
        llmx --compare "Which is better: tabs or spaces?"
    """
    # Configure logger
    configure_logger(debug=debug, json_mode=json_output)

    # List providers
    if list_providers_flag:
        provider_list = list_providers()
        if json_output:
            click.echo(json.dumps({"providers": provider_list}, indent=2))
        else:
            click.echo("Available providers:")
            for p in provider_list:
                click.echo(f"  - {p}")
        return

    # Get prompt from args or stdin
    prompt_text = " ".join(prompt) if prompt else None

    if not prompt_text:
        # Read from stdin
        if not sys.stdin.isatty():
            logger.debug("Reading prompt from stdin")
            prompt_text = sys.stdin.read().strip()
            logger.debug(f"Read {len(prompt_text)} chars from stdin")
        else:
            logger.error("No prompt provided")
            click.echo(
                "Error: No prompt provided. Use: llmx 'your prompt' or pipe input.",
                err=True,
            )
            click.echo("Try: llmx --help", err=True)
            sys.exit(1)

    if not prompt_text:
        logger.error("Empty prompt")
        click.echo("Error: Empty prompt.", err=True)
        sys.exit(1)

    try:
        # Handle compare mode
        if compare:
            provider_list = (
                providers.split(",") if providers else ["google", "openai", "xai"]
            )
            provider_list = [p.strip() for p in provider_list]

            logger.info(f"Comparing {len(provider_list)} providers", {"providers": provider_list})
            compare_providers(prompt_text, provider_list, temperature, debug, json_output)
            return

        # Infer provider from model if no provider specified
        final_provider = provider
        if model and not provider:
            inferred = infer_provider_from_model(model)
            if inferred:
                final_provider = inferred
                logger.debug(f"Inferred provider '{final_provider}' from model '{model}'")
            else:
                final_provider = "google"  # fallback to default
                logger.debug(f"Could not infer provider from model '{model}', using default: google")
        elif not provider:
            final_provider = "google"  # default if no model or provider specified

        # Regular chat
        logger.info(
            "Starting chat",
            {"provider": final_provider, "model": model or "default", "stream": stream},
        )
        chat(prompt_text, final_provider, model, temperature, stream, debug, json_output)

    except KeyboardInterrupt:
        logger.info("Interrupted by user")
        sys.exit(130)
    except Exception as error:
        if json_output:
            error_data = {
                "error": error.__class__.__name__,
                "message": str(error),
                "code": getattr(error, "code", "UNKNOWN_ERROR"),
            }
            click.echo(json.dumps(error_data, indent=2), err=True)
        else:
            click.echo(f"Error: {error}", err=True)

        # Exit codes: 2 for API key issues, 1 for others
        exit_code = 2 if "api key" in str(error).lower() else 1
        sys.exit(exit_code)


if __name__ == "__main__":
    main()
