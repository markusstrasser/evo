"""Provider management using LiteLLM"""

import os
import sys
import time
from typing import Optional
from litellm import completion, stream_chunk_builder
from rich.console import Console

from .logger import logger

console = Console()

# Provider configurations
# Updated 2025-10-24: All defaults set to most powerful models
PROVIDER_CONFIGS = {
    "google": {
        "model": "gemini/gemini-2.5-pro",  # Latest as of Oct 2025
        "env_var": "GEMINI_API_KEY or GOOGLE_API_KEY",
    },
    "openai": {
        "model": "gpt-5-pro",  # Latest as of Oct 2025 (requires temp=1)
        "env_var": "OPENAI_API_KEY",
    },
    "anthropic": {
        "model": "claude-sonnet-4-5-20250929",  # Latest as of Oct 2025 (Claude 4.5)
        "env_var": "ANTHROPIC_API_KEY",
    },
    "xai": {
        "model": "xai/grok-4-latest",  # Latest as of Oct 2025 (Grok 4)
        "env_var": "XAI_API_KEY or GROK_API_KEY",
    },
    "deepseek": {
        "model": "deepseek/deepseek-chat",
        "env_var": "DEEPSEEK_API_KEY",
    },
}


def infer_provider_from_model(model: str) -> Optional[str]:
    """Infer provider from model name"""
    model_lower = model.lower()

    # Check for explicit prefixes first
    if model.startswith("gemini/") or "gemini" in model_lower:
        return "google"
    if model.startswith("xai/") or "grok" in model_lower:
        return "xai"
    if model.startswith("anthropic/") or "claude" in model_lower:
        return "anthropic"
    if model.startswith("deepseek/") or "deepseek" in model_lower:
        return "deepseek"
    if model.startswith("gpt") or model.startswith("o1") or model.startswith("chatgpt"):
        return "openai"

    return None


def get_model_name(provider: str, model: Optional[str] = None) -> str:
    """Get model name for provider"""
    # Map our provider names to LiteLLM prefixes
    prefix_map = {
        "google": "gemini",  # LiteLLM uses "gemini/" not "google/"
        "xai": "xai",
        "anthropic": "anthropic",
        "deepseek": "deepseek",
        "openai": None,  # OpenAI doesn't use prefix
    }

    if model:
        # If model already has a prefix, use as-is
        if "/" in model:
            return model

        # Add prefix for providers that need it
        prefix = prefix_map.get(provider)
        if prefix:
            return f"{prefix}/{model}"

        return model

    config = PROVIDER_CONFIGS.get(provider)
    if not config:
        raise ValueError(f"Unknown provider: {provider}")

    return config["model"]


def check_api_key(provider: str) -> str:
    """Check if API key is available for provider and return it"""
    config = PROVIDER_CONFIGS.get(provider)
    if not config:
        raise ValueError(f"Unknown provider: {provider}")

    # Check common environment variables
    key_vars = config["env_var"].replace(" or ", ",").split(",")
    for var in key_vars:
        var = var.strip()
        key = os.getenv(var)
        if key:
            logger.debug(f"Found API key: {var} (length={len(key)}, first 10 chars={key[:10]})")
            return key

    # No key found
    logger.error(f"API key missing for {provider}", {"env_var": config["env_var"]})
    raise RuntimeError(
        f"API key not found for provider '{provider}'. "
        f"Set one of: {config['env_var']}"
    )


def chat(
    prompt: str,
    provider: str,
    model: Optional[str],
    temperature: float,
    stream: bool,
    debug: bool,
    json_output: bool,
) -> None:
    """Execute chat with single provider"""
    start_time = time.time()

    try:
        api_key = check_api_key(provider)
        model_name = get_model_name(provider, model)

        logger.debug(
            "Starting chat",
            {
                "provider": provider,
                "model": model_name,
                "temperature": temperature,
                "stream": stream,
                "prompt_length": len(prompt),
            },
        )

        messages = [{"role": "user", "content": prompt}]

        if stream:
            # Streaming mode
            logger.debug("Starting streaming generation")

            response = completion(
                model=model_name,
                messages=messages,
                temperature=temperature,
                stream=True,
                api_key=api_key,
            )

            total_chars = 0
            for chunk in response:
                if hasattr(chunk.choices[0].delta, "content"):
                    content = chunk.choices[0].delta.content
                    if content:
                        sys.stdout.write(content)
                        sys.stdout.flush()
                        total_chars += len(content)

            sys.stdout.write("\n")
            sys.stdout.flush()

            elapsed = time.time() - start_time
            logger.debug(
                "Streaming complete",
                {
                    "total_chars": total_chars,
                    "elapsed_sec": round(elapsed, 2),
                    "chars_per_sec": int(total_chars / elapsed) if elapsed > 0 else 0,
                },
            )
        else:
            # Non-streaming mode
            logger.debug("Starting non-streaming generation")
            logger.debug(f"Calling completion with api_key length={len(api_key)}, first 10={api_key[:10]}")

            response = completion(
                model=model_name,
                messages=messages,
                temperature=temperature,
                stream=False,
                api_key=api_key,
            )

            text = response.choices[0].message.content
            print(text)

            elapsed = time.time() - start_time
            logger.debug(
                "Generation complete",
                {"response_length": len(text), "elapsed_sec": round(elapsed, 2)},
            )

    except Exception as error:
        logger.error("Chat failed", {"provider": provider, "error": str(error)})
        raise


def compare(
    prompt: str,
    providers: list[str],
    temperature: float,
    debug: bool,
    json_output: bool,
) -> None:
    """Compare responses from multiple providers"""
    import concurrent.futures

    start_time = time.time()
    logger.info(f"Comparing {len(providers)} providers")

    def call_provider(provider: str) -> tuple[str, Optional[str], Optional[str]]:
        """Call a single provider and return (provider, text, error)"""
        provider_start = time.time()
        try:
            logger.debug(f"Calling {provider}")

            api_key = check_api_key(provider)
            model_name = get_model_name(provider)

            messages = [{"role": "user", "content": prompt}]

            response = completion(
                model=model_name,
                messages=messages,
                temperature=temperature,
                stream=False,
                api_key=api_key,
            )

            text = response.choices[0].message.content
            elapsed = time.time() - provider_start

            logger.debug(
                f"{provider} complete",
                {"elapsed_sec": round(elapsed, 2), "response_length": len(text)},
            )

            return (provider, text, None)

        except Exception as error:
            elapsed = time.time() - provider_start
            logger.warn(
                f"{provider} failed",
                {"elapsed_sec": round(elapsed, 2), "error": str(error)},
            )
            return (provider, None, str(error))

    # Call providers in parallel
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(providers)) as executor:
        futures = [executor.submit(call_provider, p) for p in providers]
        results = [f.result() for f in concurrent.futures.as_completed(futures)]

    # Sort by provider name for consistent output
    results.sort(key=lambda x: x[0])

    # Display results
    print()  # Blank line
    for provider, text, error in results:
        print("=" * 60)
        print(provider.upper())
        print("=" * 60)

        if error:
            print(f"❌ Error: {error}")
        else:
            print(text)
        print()

    elapsed = time.time() - start_time
    successful = sum(1 for _, text, error in results if text and not error)
    failed = len(results) - successful

    logger.info(
        "Comparison complete",
        {
            "total_elapsed_sec": round(elapsed, 2),
            "successful": successful,
            "failed": failed,
        },
    )


def list_providers() -> list[str]:
    """List available providers"""
    return list(PROVIDER_CONFIGS.keys())
