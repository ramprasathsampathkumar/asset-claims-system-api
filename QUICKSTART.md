# Quick Start

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) running

---

## Every-day startup

```bash
docker compose up -d
```

Wait ~20 seconds, then check:

```bash
curl http://localhost:8080/health
# {"status":"UP","db":"UP",...}
```

- **API** → http://localhost:8080
- **Swagger UI** → http://localhost:8080/docs
- **Couchbase console** → http://localhost:8091 (Administrator / password)

---

## First-time setup (one-time only)

Run this **once** after a fresh clone or after wiping the `couchbase-data` Docker volume.

### 1. Start Couchbase only

```bash
docker compose up -d couchbase
```

Wait ~30 seconds for Couchbase to be ready, then run the init script:

### 2. Initialise the cluster

```bash
# Node & services
curl -sf -X POST http://localhost:8091/nodeInit \
  -d 'hostname=127.0.0.1&dataPath=%2Fopt%2Fcouchbase%2Fvar%2Flib%2Fcouchbase%2Fdata&indexPath=%2Fopt%2Fcouchbase%2Fvar%2Flib%2Fcouchbase%2Fdata'

curl -sf -X POST http://localhost:8091/node/controller/setupServices \
  -d 'services=kv,n1ql,index'

# Memory quotas
curl -sf -X POST http://localhost:8091/pools/default \
  -d 'memoryQuota=512&indexMemoryQuota=256'

# Admin credentials
curl -sf -X POST http://localhost:8091/settings/web \
  -d 'username=Administrator&password=password&port=8091'

# Create the claims bucket
curl -sf -u Administrator:password \
  -X POST http://localhost:8091/pools/default/buckets \
  -d 'name=claims&bucketType=couchbase&ramQuotaMB=256&replicaNumber=0'

# GSI storage mode (required before creating indexes)
curl -sf -u Administrator:password \
  http://localhost:8091/settings/indexes \
  -d 'storageMode=plasma'

# Primary index (wait a few seconds for the bucket to be ready first)
sleep 5
curl -sf -u Administrator:password \
  http://localhost:8093/query/service \
  -d 'statement=CREATE PRIMARY INDEX ON `claims`'
```

### 3. Start the API

```bash
docker compose up -d api
```

---

## Stopping

```bash
docker compose down          # stop containers, keep data volume
docker compose down -v       # stop + wipe all data (forces first-time init next time)
```

---

## Local build (optional — Docker does this automatically)

Requires Java 21:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew shadowJar          # builds build/libs/claims-api.jar
```
