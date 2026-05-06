# CLAUDE.md

Compact repo guidance for AI coding agents.

## Project snapshot

ChainCheck is a Spring Boot 4 / Java 25 dashboard for monitoring multiple EVM RPC nodes across several chain types. It combines HTTP polling and WebSocket `newHeads` streams to track latency, head/safe/finalized delay, anomalies, block agreement, and finality.

See `BLOCKCHAINS.md` for supported profiles and `README.md` for end-user setup.

## Commands

```bash
mvn clean install
mvn test
mvn verify
mvn spring-boot:run
mvn -Dspring-boot.run.profiles=mock spring-boot:run
mvn test -Dtest=ClassName
mvn test -Dtest=ClassName#methodName
```

- Integration tests use the `IT` suffix and run during `mvn verify`.
- Built-in profiles live in `src/main/resources/application-*.yml`.

## Code map

- `config` - `ChainCheckProperties`, mode types, request profiles, YAML binding
- `model` - samples, anomalies, time ranges, attestation confidence
- `monitor` - HTTP/WS monitoring, anomaly detection, node registry, connection tracking
- `reference/node` - configured consensus source vs voting reference strategy, reference-node selection
- `reference/attestation` - attestation tracking and confidence
- `reference/block` - block voting and canonical block storage
- `store` - in-memory retention tiers and histogram-backed aggregates
- `web` - dashboard controllers/services, chart building, DTOs/view models

## Must-know behavior

### Core config

- `rpc.mode` is informational only.
- `rpc.mode-type` drives behavior: `ETHEREUM`, `COSMOS`, `OPTIMISM`, `ZK`, `AVALANCHE`, `TRON`.
- Request defaults: Ethereum `12s/60s` (`optimal/sparse`); all other mode types `2s/30s`.
- Per-node `requests: optimal|sparse` selects the mode-level polling interval.

### Ethereum mode

- Treat WS `newHeads` as wake-up signals, not a canonical ordered stream.
- Never trust the block number from the WS event. Block identity is always the hash.
- `WsMonitorService` must verify each new head with `eth_getBlockByHash` immediately.
- A delayed HTTP verification sample is created after `rpc.block-verification-delay-ms` (default `5000`).
- If `rpc.consensus.http` is configured, the consensus node is the global source of safe/finalized blocks for all execution nodes.
- Attestation tracking is Ethereum-only and optional.

### Non-Ethereum mode types

- `WsMonitorService.handleCosmosNewHead` trusts the WS payload directly.
- No delayed `getBlockByHash` verification path.
- Without `rpc.consensus.http`, canonical head comes from majority voting and safe/finalized status stays per node.
- With `rpc.consensus.http`, configured-consensus behavior applies globally here too.

### Finality and canonicality

- Finality order is `NEW < SAFE < FINALIZED`.
- SAFE/FINALIZED status propagates backward through the `parentHash` chain.
- Orphan/invalid detection is based on `parentHash` chain competition plus stronger confidence, never block number collisions.
- `CONFLICT` is not used by the dashboard; `conflict=false` is effectively fixed behavior.

## Important implementation rules

- For Ethereum `newHeads`, never derive chain state from WS event order.
- Reorg handling must be hash/parentHash based, never number adjacency based.
- `DashboardService` applies consensus-node safe/finalized knowledge globally by block hash when a consensus node exists.
- `ReferenceNodeSelector` scores the last hour heavily on uptime, latency, and delay; unhealthy nodes are disqualified.

## Conventions

- Keep the Apache 2.0 file header where the codebase already uses it.
- No Lombok.
- Use plain Java POJOs plus records for value types.
- Use `java.net.http.HttpClient` / `WebSocket`, not an external HTTP client library.
- JSON handling uses Jackson.

## GitNexus

GitNexus workflow rules are intentionally kept in `AGENTS.md`. Follow that file before editing symbols or refactoring.
