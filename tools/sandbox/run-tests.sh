#!/usr/bin/env bash
# Runs the automated client game tests headless in the Proxmox sandbox container.
#   tools/sandbox/run-tests.sh            # mod HUD
#   tools/sandbox/run-tests.sh pack       # resource-pack HUD (client pretends to be vanilla)
# Results (screenshots + logs) are copied to build/sandbox/<mode>/.
# Override the target with DS_SSH_HOST (default: pve) and DS_CTID (default: 1500).
set -euo pipefail

HOST="${DS_SSH_HOST:-pve}"
CTID="${DS_CTID:-1500}"
MODE="${1:-mod}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/sandbox/$MODE"
REMOTE=/root/deathswap

EXTRA=""
[ "$MODE" = "pack" ] && EXTRA="-PvanillaHud"

echo "==> syncing sources to CT $CTID"
tar -C "$ROOT" --owner=0 --group=0 -czf - --exclude=./build --exclude=./.gradle --exclude=./run --exclude=./.git . \
  | ssh "$HOST" "pct exec $CTID -- bash -c 'rm -rf $REMOTE/src && mkdir -p $REMOTE && tar --no-same-owner -xzf - -C $REMOTE'"

echo "==> running client game tests ($MODE HUD)"
set +e
ssh "$HOST" "pct exec $CTID -- bash -lc 'cd $REMOTE && sed -i \"s/\r\$//\" gradlew && chmod +x gradlew && \
  export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:\$PATH LANG=C.UTF-8 LC_ALL=C.UTF-8 && \
  xvfb-run -a -s \"-screen 0 1280x720x24\" ./gradlew runClientGameTest $EXTRA --console=plain --no-daemon 2>&1 | tail -n 400'" \
  | grep -E "deathswap-test|ok:|FAILED|ALL DEATHSWAP|BUILD|Exception|Error|error:" | grep -v "Win32Exception"
STATUS=${PIPESTATUS[0]}
set -e

echo "==> fetching results to $OUT"
rm -rf "$OUT" && mkdir -p "$OUT"
ssh "$HOST" "pct exec $CTID -- bash -c 'cd $REMOTE/build/run/clientGameTest && tar -czf - screenshots logs 2>/dev/null'" | tar -xzf - -C "$OUT" || true
ls "$OUT"/screenshots 2>/dev/null || true
exit "$STATUS"
