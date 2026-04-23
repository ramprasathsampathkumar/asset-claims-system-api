---
name: dev
description: Start or restart the asset claims local dev server. Use when the user wants to start, restart, or rebuild the local server.
argument-hint: "[rebuild]"
---

Start the local development server for the asset claims API.

1. Kill any process currently on port 8080:
   `lsof -ti:8080 | xargs kill -9 2>/dev/null; true`

2. If the argument is "rebuild", or if `build/libs/claims-api.jar` does not exist, build the fat jar:
   `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home ./gradlew shadowJar`
   Report build success or failure before continuing.

3. Source .env and start the server in the background:
   ```
   JAVA_BIN=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home/bin/java
   cd /Users/ramprasathsampathkumar/projects/asset-claims-system-api
   set -a && source .env && set +a
   nohup $JAVA_BIN -jar build/libs/claims-api.jar > /tmp/claims-api.log 2>&1 &
   echo "PID: $!"
   ```

4. Wait 5 seconds then hit the health endpoint:
   `curl -s http://localhost:8080/health`

5. Report:
   - Server PID
   - Status of each service: db, storage (UP / DEGRADED)
   - Useful links:
     - API:    http://localhost:8080
     - Docs:   http://localhost:8080/docs
     - MinIO:  http://localhost:9001

If the health check fails, tail /tmp/claims-api.log and report the error.
