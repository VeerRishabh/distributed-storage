#!/usr/bin/env bash
# Portfolio demo: kill a node, prove the file is still retrievable, then corrupt a replica.
set -euo pipefail; cd "$(dirname "$0")/.."
B=http://localhost:8080; U=demo_$RANDOM
TOKEN=$(curl -s -X POST $B/register -d "user=$U&pass=password123" | sed 's/.*"token":"\([^"]*\)".*/\1/')
H="Authorization: Bearer $TOKEN"
head -c 300000 /dev/urandom > /tmp/demo.bin; ORIG=$(sha256sum /tmp/demo.bin | cut -d' ' -f1)
echo "== upload"; curl -s -X PUT -H "$H" --data-binary @/tmp/demo.bin $B/files/demo.bin; echo
REPL=$(curl -s -H "$H" $B/files | grep -o 'node[0-9]' | sort -u | head -1)
echo "== killing $REPL"; kill "$(cat run/$REPL.pid)"; sleep 1
echo "== download immediately (failover)"; curl -s -H "$H" -D /tmp/h.txt $B/files/demo.bin -o /tmp/got.bin
grep -i x-served-by /tmp/h.txt; [ "$(sha256sum /tmp/got.bin | cut -d' ' -f1)" = "$ORIG" ] && echo "OK: checksum matches"
echo "== wait for failure detector + re-replication"; sleep 10; curl -s $B/status; echo
echo "== corrupt one remaining replica on disk"
F=$(ls run/data-node*/${ORIG} | head -1); echo garbage >> "$F"; echo "corrupted $F"
for i in 1 2 3 4; do curl -s -H "$H" $B/files/demo.bin -o /tmp/got.bin; [ "$(sha256sum /tmp/got.bin | cut -d' ' -f1)" = "$ORIG" ] && echo "OK $i: still correct"; done
echo "== coordinator log:"; grep -E "CORRUPT|re-replicated" run/coordinator.log || true
