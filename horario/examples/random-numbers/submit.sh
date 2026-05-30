#!/usr/bin/env bash
# submit.sh — submit 1 000 random-number subtasks to the Vasara Compute control plane.
#
# Environment variables (all optional):
#   BASE_URL            control-plane base URL   (default: http://localhost:8080)
#   LANE                target lane              (default: dev)
#   SUBTASK_COUNT       number of subtasks       (default: 1000)
#   NUMBERS_PER_SUBTASK random doubles each      (default: 10000)
#   PARALLELISM         worker pod count         (default: 50)
#   SEED                RNG seed (-1 = random)   (default: -1)
#
# Examples:
#   ./submit.sh
#   BASE_URL=http://compute.internal LANE=sit ./submit.sh
#   SUBTASK_COUNT=100 NUMBERS_PER_SUBTASK=1000000 PARALLELISM=10 ./submit.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
LANE="${LANE:-dev}"
SUBTASK_COUNT="${SUBTASK_COUNT:-1000}"
NUMBERS_PER_SUBTASK="${NUMBERS_PER_SUBTASK:-10000}"
PARALLELISM="${PARALLELISM:-50}"
SEED="${SEED:--1}"

TOTAL=$(( SUBTASK_COUNT * NUMBERS_PER_SUBTASK ))
printf "Submitting %d subtasks (%d numbers each = %d total)\n" \
       "$SUBTASK_COUNT" "$NUMBERS_PER_SUBTASK" "$TOTAL"
printf "  parallelism : %d worker pods\n" "$PARALLELISM"
printf "  lane        : %s\n"             "$LANE"
printf "  control-plane: %s\n\n"          "$BASE_URL"

# Build the JSON body with Python (avoids shell loop slowness for 1000 entries)
BODY=$(python3 - <<PYEOF
import json, sys

subtasks = [
    {
        "subtaskId": f"sub-{i:04d}",
        "kind": "random-numbers",
        "args": {
            "count": ${NUMBERS_PER_SUBTASK},
            **({"seed": ${SEED}} if ${SEED} >= 0 else {}),
        },
    }
    for i in range(${SUBTASK_COUNT})
]

payload = {
    "group": "example",
    "project": "random-numbers",
    "lane": "${LANE}",
    "version": "dev",
    "parallelism": ${PARALLELISM},
    "subtasks": subtasks,
}

print(json.dumps(payload, indent=2))
PYEOF
)

RESPONSE=$(curl -sS -w "\n%{http_code}" \
    -X POST "${BASE_URL}/api/tasks" \
    -H "Content-Type: application/json" \
    -d "$BODY")

HTTP_CODE=$(tail -n1 <<< "$RESPONSE")
BODY_OUT=$(head -n -1 <<< "$RESPONSE")

echo "$BODY_OUT" | python3 -m json.tool 2>/dev/null || echo "$BODY_OUT"
echo ""

if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "201" ]]; then
    TASK_ID=$(echo "$BODY_OUT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('taskId','?'))" 2>/dev/null || echo "?")
    echo "✓ Task submitted (taskId=${TASK_ID})"
    echo "  Open ${BASE_URL}  → Tasks tab to watch progress."
else
    echo "✗ Submission failed (HTTP ${HTTP_CODE})"
    exit 1
fi
