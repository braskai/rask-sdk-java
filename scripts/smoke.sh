#!/bin/sh
# Runs examples/smoke in Docker: this machine has no JDK. RASK_API_KEY and RASK_BASE_URL pass through by name, so the key
# is never on a command line. A stand on this machine's own loopback is http://host.docker.internal:<port>/... from here.
# Extra docker run arguments go in SMOKE_DOCKER_ARGS.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.orbstack/run/docker.sock}"
IMAGE="maven:3.9-eclipse-temurin-21"
docker run --rm -v "$ROOT":/work -w /work -v rask-sdk-java-m2:/root/.m2 "$IMAGE" mvn -B -ntp -q compile
exec docker run --rm -v "$ROOT":/work -w /work -e RASK_API_KEY -e RASK_BASE_URL -e RASK_LOG ${SMOKE_DOCKER_ARGS:-} \
  "$IMAGE" java -cp target/classes examples/smoke/Smoke.java
