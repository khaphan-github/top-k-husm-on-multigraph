#!/usr/bin/env bash
# Multigraph benchmark for topk.husm.mg (matrix + limits come from benchmark.yaml).
#
#   ./benchmark/runner/run_benchmark.sh    # start (build + run in background)
#   ./benchmark/runner/stop_benchmark.sh   # stop
#
# Output: benchmark/results/config.yaml         snapshot of the config used
#         benchmark/results/runs.csv            one row per run (append-only history)
#         benchmark/results/logs/benchmark.log  ONE log for every cell
#
# `timeout_sec` in the config is the budget for ONE mining run; every cell runs
# `warmup + repeats` mining runs in the same JVM, so the wall-clock cap applied
# to that process is `timeout_sec * (warmup + repeats)`.
#
# Resume: a cell whose last row in runs.csv is "ok" or "timeout" is skipped, so
# after a stop the next start continues where it stopped.
set -euo pipefail
SELF=$(readlink -f "${BASH_SOURCE[0]}")
cd "$(dirname "$SELF")/../.."

CONFIG=${CONFIG:-benchmark/runner/benchmark.yaml}
HEADER="cell,timestamp,status,dataset,K,mer,nlabels,wdist,label_mode,ieu_bound,incremental_domains,evaluated,expanded,pruned,theta,cpu_ms,wall_ms,alloc_bytes,peak_heap_bytes,peak_live_bytes,mem_logger_peak_bytes,mem_logger_interval_ms,mem_logger_samples,warmup,repeats"

command -v java >/dev/null || { echo "error: java not on PATH" >&2; exit 1; }
command -v jq   >/dev/null || { echo "error: jq is required" >&2; exit 1; }

# ------------------------------------------------------------- cleanup
# TERM (from stop_benchmark.sh) must also stop the running workers. Without
# this, bash only interrupts itself: the `timeout`/`java` children (started
# with `&`) keep running and the JVM survives the script.
#
# Workers are launched as background jobs, i.e. in subshells, so they cannot
# update a parent-shell array. They instead append their process-group id to
# a registry file that the signal handler reads. Whatever a cell already wrote
# is flushed into the single benchmark.log before the script exits.
PGID_FILE=""
PARTS=""
STOPFILE=""
CLEANING=0

stopping() { # a worker subshell cannot see CLEANING (own process), use a file
  [ -n "${STOPFILE:-}" ] && [ -e "$STOPFILE" ]
}

flush_part() { # append one cell's block to the single log, atomically
  local part=$1
  if [ -z "${LOG:-}" ] || [ -z "${LOCK:-}" ] || [ ! -f "$part" ]; then
    rm -f "$part"
    return 0
  fi
  flock "$LOCK" bash -c 'cat -- "$1"; printf "\n"' _ "$part" >> "$LOG"
  rm -f "$part"
}

kill_children() {
  local pgid
  [ -n "$PGID_FILE" ] && [ -f "$PGID_FILE" ] || return 0
  for pgid in $(tr -d "'\" " < "$PGID_FILE"); do
    # negative pid == kill the whole process group (timeout + java)
    kill -TERM -- "-$pgid" 2>/dev/null || kill -TERM "$pgid" 2>/dev/null || true
  done
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    local alive=0
    for pgid in $(tr -d "'\" " < "$PGID_FILE"); do
      kill -0 -- "-$pgid" 2>/dev/null && alive=1
    done
    (( alive )) || break
    sleep 0.5
  done
  for pgid in $(tr -d "'\" " < "$PGID_FILE"); do
    kill -KILL -- "-$pgid" 2>/dev/null || kill -KILL "$pgid" 2>/dev/null || true
  done
  : > "$PGID_FILE"
}

