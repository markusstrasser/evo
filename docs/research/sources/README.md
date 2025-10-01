# Best-Of Repos Reference

Source repositories for research queries, located at `~/Projects/best/`.

## Quick Usage

**Small repos** (<10k LOC):
```bash
repomix ~/Projects/best/{repo} --copy --output /dev/null \
  --include "src/**,README.md" > /dev/null 2>&1 && \
  pbpaste | gemini -y -p "YOUR_QUESTION"
```

**Large repos** (>10k LOC - zoom to subdirs):
```bash
repomix ~/Projects/best/clojurescript/src/main/clojure/cljs \
  --include "compiler.clj,analyzer.cljc" --copy --output /dev/null && \
  pbpaste | gemini -y -p "YOUR_QUESTION"
```

## Repository List

See `repos.edn` for structured data with LOC counts, focus areas, and directory trees.

To regenerate repo stats:
```bash
research/sources/update-repos.sh
```

## Adding New Repos

```bash
# Clone to ~/Projects/best/
cd ~/Projects/best
git clone https://github.com/{org}/{repo}

# Update documentation
research/sources/update-repos.sh
```

## Repository Details

The `repos.edn` file contains for each repo:
- **:focus**: What the repo is about
- **:loc**: Lines of code (from tokei)
- **:langs**: Language breakdown
- **:tree**: Directory structure
- **:readme**: First paragraph from README

Use this info to decide which repos and subdirs to query.
