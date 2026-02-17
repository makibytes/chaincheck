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

Integration tests use the `IT` suffix (e.g., `LargeDataStoreIntegrationTestIT`) and run during `mvn verify`. Additional profiles: `application-ethereum.yml`, `application-polygon.yml`, `application-test.yml`.

## Architecture

All source lives under `de.makibytes.chaincheck` with eight packages:

- **config** — `ChainCheckProperties` binds the `rpc.*` YAML namespace. Per-node overrides (timeouts, retries, headers, anomaly thresholds) fall back to `rpc.defaults.*`.
- **model** — Value types: `MetricSample` (raw metrics per poll/event), `AnomalyEvent` (with `AnomalyType` enum: ERROR, RATE_LIMIT, TIMEOUT, DELAY, BLOCK_GAP, CONFLICT, REORG, WRONG_HEAD), `MetricSource` (HTTP vs WS), `TimeRange`, `AttestationConfidence` (per-block attestation tracking data).
- **monitor** — Core monitoring services:
  - `RpcMonitorService` — orchestrator, wires up per-node HTTP/WS monitors
  - `HttpMonitorService` — scheduled polling of `eth_blockNumber`/`eth_getBlockByNumber`
  - `WsMonitorService` — WebSocket `eth_subscribe(newHeads)` for real-time head tracking
  - `AnomalyDetector` — evaluates each sample against thresholds, emits `AnomalyEvent`s
  - `NodeRegistry` — maintains the set of monitored nodes (uses a `NodeDefinition` record)
  - `HttpConnectionTracker` / `WsConnectionTracker` — track connection health per node
- **reference/node** — Reference node selection:
  - `ReferenceNodeSelector` — selects best reference node via scoring with `NodeSwitchPolicy` hysteresis
  - `ReferenceStrategy` interface with `ConfiguredReferenceStrategy` (explicit consensus node) and `VotingReferenceStrategy` (majority voting)
  - `ConfiguredReferenceSource` / `ConsensusNodeClient` — manage consensus node HTTP client, SSE event stream, and block updates. `ConsensusNodeClient` also drives attestation tracking when enabled: on each `head` SSE event it registers attestation tracking, then a background loop polls committee data and updates `AttestationTracker`.
  - `BlockAgreementTracker` — tracks which nodes agree on blocks
- **reference/attestation** — Attestation-based block confidence scoring:
  - `AttestationTracker` — tracks committee attestation rounds per block, computes confidence percentage (0–100%), stores by block number and hash with 2h retention. Also includes SSZ bitlist parsing utilities (`countSetBits`, `bitlistLength`).
- **reference/block** — Block voting and consensus:
  - `BlockVotingService` — records block votes and performs majority voting
  - `BlockVotingCoordinator` — coordinates voting across all nodes
  - `ReferenceBlocks` — stores voted block hashes by confidence level (NEW, SAFE, FINALIZED)
- **store** — `InMemoryMetricsStore` holds three retention tiers: raw samples (2h), minutely aggregates (3d), daily/monthly aggregates (30d). Uses `SampleAggregate` and `AnomalyAggregate` for rollups. Optional on-disk JSON snapshots via persistence config.
- **web** — `DashboardController` + `DashboardService` serve the Thymeleaf dashboard. `MetricsCache` caches responses. `ChartBuilder` generates Chart.js-compatible data. Supporting classes: `DashboardView`, `ReferenceComparison`, `AnomalyRow`/`AnomalyDetails`, `SampleRow` (record), `HttpStatus`/`WsStatus`, `AppVersionProvider`.

### Data Flow

1. HTTP monitors poll RPC endpoints on configurable intervals; WS monitors receive `newHeads` events
2. Samples are stored in `InMemoryMetricsStore` and merged by block hash for a unified block view
3. `AnomalyDetector` evaluates each sample in real-time
4. `ReferenceNodeSelector` establishes consensus head via configured consensus node or majority voting
5. When attestation tracking is enabled, `ConsensusNodeClient` polls committee data on each new head and `AttestationTracker` computes per-block confidence
6. Dashboard queries the store and renders via Thymeleaf + Chart.js; `DashboardService` enriches `SampleRow` records with attestation confidence data

## Reference Node

### By Configuration

