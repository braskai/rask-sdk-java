# This machine has no JDK, so the targets run Maven in Docker. With a JDK and Maven of your own, use mvn directly.
.PHONY: generate build lint test test-11 test-21 test-slow check-generated smoke verify

generate:
	python3 tools/generate.py

check-generated:
	python3 tools/test_generate.py

build:
	scripts/docker-mvn.sh 21 compile

lint:
	scripts/docker-mvn.sh 21 checkstyle:check javadoc:javadoc

test: test-21

test-21:
	scripts/docker-test.sh 21

test-11:
	scripts/docker-test.sh 11

test-slow:
	RUN_SLOW=1 scripts/docker-test.sh 21

# Against RASK_BASE_URL with RASK_API_KEY, both from your environment. It creates and deletes one folder.
smoke:
	scripts/smoke.sh

verify: check-generated lint test-11 test-21
