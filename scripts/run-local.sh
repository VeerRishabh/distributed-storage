#!/usr/bin/env bash
# Starts 1 coordinator + 3 nodes in the background. Logs + pids in ./run. Stop with scripts/stop-local.sh
set -euo pipefail; cd "$(dirname "$0")/.."
[ -d out ] || scripts/build.sh
mkdir -p run; scripts/stop-local.sh >/dev/null 2>&1 || true
export CLUSTER_KEY="${CLUSTER_KEY:-dev-secret}"
PORT=8080 REPLICAS=2 java -cp out Coordinator > run/coordinator.log 2>&1 & echo $! > run/coordinator.pid
sleep 1
for i in 1 2 3; do
  NODE_ID=node$i PORT=900$i DATA_DIR=run/data-node$i java -cp out StorageNode > run/node$i.log 2>&1 & echo $! > run/node$i.pid
done
sleep 3; curl -s localhost:8080/status; echo
