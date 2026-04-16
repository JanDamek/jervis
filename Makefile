# Jervis monorepo top-level Makefile.
#
# Targets in this file orchestrate pod-to-pod contract generation and
# verification. See docs/inter-service-contracts.md for the SSOT.

PROTO_DIR := proto
KOTLIN_GEN := shared/service-contracts/build/generated/source/buf
PY_GEN := libs/jervis_contracts
PY_PKG := libs/jervis_contracts
PROTO_FILES := $(shell find $(PROTO_DIR) -name "*.proto" 2>/dev/null)

.PHONY: help proto-lint proto-breaking proto-generate proto-generate-kotlin proto-generate-python proto-verify python-install-contracts

help:
	@echo "Jervis monorepo — contract targets:"
	@echo "  proto-lint                 Lint proto/ via `buf lint`"
	@echo "  proto-breaking             Check proto/ for breaking changes vs master"
	@echo "  proto-generate             Regenerate Kotlin + Python stubs from proto/"
	@echo "  proto-verify               Lint + breaking + generate + drift-free diff"
	@echo "  python-install-contracts   pip install -e libs/jervis_contracts"

proto-lint:
	cd $(PROTO_DIR) && buf lint

proto-breaking:
	@# Against the master branch's `proto/` subtree. Tolerant of the
	@# "subtree didn't exist yet" case (first PR introducing proto/).
	cd $(PROTO_DIR) && ( \
	  buf breaking --against '../.git#branch=master,subdir=proto' 2>&1 \
	  | tee /tmp/buf_breaking.out; \
	  if grep -q 'had no .proto files' /tmp/buf_breaking.out; then \
	    echo "(proto/ not yet on master — skipping breaking check)"; \
	    exit 0; \
	  fi; \
	  ! grep -q 'Failure' /tmp/buf_breaking.out \
	)

proto-generate: proto-generate-kotlin proto-generate-python

# Kotlin/Java via Buf remote plugins (no reliable local alternative for
# grpc-kotlin).
proto-generate-kotlin:
	cd $(PROTO_DIR) && buf generate

# Python via local `grpcio-tools` so gencode always matches the installed
# runtime (remote buf/protocolbuffers/python plugin has released gencode
# ahead of PyPI `protobuf` in the past — local keeps them in lockstep).
proto-generate-python:
	@mkdir -p $(PY_GEN)
	@if [ -z "$(PROTO_FILES)" ]; then echo "no .proto files"; exit 0; fi
	python3 -m grpc_tools.protoc \
	  --proto_path=$(PROTO_DIR) \
	  --python_out=$(PY_GEN) \
	  --grpc_python_out=$(PY_GEN) \
	  --pyi_out=$(PY_GEN) \
	  $(PROTO_FILES)
	@# Ensure __init__.py in each generated proto package directory (only the
	@# jervis/ subtree — never touch the outer libs/jervis_contracts/ root,
	@# and skip __pycache__ which is not a proto package).
	@find $(PY_GEN)/jervis -type d -not -path '*__pycache__*' -exec touch {}/__init__.py \;

# CI guardrail — any drift between committed generated code and the current
# proto/ state makes the tree dirty, fails, and forces the developer to
# re-commit with `make proto-generate`.
proto-verify: proto-lint proto-breaking proto-generate
	git diff --exit-code -- $(PROTO_DIR) $(KOTLIN_GEN) libs/jervis_contracts/jervis

python-install-contracts:
	pip install -e $(PY_PKG)
