#!/usr/bin/env bash
# Generate repos.edn with tokei stats and tree structures

set -e

BEST_DIR="$HOME/Projects/best"
OUTPUT_FILE="$(dirname "$0")/repos.edn"

echo "{:base-path \"~/Projects/best\"" > "$OUTPUT_FILE"
echo " :repos [" >> "$OUTPUT_FILE"

for dir in "$BEST_DIR"/*; do
  [ -d "$dir" ] || continue

  name=$(basename "$dir")

  # Get tokei stats (JSON)
  tokei_json=$(tokei "$dir" --output json 2>/dev/null || echo '{}')
  total_loc=$(echo "$tokei_json" | jq -r '.Total.code // 0')

  # Get top languages
  langs=$(echo "$tokei_json" | jq -r '
    to_entries
    | map(select(.key != "Total"))
    | sort_by(-.value.code)
    | .[0:3]
    | map("\(.key): \(.value.code)")
    | join(", ")
  ')

  # Get tree (2 levels, dirs only)
  tree_output=$(tree "$dir" -L 2 -d -I 'node_modules|target|.git|build' 2>/dev/null | tail -n +2 | head -20 || echo "  (tree unavailable)")

  # Get README first paragraph
  readme_first=$(find "$dir" -maxdepth 1 -iname "README.md" -exec head -20 {} \; 2>/dev/null | grep -v "^#" | grep -v "^$" | head -1 || echo "(no README)")

  cat << EOF >> "$OUTPUT_FILE"
  {:name "$name"
   :loc $total_loc
   :langs "$langs"
   :tree "$(echo "$tree_output" | sed 's/"/\\"/g' | tr '\n' '|')"
   :readme "$readme_first"}
EOF

done

echo " ]}" >> "$OUTPUT_FILE"

echo "✓ Generated $OUTPUT_FILE"