on_signal() {
  (( CLEANING )) && return
  CLEANING=1
  echo >&2
  echo "caught signal, stopping workers..." >&2
  # flag first, so a worker that wakes up from `wait` knows why its JVM died
  [ -n "${STOPFILE:-}" ] && : > "$STOPFILE" 2>/dev/null
  kill_children
  if [ -n "$PARTS" ] && [ -d "$PARTS" ]; then
    local part
    for part in "$PARTS"/*.part; do
      [ -f "$part" ] || continue
      printf 'INTERRUPTED: worker stopped before the cell finished\n' >> "$part"
      flush_part "$part"
    done
    rm -rf "$PARTS"
  fi
  [ -n "${PIDFILE:-}" ] && rm -f "$PIDFILE" 2>/dev/null || true
  [ -n "${STOPFILE:-}" ] && rm -f "$STOPFILE" 2>/dev/null || true
  printf 'stopped; finished cells stay in %s, re-run to resume.\n' "${CSV:-runs.csv}" >&2
  trap - INT TERM HUP
  exit 130
}
trap on_signal INT TERM HUP

# config -> shell variables (validates paths, heap, ...)
SETTINGS=$(python3 benchmark/runner/config.py "$CONFIG") || exit 1
eval "$SETTINGS"

JAVA_OPTS="${BENCH_JAVA_OPTS:-${HEAP:+-Xmx$HEAP}}"
CSV=$OUT/runs.csv
LOGS=$OUT/logs
LOG=$LOGS/benchmark.log
PARTS=$LOGS/.parts
PIDFILE=$OUT/benchmark.pid
LOCK=$LOGS/.lock
mkdir -p "$OUT" "$LOGS"

# ------------------------------------------------------------- background
# Always detached: the campaign outlives the terminal and is stopped with
# stop_benchmark.sh. The child writes its own pid because `setsid` may fork.
# Only one run may own an output_dir (shared log + pgid registry).
if [ -n "${BENCH_DETACHED:-}" ]; then
  echo $$ > "$PIDFILE"
  trap 'rm -f "$PIDFILE"' EXIT
else
  if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null || true)
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
      echo "error: a benchmark is already running (pid $old)" >&2
      echo "       stop it with ./benchmark/runner/stop_benchmark.sh" >&2
      exit 1
    fi
  fi
  BENCH_DETACHED=1 setsid nohup "$SELF" >>"$LOG" 2>&1 </dev/null &
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ -s "$PIDFILE" ] && break
    sleep 0.2
  done
  pid=$(cat "$PIDFILE" 2>/dev/null || true)
  echo "benchmark started in background${pid:+ (pid $pid)}"
  echo "  log : $LOG"
  echo "  stop: ./benchmark/runner/stop_benchmark.sh"
  exit 0
fi

# one worker per pinned core; ncores=1 means "no pinning"
ncores=${#CORE_LIST[@]}
if (( ncores > 0 )); then
  command -v taskset >/dev/null || { echo "error: taskset is required for core_ids" >&2; exit 1; }
fi
(( ncores > 0 )) || ncores=1

# `BENCH_SKIP_BUILD=1` uses the jar as-is, e.g. when it was built on the control
# node and rsynced here (the benchmark host may have no Maven toolchain).
if [ "${BENCH_SKIP_BUILD:-0}" != "1" ]; then
  ./benchmark/runner/build_artifact.sh
fi
[ -f "$JAR" ] || { echo "error: $JAR not found (build it or sync it from the control node)" >&2; exit 1; }
rm -rf "$PARTS"
mkdir -p "$PARTS"
PGID_FILE="$LOGS/.pgids"
STOPFILE="$LOGS/.stopping"
: > "$PGID_FILE"
rm -f "$STOPFILE"

# (1) snapshot of the config actually used, plus provenance
{
  echo "# snapshot of $CONFIG"
  echo "# started_utc: $(date -u +%FT%TZ)"
  echo "# git_sha: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "# java: $(java -version 2>&1 | head -n1)"
  echo "# java_opts: ${JAVA_OPTS:-<jvm default>}  jobs: $JOBS"
  echo "# cores: ${CORE_LIST[*]:-<all>}"
  echo "# host: $(uname -srm) / $(nproc) cpus"
  echo
  cat "$CONFIG"
} > "$OUT/config.yaml"

# (2) one CSV for every run, ever
if [ -s "$CSV" ] && [ "$(head -n1 "$CSV")" != "$HEADER" ]; then
  mv "$CSV" "$OUT/runs.$(date -u +%Y%m%dT%H%M%SZ).csv"
  echo "note: old runs.csv had a different schema, moved aside" >&2
fi
[ -s "$CSV" ] || echo "$HEADER" > "$CSV"

# (3) one log for every cell, appended across runs (never rotated): it is the
# trace of the whole campaign, one block per cell, marked by its "run ..." line
# ($LOCK serialises the CSV rows and the cell blocks when jobs > 1)
{
  printf '\n================================================================================\n'
  printf 'run  %s  pid %s\n' "$(date -u +%FT%TZ)" "$$"
  printf 'cfg  %s  jobs=%s  heap=%s  cores=%s\n' "$CONFIG" "$JOBS" "${HEAP:-jvm default}" "${CORE_LIST[*]:-all}"
  printf 'log  %s\n' "$LOG"
} >> "$LOG"

# last row for a cell wins; ok and timeout are terminal, only 'failed' retries
finished() {
  awk -F, -v c="$1" '
    NR > 1 { k = $1; st = $3; gsub(/"/, "", k); gsub(/"/, "", st); if (k == c) last = st }
    END    { exit !(last == "ok" || last == "timeout") }' "$CSV"
}

count_status() {
  awk -F, -v want="$1" 'NR > 1 { s = $3; gsub(/"/, "", s); if (s == want) n++ } END { print n + 0 }' "$CSV"
}

append_row() { # cell, timestamp, status, json-file ("" for a failed run), dataset id
  local cell=$1 ts=$2 status=$3 src=$4 ds=${5:-}
  if [ -n "$src" ]; then
    flock "$LOCK" jq -r --arg c "$cell" --arg t "$ts" --arg s "$status" '
      [$c,$t,$s,.dataset,.K,.mer,.nlabels,.wdist,.label_mode,.ieu_bound,
       .incremental_domains,.evaluated,.expanded,.pruned,.theta,.cpu_ms,.wall_ms,
       .alloc_bytes,.peak_heap_bytes,.peak_live_bytes,
       .mem_logger_peak_bytes,.mem_logger_interval_ms,.mem_logger_samples,
       .warmup,.repeats] | @csv' "$src" >> "$CSV"
  else
    flock "$LOCK" jq -rn --arg c "$cell" --arg t "$ts" --arg s "$status" --arg d "$ds" \
      '([$c,$t,$s,$d] + [range(21) | ""]) | @csv' >> "$CSV"
  fi
}

run_one() { # pos, cell, dataset id, file, K, mer, bound, incremental, topk, slot
  local pos=$1 cell=$2 did=$3 file=$4 K=$5 mer=$6 bound=$7 inc=$8 topk=$9 slot=${10}
  local part="$PARTS/$cell.part" json="$PARTS/.$cell.json" status=ok rc=0
  local -a cmd=()
  if (( ${#CORE_LIST[@]} > 0 )); then
    cmd=(taskset -c "${CORE_LIST[$((slot % ${#CORE_LIST[@]}))]}")
  fi
  cmd+=(java)
  if [ -n "$JAVA_OPTS" ]; then
    # shellcheck disable=SC2206  # BENCH_JAVA_OPTS is intentionally word-split
    cmd+=($JAVA_OPTS)
  fi
  cmd+=(-cp "$JAR" "$MAIN" "$did"
        --edges-file "$file" --K "$K" --mer "$mer" --nlabels "$NLABELS"
        --label-mode "$LABEL_MODE" --wdist "$WDIST"
        --ieu-bound "$bound" --incremental-domains "$inc"
        --enable-top-k "$topk")
  if [ -n "$MAX_M" ]; then cmd+=(--max-m "$MAX_M"); fi
  if [ -n "$MAX_V" ]; then cmd+=(--max-v "$MAX_V"); fi
  if [ -n "${MIN_UTIL:-}" ]; then cmd+=(--min-util "$MIN_UTIL"); fi
  if [ -n "${MEM_LOGGER_MS:-}" ] && [ "$MEM_LOGGER_MS" != "0" ]; then
    cmd+=(--mem-logger-ms "$MEM_LOGGER_MS")
  fi
  if [ -n "${LOG_LEVEL:-}" ]; then cmd+=(--log-level "$LOG_LEVEL"); fi
  if [ -n "${PROFILE:-}" ] && [ "${PROFILE}" != "off" ]; then cmd+=(--profile "$PROFILE"); fi
  cmd+=(--warmup "$WARMUP" --repeats "$REPEATS")

  # One process performs warmup+repeats mining runs; the configured timeout is
  # per run, so the process-level budget must cover all of them (otherwise a
  # single config that is fine on its own is reported as timeout).
  local budget=$(( TIMEOUT * (WARMUP + REPEATS) ))
  (( budget > 0 )) || budget=$TIMEOUT

  # Everything a cell produces lands in a part file first and is appended to
  # the single benchmark.log when the cell ends, so concurrent workers (jobs>1)
  # never interleave inside one cell block.
  {
    printf '================================================================================\n'
    printf '[%s] %s\n' "$pos" "$cell"
    printf 'Dataset=%s, Topk=%s, enable_top_k=%s, min_util=%s, mer=%s, nlabels=%s, wdist=%s, label_mode=%s, ieu_bound=%s, incremental_domains=%s, max_m=%s, max_v=%s, mem_logger_ms=%s, profile=%s, warmup=%s, repeats=%s\n' \
      "$did" "$K" "$topk" "${MIN_UTIL:-none}" "$mer" "$NLABELS" "$WDIST" "$LABEL_MODE" "$bound" "$inc" \
      "${MAX_M:-none}" "${MAX_V:-none}" "${MEM_LOGGER_MS:-0}" "${PROFILE:-none}" "$WARMUP" "$REPEATS"
    printf 'CMD: %s\n' "${cmd[*]}"
    printf 'TIMEOUT: %ss for %d mining run(s)\n' "$budget" "$(( WARMUP + REPEATS ))"
  } > "$part"

  # Run the worker in its own session so the INT/TERM handler can kill
  # `timeout` *and* the JVM with a single group kill. `setsid -w` keeps this
  # subshell as the parent so `wait` still works and exits with the real code.
  local pid pgid started rc=0
  started=$(date +%s)
  setsid -w timeout "${budget}s" "${cmd[@]}" > "$json" 2>> "$part" &
  pid=$!
  # `setsid` does not fork when the caller is not a process-group leader,
  # which is the case for a background subshell, so $! is the session id.
  pgid=$pid
  printf '%s\n' "$pgid" >> "$PGID_FILE"
  if wait "$pid"; then
    jq -e 'has("cpu_ms")' "$json" >/dev/null 2>&1 || status=failed
  else
    rc=$?
    status=failed
    if (( rc == 124 || rc == 137 || rc == 143 )); then status=timeout; fi
  fi
  sed -i "/^$pgid\$/d" "$PGID_FILE" 2>/dev/null || true

  # A cell killed by Ctrl-C / stop_benchmark.sh is *not* recorded as a result:
  # writing it as "timeout" would make it look finished and skip it on resume.
  local label=$status
  stopping && label=interrupted
  printf 'EXIT: %s (rc=%d, %ss)\n' "$label" "$rc" "$(( $(date +%s) - started ))" >> "$part"
  if stopping; then
    flush_part "$part"
    rm -f "$json"
    return 130
  fi

  if [ "$status" = ok ]; then
    append_row "$cell" "$(date -u +%FT%TZ)" ok "$json" "$did"
  else
    append_row "$cell" "$(date -u +%FT%TZ)" "$status" "" "$did"
    echo "  $status: $cell (exit $rc, log: $LOG)" >&2
  fi
  rm -f "$json"
  flush_part "$part"
  return 0
}

# --------------------------------------------------------------- plan
cells=()
p_ds=()
p_file=()
p_k=()
p_mer=()
p_bound=()
p_inc=()
p_topk=()
for ((d = 0; d < ${#DS_IDS[@]}; d++)); do
  for K in "${K_LIST[@]}"; do
  for mer in "${MER_LIST[@]}"; do
  for bound in "${BOUNDS[@]}"; do
  for inc in "${INCREMENTAL[@]}"; do
  for topk in "${TOPK[@]}"; do
    # keep existing cell ids for the default (true) so a resumed campaign is not re-run
    ksfx=""
    [ "$topk" = "false" ] && ksfx=".notopk"
    cells+=("${DS_IDS[d]}.K${K}.mer${mer}.${bound}.inc${inc}${ksfx}")
    p_ds+=("${DS_IDS[d]}")
    p_file+=("${DS_PATHS[d]}")
    p_k+=("$K")
    p_mer+=("$mer")
    p_bound+=("$bound")
    p_inc+=("$inc")
    p_topk+=("$topk")
  done; done; done; done; done; done

total=${#cells[@]}
todo=()
for idx in "${!cells[@]}"; do
  if ! finished "${cells[idx]}"; then todo+=("$idx"); fi
done
n=${#todo[@]}

echo "config: $CONFIG"
printf '  cells: %d total | %d finished | %d to run | jobs=%s heap=%s cores=%s\n' \
  "$total" "$(( total - n ))" "$n" "$JOBS" "${HEAP:-jvm default}" "${CORE_LIST[*]:-all}"
printf '  log  : %s\n' "$LOG"

if (( n == 0 )); then
  echo "nothing to do: every cell is finished"
  exit 0
fi

# --------------------------------------------------------------- run
i=0
running=0
slot=0

for idx in "${todo[@]}"; do
  (( CLEANING )) && break   # stop_benchmark: stop scheduling new work
  i=$((i+1))
  cell=${cells[idx]}
  pos="$i/$n"
  printf '[%s] %s\n' "$pos" "$cell"
  printf '        Dataset=%s, Topk=%s, enable_top_k=%s, min_util=%s, mer=%s, nlabels=%s, wdist=%s, label_mode=%s, ieu_bound=%s, incremental_domains=%s\n' \
    "${p_ds[idx]}" "${p_k[idx]}" "${p_topk[idx]}" "${MIN_UTIL:-none}" "${p_mer[idx]}" "$NLABELS" "$WDIST" "$LABEL_MODE" "${p_bound[idx]}" "${p_inc[idx]}"
  run_one "$pos" "$cell" "${p_ds[idx]}" "${p_file[idx]}" "${p_k[idx]}" "${p_mer[idx]}" "${p_bound[idx]}" "${p_inc[idx]}" "${p_topk[idx]}" "$slot" &
  slot=$(( (slot + 1) % ncores ))
  running=$((running + 1))
  if (( running >= JOBS )); then
    wait -n || true
    running=$((running - 1))
  fi
done
wait || true

(( CLEANING )) && exit 130

# keep exactly the last row per cell (retries replace old attempts)
TMP=$(mktemp)
awk -F, 'NR==1 {print; next}
         { if (!($1 in idx)) { idx[$1] = ++n; order[n] = $1 } last[$1] = $0 }
         END { for (i = 1; i <= n; i++) print last[order[i]] }' "$CSV" > "$TMP"
mv "$TMP" "$CSV"

echo
echo "done: $(count_status ok)/$total ok, $(count_status timeout) timeout, $(count_status failed) failed"
echo "  $OUT/config.yaml   (config used)"
echo "  $CSV               (all runs)"
echo "  $LOG               (single log for every cell)"
