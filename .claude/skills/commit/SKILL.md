---
name: commit
description: Stage changed files and create a well-structured conventional commit. Use when the user asks to commit changes.
---

Create a git commit for the asset claims API.

1. Run `git status` and `git diff --stat` to see what has changed.

2. Read any changed source files to understand the nature of the changes.

3. Stage only appropriate files — never `.env`, secrets, or binary build artifacts:
   - Stage modified source: `src/main/kotlin/**`
   - Stage modified tests: `src/test/kotlin/**`
   - Stage resource changes: `src/main/resources/**`
   - Stage build config if changed: `build.gradle.kts`, `settings.gradle.kts`
   - Stage new skill files: `.claude/skills/**`

4. Check recent commit style: `git log --oneline -5`

5. Write a conventional commit message following this project's style:
   - Type prefix: `feat` / `fix` / `refactor` / `test` / `docs` / `chore`
   - Subject line: concise, present tense, under 72 chars
   - Body: bullet points explaining the what and why if non-trivial
   - Always end with: `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`

6. Commit using a HEREDOC to preserve formatting, then run `git log --oneline -3` to confirm.
