---
name: check-db
description: Diagnose and fix Couchbase connectivity issues. Use when the user reports db DEGRADED, Couchbase errors, or wants to verify the database is healthy.
---

Diagnose the Couchbase connection for the asset claims API.

1. Check Docker is running:
   `docker info > /dev/null 2>&1 && echo "Docker: running" || echo "Docker: NOT running"`
   If Docker is not running, open it with `open -a Docker` and wait up to 30s for it to start.

2. Check container status:
   `docker ps -a --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"`
   Look for `claims-couchbase`. If it is stopped or missing, report it.

3. If `claims-couchbase` is stopped, start it:
   `cd /Users/ramprasathsampathkumar/projects/asset-claims-system-api && docker compose up -d couchbase`
   Wait 15 seconds for it to become healthy.

4. Verify the bucket exists and has data:
   `curl -s -u Administrator:password http://localhost:8091/pools/default/buckets`
   Check that the `claims` bucket is present and `memUsed > 0`.

5. Verify the primary index exists:
   `curl -s -u Administrator:password http://localhost:8093/query/service -d "statement=SELECT COUNT(*) FROM \`claims\`"`
   If the query fails with "No index available", create the primary index:
   `curl -u Administrator:password http://localhost:8093/query/service -d "statement=CREATE PRIMARY INDEX ON \`claims\`"`

6. Hit the running API health endpoint:
   `curl -s http://localhost:8080/health`
   If `db` is still DEGRADED, the server needs a restart — offer to run /dev.

7. Report a clear summary: what was found, what was fixed, current status.
