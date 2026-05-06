# AGENTS.md

Essential AI workflow rules for this repository.

## GitNexus requirements

- Before editing a function, method, or class, run upstream impact analysis for that symbol.
- If impact risk is `HIGH` or `CRITICAL`, warn before proceeding.
- Before committing, run change detection and confirm the affected scope matches the intended change.
- For renames, use GitNexus rename flow instead of find/replace.

## Fast workflow

- Explore unfamiliar behavior with `query`.
- Inspect one symbol deeply with `context`.
- Check blast radius with `impact`.
- Validate final scope with `detect_changes`.
- Re-index with `npx gitnexus analyze` if the index is stale.

## Minimal checklist

1. Impact checked for every edited symbol.
2. Direct dependents updated when required.
3. No ignored `HIGH`/`CRITICAL` warnings.
4. Final scope verified with change detection.

## Useful resources

- `gitnexus://repo/chaincheck/context`
- `gitnexus://repo/chaincheck/processes`
- `gitnexus://repo/chaincheck/process/{name}`
