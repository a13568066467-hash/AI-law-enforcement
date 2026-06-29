# Agent instructions

## Agent skills

### Issue tracker

GitHub Issues on `a13568066467-hash/text1` via `gh` CLI; external PRs are not a triage surface. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles mapped 1:1 to GitHub label names (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout — root `CONTEXT.md` + `docs/adr/` (created lazily by domain-modeling skills). See `docs/agents/domain.md`.
