---
name: test
description: Run the asset claims test suite and print a clear pass/fail summary. Use when the user wants to run tests or check if anything is broken.
argument-hint: "[fully.qualified.TestClassName]"
---

Run the test suite for the asset claims API.

1. Run the tests:
   - If an argument is provided, run only that suite:
     `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home ./gradlew test --tests "$ARGUMENTS" --rerun-tasks`
   - Otherwise run all tests:
     `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home ./gradlew test --rerun-tasks`

2. Read all XML files under `build/test-results/test/` and print a summary table:

   | Suite | Tests | Failures | Errors |
   |---|---|---|---|
   | ClaimSubmitIntegrationTest | 8 | 0 | 0 |
   | ... | | | |
   | **Total** | **N** | **0** | **0** |

3. If any failures exist, read the `<failure>` element from the relevant XML and show:
   - Test name
   - Failure message
   - Relevant stack frame

4. Finish with a one-line verdict: "✅ All N tests passed" or "❌ X failures in Y suites"
