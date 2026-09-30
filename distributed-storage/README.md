# 🌐 Distributed File Storage System (Java, zero dependencies)

A mini-Dropbox: a coordinator exposes an HTTP API and spreads files across storage nodes with replication, failure detection, checksum verification and self-healing.

## Features (all implemented and demo-tested)
Upload/download/list/delete · SHA-256 checksums · replication factor (default 2) · least-loaded placement · round-robin read balancing · heartbeat failure detection (dead after 6 s) · automatic failover on reads · **automatic re-replication** when a node dies · **corruption detection** (bad replica dropped, then healed) · user accounts (PBKDF2) + bearer tokens · per-user namespaces · concurrent requests (thread pools) · shared cluster key for node↔coordinator traffic · Docker Compose.

## Architecture
```
 Client (curl)
    │ HTTP + Bearer token
    ▼
 Coordinator :8080  ── meta.db / users.db (file-backed metadata)
    │   ▲ heartbeats every 2s (X-Cluster-Key)
    ├──────────┬──────────┐  PUT/GET/DELETE /blob/<sha256>
    ▼          ▼          ▼
 node1:9001  node2:9002  node3:9003     (each stores blobs named by SHA-256)
```
Write path: hash file → pick `REPLICAS` least-loaded live nodes → write in parallel → record the nodes that acknowledged (an under-replicated result returns a warning and is healed later).
Read path: rotate replica list (load balancing) → skip dead nodes → fetch → verify SHA-256 → on mismatch drop that replica and try the next.
Healer (every 2 s): any file with fewer live replicas than `REPLICAS` is copied from a verified source to another live node.

## Run it locally (needs Java 17+ JDK)
```bash
cd distributed-storage
scripts/build.sh             # compiles to ./out
scripts/run-local.sh         # 1 coordinator + 3 nodes; logs in ./run
```
Use it:
```bash
B=http://localhost:8080
TOKEN=$(curl -s -X POST $B/register -d 'user=alice&pass=password123' | sed 's/.*"token":"\([^"]*\)".*/\1/')
curl -X PUT -H "Authorization: Bearer $TOKEN" --data-binary @photo.jpg $B/files/photo.jpg
curl -H "Authorization: Bearer $TOKEN" $B/files                      # list + replica locations
curl -H "Authorization: Bearer $TOKEN" $B/files/photo.jpg -o back.jpg
curl $B/status                                                       # node health, under-replication
scripts/stop-local.sh
```

## The portfolio demo (kill a node)
`scripts/demo.sh` uploads a file, **kills a node holding it**, downloads the file immediately (failover), waits for re-replication, **corrupts a replica on disk**, and proves downloads still return the exact bytes. A verified run prints `OK: checksum matches`, then `re-replicated ... -> node1`, then `CORRUPT replica ... dropping it`.

## Docker
```bash
docker compose up --build            # coordinator on :8080, nodes node1..3
docker compose stop node2            # kill a node while it runs, then download again
docker compose start node2           # it rejoins ("node recovered" in logs)
```

## Configuration (env vars)
Coordinator: `PORT=8080`, `REPLICAS=2`, `DATA_DIR=./coord-data`, `CLUSTER_KEY=dev-secret`.
Node: `NODE_ID`, `PORT`, `DATA_DIR`, `COORDINATOR_URL`, `ADVERTISE_URL` (the URL the **coordinator** uses to reach the node), `CLUSTER_KEY`.
**Change `CLUSTER_KEY`** for anything beyond a demo; it must be identical everywhere.

## Design trade-offs / known limitations (say these in interviews)
* The coordinator is a **single point of failure** (nodes survive, control plane doesn't). Fix: Raft/etcd-backed metadata or an active-passive pair.
* Metadata is file-backed and fully rewritten on change (simple, not scalable). Replace `load()/persist()` with JDBC.
* Tokens are in memory, so restarting the coordinator logs everyone out (users persist).
* Whole files are held in memory (50 MB cap); real systems chunk and stream.
* Placement is least-loaded by file count, not bytes or rack-aware; no consistent hashing.
* Plain HTTP: add TLS (reverse proxy) before exposing it anywhere.
* A node that returns after being replaced may hold a now-surplus replica (harmless; no garbage collection yet).

## Troubleshooting: errors you are likely to hit
| Symptom | Cause | Fix |
|---|---|---|
| `javac: command not found` | JRE installed, not JDK | Install a JDK (`sudo apt install openjdk-21-jdk`, or brew/Temurin). `build.sh` also falls back to `java -m jdk.compiler/...` |
| `UnsupportedClassVersionError` / `release version 17 not supported` | Java older than 17 | Use JDK 17+ (`java -version`) |
| `error: class X is public, should be declared in a file named X.java` | Renamed a file/class | Keep `Coordinator`, `StorageNode` in same-named files |
| `Error: Could not find or load main class Coordinator` | Not built, or wrong directory | `scripts/build.sh`; run from `distributed-storage/` with `-cp out` |
| `java.net.BindException: Address already in use` | Ports 8080/9001-3 busy, or a stale run | `scripts/stop-local.sh`; or change `PORT` |
| Node log: `heartbeat failed (is the coordinator up...)` | Coordinator not started or wrong `COORDINATOR_URL` | Start coordinator first; check the URL |
| Heartbeat returns `403 bad cluster key` | `CLUSTER_KEY` differs between processes | Use the same value everywhere (`export` it before starting all) |
| `/status` shows no nodes | Nodes not started yet (they appear within 2 s) | Wait, then check `run/node1.log` |
| Docker: nodes listed but `alive:false`, or uploads say `all replica writes failed` | `ADVERTISE_URL` uses `localhost` (coordinator can't reach that) | Use the compose service name (`http://node1:9000`), as provided |
| `503 no live storage nodes` | All nodes dead or none started | Start nodes; check `/status` |
| Upload warns `under-replicated; will heal` | Fewer live nodes than `REPLICAS` | Start more nodes; it heals automatically |
| `503 no healthy replica available` | All replicas dead/corrupt | Restart the nodes holding the file (`/files` lists them) |
| `401 missing/invalid bearer token` | Token absent, typo, or coordinator restarted | `POST /login` again |
| `400 file name must match ...` | Spaces/slashes in the name | Use `[A-Za-z0-9._-]`, max 100 chars |
| `413 file too large` | Over 50 MB | Raise `Util.MAX_BODY` (and JVM heap `-Xmx`) |
| curl upload sends corrupted/text-mangled data | Used `-d` instead of `--data-binary` | Always use `--data-binary @file` |
| `409 user exists` | Registered twice | Use `/login` |
| `OutOfMemoryError` under many big uploads | Files buffered in RAM × 32 threads | Lower pool size / cap or run with `-Xmx1g` |
| Windows: `scripts/*.sh` won't run | No bash | Use WSL or Git Bash, or run the `java -cp out ...` commands from the scripts manually |
| `demo.sh` says `kill: No such process` | Stack not running via `run-local.sh` | Run `scripts/run-local.sh` first |
