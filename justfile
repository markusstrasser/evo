# Evo task runner

# Regenerate AI-powered source + tooling overviews
overview *args:
    ~/Projects/skills/hooks/generate-overview.sh --type source {{args}} --project-root {{justfile_directory()}}
    ~/Projects/skills/hooks/generate-overview.sh --type tooling {{args}} --project-root {{justfile_directory()}}
