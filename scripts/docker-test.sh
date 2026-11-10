#!/bin/sh
# Runs the whole test suite in Docker against the acceptance harness, with no route off the machine: the tests and the
# harness share one network namespace that has only a loopback interface. A bug that lost the base URL cannot reach
# production. Usage: scripts/docker-test.sh [11|21] [maven args]; RUN_SLOW=1 adds the two slow scenarios.
set -eu
JDK="${1:-21}"
shift || true
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HARNESS_DIR="${RASK_SDK_ACCEPTANCE_DIR:-$ROOT/../rask-sdk-acceptance}"
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.orbstack/run/docker.sock}"
IMAGE="maven:3.9-eclipse-temurin-$JDK"
NAME="rask-sdk-java-harness-$$"

docker build -q -t rask-sdk-acceptance:local "$HARNESS_DIR" >/dev/null

# Fetch every plugin while there is a network; the run itself is offline.
docker run --rm -v "$ROOT":/work -w /work -v rask-sdk-java-m2:/root/.m2 "$IMAGE" \
  mvn -B -ntp -q test -Dtest=JsonTest

docker run -d --rm --name "$NAME" --network none rask-sdk-acceptance:local \
  --host 127.0.0.1 --public-host 127.0.0.1 >/dev/null
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
i=0
until docker exec "$NAME" wget -q -O /dev/null http://127.0.0.1:4010/__control/health 2>/dev/null; do
  i=$((i + 1))
  [ "$i" -lt 50 ] || { echo "The harness did not start" >&2; docker logs "$NAME" >&2; exit 1; }
  sleep 0.2
done

docker run --rm --network "container:$NAME" \
  -v "$ROOT":/work -w /work -v rask-sdk-java-m2:/root/.m2 \
  -e RASK_ACCEPTANCE_URL=http://127.0.0.1:4010 -e RUN_SLOW="${RUN_SLOW:-}" \
  "$IMAGE" mvn -B -ntp -o test "$@"
