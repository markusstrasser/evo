#!/bin/bash
# Parallel research tool for querying multiple repos/paths
# Usage: scripts/parallel-research.sh questions.edn

set -euo pipefail

QUESTIONS_FILE="${1:-research/questions.edn}"
RESULTS_DIR="research/results"
CACHE_DIR="$HOME/.cache/evo-research"
MAX_CONCURRENT=4

mkdir -p "$RESULTS_DIR" "$CACHE_DIR"

# Parse EDN and extract repo/question pairs
parse_questions() {
  # Simple EDN parser for {repo {:path "..." :question "..."}}
  # Uses clojure if available, otherwise basic grep
  if command -v clojure &> /dev/null; then
    clojure -M -e "
      (require '[clojure.edn :as edn])
      (doseq [[repo {:keys [path question]}] (edn/read-string (slurp \"$QUESTIONS_FILE\"))]
        (println (str repo \"|\" path \"|\" question)))
    "
  else
    echo "Error: clojure not found. Install or manually parse $QUESTIONS_FILE" >&2
    exit 1
  fi
}

# Query a single repo/path
query_repo() {
  local repo=$1
  local path=$2
  local question=$3
  local cache_key=$(echo "$repo:$path:$question" | md5)
  local cache_file="$CACHE_DIR/$cache_key.md"
  local output_file="$RESULTS_DIR/${repo//./-}.md"

  echo "[$(date +%H:%M:%S)] Starting $repo..."

  # Check cache
  if [ -f "$cache_file" ]; then
    echo "[$(date +%H:%M:%S)] Cache hit for $repo"
    cp "$cache_file" "$output_file"
    return 0
  fi

  # Expand tilde in path
  local full_path="${path/#\~/$HOME}"

  # Run repomix + gemini
  if repomix "$full_path" --copy --output /dev/null 2>/dev/null; then
    if pbpaste | gemini --allowed-mcp-server-names context-prompt content-prompt -y -p "$question" > "$output_file" 2>/dev/null; then
      # Cache successful result
      cp "$output_file" "$cache_file"
      echo "[$(date +%H:%M:%S)] ✓ Completed $repo"
    else
      echo "[$(date +%H:%M:%S)] ✗ Gemini failed for $repo" >&2
      echo "Error: Gemini query failed" > "$output_file"
    fi
  else
    echo "[$(date +%H:%M:%S)] ✗ Repomix failed for $repo" >&2
    echo "Error: Could not process repository" > "$output_file"
  fi
}

# Main execution
main() {
  if [ ! -f "$QUESTIONS_FILE" ]; then
    echo "Error: Questions file not found: $QUESTIONS_FILE"
    echo "Usage: $0 [questions.edn]"
    exit 1
  fi

  echo "Starting parallel research..."
  echo "Questions file: $QUESTIONS_FILE"
  echo "Results dir: $RESULTS_DIR"
  echo "Max concurrent: $MAX_CONCURRENT"
  echo ""

  # Process questions in parallel
  while IFS='|' read -r repo path question; do
    # Wait if at max concurrent
    while [ $(jobs -r | wc -l) -ge $MAX_CONCURRENT ]; do
      sleep 0.5
    done

    # Launch background job
    query_repo "$repo" "$path" "$question" &
  done < <(parse_questions)

  # Wait for all jobs
  wait

  echo ""
  echo "All queries complete!"
  echo "Results in: $RESULTS_DIR"
  ls -lh "$RESULTS_DIR"
}

main "$@"
