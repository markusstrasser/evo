# Repository Pattern Analysis Agent Instructions

## Objective
Analyze repositories for the most ingenious engineering patterns and create comprehensive technical documentation.

## Process
1. **Deep Analysis**: Use deepwiki MCP to ask specific technical questions about innovative patterns, implementation details, and architectural decisions
2. **Multiple Passes**: Perform 2-3 analysis rounds to extract maximum insights - surface patterns, then dive deeper into implementation specifics
3. **Documentation**: Create `{reponame}.insights.md` in `docs/refs/` with detailed code examples and namespace references

## Output Format
For each repository, create insights documentation with:

### Header
```markdown
# {RepoName} - Ingenious Patterns Analysis
## Repository: {owner/repo}
**Category**: {primary-purpose}
**Language**: {languages}
**Paradigm**: {programming-paradigms}

## 📁 Key Namespaces to Study
- `namespace.core` - Purpose description
```

### Patterns Section
```markdown
## 🧠 The N Most Ingenious Patterns

### X. **Pattern Name** ⭐⭐⭐⭐⭐
**Location**: `src/path/to/code.ext`
**Key Functions**: `func1`, `func2`, `func3`

Description of what makes this pattern ingenious.

```code-example
(real-code-example-here)
```

**Innovation**: Specific technical innovation and why it matters.
```

## Quality Standards
- **Concrete Code Examples**: Always include real, runnable code snippets
- **Namespace Precision**: Specify exact source file locations and key functions
- **Innovation Analysis**: Explain what makes each pattern technically superior
- **Implementation Details**: Include actual function names, types, and algorithms used
- **Star Ratings**: 5 stars for paradigm-shifting innovations, 4 for novel approaches, 3 for clever solutions

## Master Index
Create `docs/refs/README.md` summarizing all patterns with cross-references and categorization by technical domain (reactive programming, distributed systems, security, etc.).

## Key Questions to Ask
- "What are the most ingenious and innovative patterns in {repo}? Focus on: {specific-technical-areas}"
- "What are the deepest implementation details and algorithmic innovations?"
- "What are advanced technical patterns and cutting-edge techniques implemented?"

Always prioritize technical depth over surface-level features.