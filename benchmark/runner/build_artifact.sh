#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

export JAVA_HOME="${JAVA17_HOME:-${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}}"
export PATH="$JAVA_HOME/bin:$PATH"

(cd artifact && "$(command -v mvn || echo /tmp/opencode/apache-maven-3.9.9/bin/mvn)" -q clean package "$@")

echo "built: artifact/target/$(basename "$(ls artifact/target/top-k-husm-mg-*.jar | head -n 1)")"