You can configure a specific consensus node as the reference source for safe/finalized comparisons.

### By Majority Voting

When not explicitly configured, the reference node is selected via majority voting across all monitored nodes.

## Block Finality Status and Updates

Blocks initially have the status NEW, then SAFE (optionally), and finally FINALIZED. NEW blocks are streamed via WebSocket's newHeads subscription, if available, otherwise via HTTP polling ('latest'). SAFE and FINALIZED blocks can also be polled via HTTP, but that's not the preferred method for all chains. On Ethereum you should configure a consensus node as reference and get the SAFE and FINALIZED status from there.

When ChainCheck learns about SAFE and FINALIZED blocks the status updates are triggered by the following rules:

- First time we get to know about a FINALIZED block: tag this block finalized and go back through the blockchain (block-by-block by the parent hash), tag each of the blocks you go through finalized as well, until you encounter a block that has already been tagged finalized, then stop
- First time we get to know about a SAFE block: tag this block safe and go back through the blockchain (block-by-block by the parent hash), tag each of the blocks you go through safe as well, until you encounter a block that has already been tagged safe or finalized, then stop

When HTTP polling is used, these rules apply for the current node's samples only. When a consensus node is configured, these rules apply to all nodes' samples.

## Attestation-Based Block Confidence

On Ethereum, SAFE takes ~6.4 min and FINALIZED takes ~12.8 min. Attestation tracking fills this blind spot by querying beacon committee data for each new head and computing a confidence percentage that updates every few seconds.

When `rpc.consensus.attestations-enabled: true` is set:
1. On each `head` SSE event, `ConsensusNodeClient` registers the slot for attestation tracking
2. A background loop polls `/eth/v1/beacon/states/head/committees?slot={slot}` at a configurable interval (`attestation-tracking-interval-ms`, default 3s)
3. `AttestationTracker` records up to 3 attestation rounds per block, computing confidence as `(round/3) * 100%`
4. The dashboard shows attestation round badges (1/2/3) on NEW blocks and the confidence percentage in the sample detail modal

The feature is opt-in and only works in configured reference mode (consensus node required). Configuration options under `rpc.consensus`:
- `attestations-enabled` — enable/disable (default: false)
- `attestations-path` — beacon block attestations endpoint (default: `/eth/v1/beacon/blocks/{slot}/attestations`)
- `committees-path` — beacon committee endpoint (default: `/eth/v1/beacon/states/head/committees`)
- `attestation-tracking-interval-ms` — poll interval for committee data (default: 3000)
- `attestation-tracking-max-attempts` — max poll attempts per slot (default: 100)

## Conflicting and Invalid Blocks

- Only one status of new/safe/finalized is allowed. New blocks are new, status=safe replaces status=new and status=finalized replaces both, status=new and status=safe
- Only one additional status of conflict/invalid is allowed. Conflict means there's at least one other block with the same block number and we don't know which one is the "real" one and which ones are the invalid ones.
- For a conflict when we get to know which one is the real one and which ones are the invalid ones, we remove the conflict from the real one (so it only has a status of new/safe/finalized) and we mark all other blocks with the same block number but a different block hash as invalid

## Configuration

All config is in `src/main/resources/application.yml` under the `rpc` prefix, bound to `ChainCheckProperties`. The mock profile (`application-mock.yml`) provides fake RPC responses for local development. The `rpc.consensus` namespace configures the beacon/consensus node connection, finality checkpoint polling, and attestation tracking. Per-node WS gap recovery can be configured via `ws-gap-recovery-enabled` and `ws-gap-recovery-max-blocks` at both the global and per-node level.

## Key Conventions

- All source files carry the Apache 2.0 license header (see `HEADER` file)
- No Lombok — plain Java POJOs with getters/setters for model/config classes; records used for value types (e.g., `NodeDefinition`, `ChartData`, `ChainNode`, `SampleRow`)
- Concurrency handled via `ConcurrentHashMap`, `ConcurrentLinkedDeque`, `ConcurrentSkipListMap`, `AtomicReference`, `AtomicLong`, `volatile` fields, and `NavigableMap`
- No external HTTP client library — uses `java.net.http.HttpClient` and `java.net.http.WebSocket` directly
- JSON-RPC communication uses Jackson for serialization
