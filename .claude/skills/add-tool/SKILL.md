---
name: add-tool
description: Scaffold a new tool for the Claude chat assistant. Use when the user wants to expose a new backend endpoint as a chat tool.
argument-hint: "<tool-name> <http-method> <endpoint>"
---

Add a new tool to the chat API tool-use loop.

Arguments: `$0` = tool name (snake_case), `$1` = HTTP method (GET/POST), `$2` = endpoint path (e.g. /api/store-locator/search)

1. Read `src/main/kotlin/com/example/claims/client/AnthropicClient.kt` and
   `src/main/kotlin/com/example/claims/client/ClaimsToolExecutor.kt` to understand existing patterns.

2. In `AnthropicClient.kt`, add a new entry to the `tools` JsonArray:
   - `name`: the tool name from $0
   - `description`: a clear, Claude-readable description of what the tool does and when to use it
   - `input_schema`: properties and required fields matching the endpoint's request shape
   Follow the exact same `.add(tool(...))` pattern as existing tools.

3. In `ClaimsToolExecutor.kt`:
   - Add a `"$0" -> functionName(input)` branch to the `when` block in `execute()`
   - Implement a `private suspend fun functionName(input: JsonObject): String` that calls $1 $2
   - Follow the same WebClient call pattern as `inquireClaim`, `searchStores`, etc.

4. Run `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home ./gradlew compileKotlin`
   and fix any errors before reporting success.

5. Tell the user:
   - What was added to each file
   - A sample chat message to test the new tool (e.g. "Ask Claude to call $0")
