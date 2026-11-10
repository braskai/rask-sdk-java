#!/bin/sh
# Runs Maven in Docker: this machine has no JDK. Usage: scripts/docker-mvn.sh [11|21] <maven args...>
# Maven's local repository stays in the rask-sdk-java-m2 volume between runs.
set -eu
JDK="${1:-21}"
shift || true
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.orbstack/run/docker.sock}"
exec docker run --rm \
  -v "$ROOT":/work -w /work \
  -v rask-sdk-java-m2:/root/.m2 \
  ${DOCKER_MVN_ARGS:-} \
  "maven:3.9-eclipse-temurin-$JDK" mvn -B -ntp "$@"
