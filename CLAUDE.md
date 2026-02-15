# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ChainCheck is an Ethereum/EVM blockchain RPC node monitoring dashboard. It tracks latency, block delays, anomalies, and finality status across multiple HTTP and WebSocket RPC endpoints. Built with Spring Boot 4.0.2, Java 21, Thymeleaf, and Chart.js.

## Build & Run Commands

```bash
mvn clean install                # Build and run all tests
mvn spring-boot:run              # Run the application (dashboard at http://localhost:8080)
mvn test                         # Run all tests
mvn test -Dtest=AnomalyDetectorTest  # Run a single test class
mvn test -Dtest=AnomalyDetectorTest#testMethodName  # Run a single test method
mvn -Dspring-boot.run.profiles=mock spring-boot:run  # Run with mock RPC data
```

Integration tests use the `IT` suffix (e.g., `LargeDataStoreIntegrationTestIT`) and run during `mvn verify`.

## Architecture

All source lives under `de.makibytes.chaincheck` with five packages:

- **config** — `ChainCheckProperties` binds the `rpc.*` YAML namespace. Per-node overrides (timeouts, retries, headers, anomaly thresholds) fall back to `rpc.defaults.*`.
- **model** — Value types: `MetricSample` (raw metrics per poll/event), `AnomalyEvent` (with `AnomalyType` enum: ERROR, RATE_LIMIT, TIMEOUT, DELAY, BLOCK_GAP, CONFLICT, REORG, WRONG_HEAD), `MetricSource` (HTTP vs WS), `TimeRange`.
- **monitor** — Core monitoring services:
  - `RpcMonitorService` — orchestrator, wires up per-node HTTP/WS monitors
  - `HttpMonitorService` — scheduled polling of `eth_blockNumber`/`eth_getBlockByNumber`
  - `WsMonitorService` — WebSocket `eth_subscribe(newHeads)` for real-time head tracking
  - `AnomalyDetector` — evaluates each sample against thresholds, emits `AnomalyEvent`s
  - `ReferenceNodeSelector` / `BlockVotingService` / `ReferenceSelectionPolicy` — consensus reference head via majority voting with hysteresis
  - `NodeRegistry` — maintains the set of monitored nodes
- **store** — `InMemoryMetricsStore` holds three retention tiers: raw samples (2h), minutely aggregates (3d), monthly aggregates (30d). Optional on-disk JSON snapshots via persistence config.
- **web** — `DashboardController` + `DashboardService` serve the Thymeleaf dashboard. `MetricsCache` caches responses.

### Data Flow

1. HTTP monitors poll RPC endpoints on configurable intervals; WS monitors receive `newHeads` events
2. Samples are stored in `InMemoryMetricsStore` and merged by block hash for a unified block view
3. `AnomalyDetector` evaluates each sample in real-time
4. `ReferenceNodeSelector` establishes consensus head via majority voting
5. Dashboard queries the store and renders via Thymeleaf + Chart.js

## Reference Node

### By Configuration

You can configure a specific beacon/consensus node as the reference source for safe/finalized comparisons.

### By Majority Voting

When not explicitly configured, the reference node is selected via majority voting across all monitored nodes.

## Conflicting and Invalid Blocks

- Only one status of new/safe/finalized is allowed. New blocks are new, status=safe replaces status=new and status=finalized replaces both, status=new and status=safe
- Only one additional status of conflic/invalid is allowed. Conflict means there's at least one other block with the same block number and we don't know which one is the "real" one and which ones are the invalid ones.
- For a conflict when we get to know which one is the real one and which ones are the invalid ones, we remove the conflict from the real one (so it only has a status of new/safe/finalized) and we put conflict to all other conflict blocks (i.e. blocks with the same block number but a different block hash)

## Configuration

All config is in `src/main/resources/application.yml` under the `rpc` prefix, bound to `ChainCheckProperties`. The mock profile (`application-mock.yml`) provides fake RPC responses for local development.

## Key Conventions

- All source files carry the Apache 2.0 license header (see `HEADER` file)
- No records or Lombok — plain Java POJOs with getters/setters
- Concurrency handled via `ConcurrentHashMap`, `ConcurrentLinkedDeque`, `AtomicReference`, and `NavigableMap`
- No external HTTP client library — uses `java.net.http.HttpClient` and `java.net.http.WebSocket` directly
- JSON-RPC communication uses Jackson for serialization
