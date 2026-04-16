# jervis-contracts (Python)

Generated Protobuf + gRPC stubs for inter-service contracts in the Jervis
monorepo. See repo root `docs/inter-service-contracts.md` for the SSOT.

## Install (editable, local dev)

```bash
pip install -e libs/jervis_contracts
```

## Generate / regenerate stubs

```bash
make proto-generate        # from repo root
```

Generated files land in `jervis_contracts/_generated/`. Do **not** edit
manually — they are committed so IDEs can resolve types before a build.

## Interceptors

`jervis_contracts.interceptors` ships client + server interceptors that
mirror the Kotlin side (`com.jervis.contracts.interceptors.*`). See
`docs/inter-service-contracts.md` §4 — "No contract data in HTTP headers".
