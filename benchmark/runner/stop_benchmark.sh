#!/usr/bin/env bash
# Stop a benchmark started by run_benchmark.sh.
#
#   ./benchmark/runner/stop_benchmark.sh
#
# Stopping only ends the current cell; everything already written to
# benchmark/results/runs.csv is kept, so running run_benchmark.sh again resumes
# at the first cell that is not finished yet.
set -uo pipefail
SELF=$(readlink -f "${BASH_SOURCE[0]}")
cd "$(dirname "$SELF")/../.."

OUT=${BENCH_OUT:-}
if [ -z "$OUT" ] && [ -f "${CONFIG:-benchmark/runner/benchmark.yaml}" ]; then
  # follow run.output_dir, so a non-default config is stopped too
  OUT=$(python3 benchmark/runner/config.py "${CONFIG:-benchmark/runner/benchmark.yaml}" 2>/dev/null \
        | sed -n 's/^OUT=//p' | tr -d "'\"")
fi
OUT=${OUT:-benchmark/results}
PIDFILE=$OUT/benchmark.pid
PGIDS=$OUT/logs/.pgids

running() { # pid
  [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null
}

kill_groups() { # signal
  local sig=$1 pgid
  [ -f "$PGIDS" ] || return 0
  for pgid in $(tr -d "'\" " < "$PGIDS"); do
    kill -"$sig" -- "-$pgid" 2>/dev/null || kill -"$sig" "$pgid" 2>/dev/null || true
  done
}

pid=$(cat "$PIDFILE" 2>/dev/null || true)

if ! running "$pid"; then
  # leftovers from an unclean stop (killed -9, machine reboot, ...)
  kill_groups KILL
  rm -f "$PIDFILE" "$PGIDS"
  echo "not running (nothing to stop)"
  exit 0
fi

echo "stopping benchmark (pid $pid)..."
kill -TERM "$pid" 2>/dev/null || true   # handler stops workers and flushes the log
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  running "$pid" || break
  sleep 0.5
done

if running "$pid"; then
  echo "  still alive after 10s, sending KILL" >&2
  kill -KILL "$pid" 2>/dev/null || true
  sleep 0.5
fi

kill_groups KILL
rm -f "$PIDFILE" "$PGIDS"
echo "stopped. run run_benchmark.sh again to resume from the last finished cell."
