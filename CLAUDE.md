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
- **model** — Value types: `MetricSample` (raw metrics per poll/event), `AnomalyEvent` (with `AnomalyType` enum: ERROR, RATE_LIMIT, TIMEOUT, DELAY, STALE, BLOCK_GAP, CONFLICT, REORG, WRONG_HEAD — note: CONFLICT is never emitted; the dashboard always sets `conflict=false`), `MetricSource` (HTTP vs WS), `TimeRange`, `AttestationConfidence` (per-block attestation tracking data).
- **monitor** — Core monitoring services:
  - `RpcMonitorService` — orchestrator, wires up per-node HTTP/WS monitors, selects reference strategy: `ConfiguredReferenceStrategy` when `rpc.consensus.http` is set (works in **both** ETHEREUM and COSMOS modes), otherwise `VotingReferenceStrategy`
  - `HttpMonitorService` — scheduled polling of `eth_blockNumber`/`eth_getBlockByNumber`; also provides `fetchBlockByHash` used by WS verification
  - `WsMonitorService` — WebSocket `eth_subscribe(newHeads)` for real-time head tracking; Ethereum and Cosmos paths differ significantly (see below)
  - `AnomalyDetector` — evaluates each sample against thresholds, emits `AnomalyEvent`s
  - `NodeRegistry` — maintains the set of monitored nodes (uses a `NodeDefinition` record)
  - `HttpConnectionTracker` / `WsConnectionTracker` — track connection health per node
- **reference/node** — Reference node selection:
  - `ReferenceNodeSelector` — selects best reference node via scoring with `NodeSwitchPolicy` hysteresis
  - `ReferenceStrategy` interface with `ConfiguredReferenceStrategy` (explicit consensus node, used in **both** ETHEREUM and COSMOS modes when `rpc.consensus.http` is configured) and `VotingReferenceStrategy` (majority voting, used when no consensus node is configured)
  - `ConfiguredReferenceSource` / `ConsensusNodeClient` — manage consensus node HTTP client, SSE event stream, and block updates. `ConsensusNodeClient` also drives attestation tracking when enabled: on each `head` SSE event it registers attestation tracking, then a background loop polls committee data and updates `AttestationTracker`.
  - `BlockAgreementTracker` — tracks which nodes agree on blocks
- **reference/attestation** — Attestation-based block confidence scoring:
  - `AttestationTracker` — tracks committee attestation rounds per block, computes confidence percentage (0–100%), stores by block number and hash with 2h retention. Also includes SSZ bitlist parsing utilities (`countSetBits`, `bitlistLength`).
- **reference/block** — Block voting and consensus:
  - `BlockVotingService` — records block votes and performs majority voting
  - `BlockVotingCoordinator` — coordinates voting across all nodes
  - `ReferenceBlocks` — stores voted block hashes by confidence level (NEW, SAFE, FINALIZED)
- **store** — `InMemoryMetricsStore` holds three retention tiers: raw samples (2h), minutely aggregates (3d), daily/monthly aggregates (30d). Uses `SampleAggregate` and `AnomalyAggregate` for rollups. `SampleAggregate` stores histogram data (`long[]`) for latency, head delay, safe delay, and finalized delay using `HistogramAccumulator`'s fixed bucket layout. `HistogramAccumulator` provides histogram-based percentile computation (P5/P95/P99) over 21 predefined buckets (0–60000 ms). Optional on-disk JSON snapshots via persistence config.
- **web** — `DashboardController` + `DashboardService` serve the Thymeleaf dashboard. `MetricsCache` caches responses. `ChartBuilder` generates Chart.js-compatible data using `HistogramAccumulator` for per-bucket percentile computation; produces P5/P95/P99 bounds for latency, head delay, safe delay, and finalized delay. The main chart line is the P95 value; P5 and P99 are rendered as a toggleable band (off by default, labelled "P5–P99 band"). Supporting classes: `DashboardView`, `ReferenceComparison`, `AnomalyRow`/`AnomalyDetails`, `SampleRow` (record), `HttpStatus`/`WsStatus`, `AppVersionProvider`.

### Data Flow

1. HTTP monitors poll RPC endpoints on configurable intervals; WS monitors receive `newHeads` events
2. Samples are stored in `InMemoryMetricsStore` and merged by block hash for a unified block view
3. `AnomalyDetector` evaluates each sample in real-time
4. `ReferenceNodeSelector` establishes consensus head via configured consensus node (when `rpc.consensus.http` is set, in any mode) or majority voting (when no consensus node is configured)
5. When attestation tracking is enabled (Ethereum only), `ConsensusNodeClient` polls committee data on each new head and `AttestationTracker` computes per-block confidence
6. Dashboard queries the store and renders via Thymeleaf + Chart.js; `DashboardService` enriches `SampleRow` records with safe/finalized status from the consensus node (applying it globally by block hash across all nodes) and with attestation confidence data

## Modes

ChainCheck operates in one of two modes set by `rpc.mode`: `ETHEREUM` (default: `COSMOS`).

---

### Ethereum Mode

Intended for Ethereum mainnet/testnets with a beacon chain. A separate consensus node must be configured under `rpc.consensus`.

#### Reference source
The consensus node is the single authoritative source of truth for safe and finalized block hashes. Its observations are applied **globally to all execution nodes** by matching block hash in `DashboardService`. This is in contrast to Cosmos mode where each node tracks its own finality locally.

#### WS newHead path (execution nodes, `source=WS`)

On each `eth_subscribe("newHeads")` event, `WsMonitorService.handleEthereumNewHead` runs:

1. **Immediately** calls `eth_getBlockByHash(event.hash)` on the same execution node via HTTP. This is mandatory because the block number in the newHead event payload is unreliable — the hash is the only trusted identity.
2. The result from `getBlockByHash` provides a reliable `blockNumber`, `parentHash`, `blockTimestamp`, and `blockHash`.
3. Registers the block in `ChainTracker` for parentHash-based reorg detection.
4. Stores a `MetricSample` with `source=WS` containing: `blockHash`, `parentHash`, `blockNumber` (reliable, from `getBlockByHash`), `blockTimestamp`, `headDelayMs` (now − blockTimestamp). No `latencyMs`, `transactionCount`, or `gasPriceWei`.
5. **5 seconds later** (configurable via `rpc.block-verification-delay-ms`, default 5000), calls `eth_getBlockByHash` again via HTTP to create a verification sample with `source=HTTP`: `blockHash`, `parentHash`, `blockNumber`, `blockTimestamp`, `transactionCount`, `gasPriceWei`, `latencyMs` (the HTTP call's round-trip time). This sample does not affect finality status — it only provides a reliable block number and HTTP latency data point.

Block numbers from newHead events MUST NOT be trusted or used as unique identifiers. Block identity is always the hash.

#### HTTP polling path (execution nodes, `source=HTTP`)

`HttpMonitorService` polls each execution node on its configured interval. When WS is active, only finality checkpoint tags are polled (safe and/or finalized, alternating). The resulting `MetricSample` (`source=HTTP`) contains:
- `blockNumber` — from `eth_blockNumber` (used for latency measurement and WS health check)
- `latencyMs` — total round-trip time for the poll cycle
- `blockHash`, `parentHash`, `blockTimestamp`, `transactionCount`, `gasPriceWei` — from `eth_getBlockByNumber("safe"|"finalized")`
- `headDelayMs` — derived from WS freshness: time since last WS block timestamp (only when WS data is fresh)
- `safeDelayMs` — time from when the consensus node first observed the safe block (via `getReferenceObservedAt`) to now. Falls back to block timestamp if not available.
- `finalizedDelayMs` — same pattern as safeDelayMs, anchored to the consensus node's finalized observation time.

#### Consensus node path (beacon node, not per-execution-node)

`ConsensusNodeClient` connects to the beacon node and:
- Subscribes to SSE events: `head` and `finalized_checkpoint`
- Optionally polls `/eth/v1/beacon/states/head/finality_checkpoints` at configurable intervals
- Resolves execution block hashes from beacon block roots
- Stores `ReferenceObservation` history for NEW, SAFE, and FINALIZED confidence levels

The consensus node data is exposed via `ConfiguredReferenceSource.getDelaySamplesSince()`. These are synthetic `MetricSample` objects (`source=HTTP`, `latencyMs=-1`) with `safeDelayMs` or `finalizedDelayMs` set, keyed by block hash. In `DashboardService`, these are used to build `consensusSafeHashes` and `consensusFinalizedHashes` sets. Any execution node sample row whose `blockHash` appears in those sets is tagged as safe or finalized — **regardless of which execution node saw it**. This means the consensus node's finality knowledge is shared across all execution nodes.

#### Attestations (Ethereum only, opt-in)

When `rpc.consensus.attestations-enabled: true`:
- On each `head` SSE event, the slot is registered for attestation tracking
- A background loop polls `/eth/v1/beacon/states/head/committees?slot={slot}` every `attestation-tracking-interval-ms` (default 3s)
- `AttestationTracker` records up to 3 attestation rounds per block, computing confidence as `(round/3) * 100%`
- The dashboard shows attestation round badges (1/2/3) on NEW blocks
- Attestation confidence is also used for orphan detection in `DashboardService`: blocks with 3 attestation rounds are treated as 90% canonical

#### Orphan (invalid block) detection

A block is tagged invalid only when a more trusted canonical block occupies the same parent slot. Specifically, a block B is orphaned if another block C exists such that `C.parentHash == B.parentHash` and C belongs to the canonical chain at the appropriate confidence level:
- **Finalized** blocks are 100% certain: B is invalid if a different finalized block shares B's parentHash
- **Safe** blocks are 99% certain: same logic
- **3-attestation blocks** are 90% certain: same logic
- NewHead and 1–2 attestation round blocks are NOT sufficient to mark another block invalid

Block numbers are never used as the basis for conflict/invalid detection. Only parentHash chain linkage determines validity.

#### Summary — Ethereum mode data sources

| Sample field | WS newHead sample | HTTP verification sample (5s) | HTTP poll sample | Consensus node |
|---|---|---|---|---|
| source | WS | HTTP | HTTP | HTTP (latencyMs=-1) |
| blockHash | from getBlockByHash | from getBlockByHash | from block tag | per observation |
| blockNumber | from getBlockByHash (reliable) | from getBlockByHash | from eth_blockNumber | per observation |
| parentHash | from getBlockByHash | from getBlockByHash | from block tag | — |
| blockTimestamp | from getBlockByHash | from getBlockByHash | from block tag | per observation |
| transactionCount | — | yes | yes | — |
| gasPriceWei | — | yes | yes | — |
| latencyMs | — | yes (HTTP call time) | yes (poll time) | -1 (synthetic) |
| headDelayMs | now − blockTimestamp | — | from WS freshness | yes (head only) |
| safeDelayMs | — | — | yes (per node) | yes (all nodes) |
| finalizedDelayMs | — | — | yes (per node) | yes (all nodes) |

---

### Cosmos Mode (e.g. Polygon)

Used for EVM chains without a beacon chain. By default, finality is tracked per execution node and the reference head is determined by majority voting. Optionally, a consensus node can be configured via `rpc.consensus.http` to serve as the authoritative reference source instead of voting (see below).

#### Reference source
**Default (no consensus node):** Majority voting across all execution nodes. The reference head is the block hash agreed on by the most nodes. Safe and finalized observations are per-node (each node's own HTTP poll determines its own finality).

**Optional (with consensus node):** When `rpc.consensus.http` is set, `ConfiguredReferenceStrategy` is used — the same as in Ethereum mode. The consensus node becomes the authoritative source for head, safe, and finalized blocks, and its observations are applied globally across all execution nodes.

#### WS newHead path (execution nodes, `source=WS`)

On each `eth_subscribe("newHeads")` event, `WsMonitorService.handleCosmosNewHead` runs:

1. **Trusts the event data directly** — no `eth_getBlockByHash` call is made. Block number, hash, parentHash, and timestamp come from the event payload.
2. Stores a `MetricSample` with `source=WS` containing: `blockHash`, `parentHash`, `blockNumber` (from event, may be unreliable), `blockTimestamp`, `headDelayMs` (now − blockTimestamp).
3. No delayed block verification HTTP call. Block numbers from events are used as-is.

#### HTTP polling path (execution nodes, `source=HTTP`)

When WS is active, HTTP polling alternates between safe and finalized block tags (as configured). When WS is not active, HTTP also polls `latest`. The resulting `MetricSample` (`source=HTTP`) contains the same fields as in Ethereum mode, with one key difference: `safeDelayMs` and `finalizedDelayMs` are computed using block timestamp as the baseline (no consensus node observation time is available), and safe/finalized status applies only to that node's own samples.

#### No consensus node, no attestations (default Cosmos mode)

- No beacon node is connected
- No SSE events for finality
- No attestation tracking
- `consensusSafeHashes` and `consensusFinalizedHashes` are always empty in `DashboardService`
- Safe/finalized tagging on sample rows comes only from each node's own HTTP polling results
- The `DashboardService` orphan detection still applies via parentHash chain from each node's finalized/safe samples

#### WRONG_HEAD detection (Cosmos mode)

`BlockVotingCoordinator.emitWrongHeadForOrphanedWsNewHeads` detects nodes that reported a WS newHead block hash that is not part of the canonical chain. Detection is anchored to SAFE or FINALIZED consensus: once the voting majority establishes a canonical hash at a given confidence level, any stored WS newHead sample for the same block number with a different hash is flagged as `WRONG_HEAD`.

This avoids false positives for nodes that are simply lagging (they will catch up and eventually agree on the canonical hash). Only nodes that were genuinely on a fork — where their reported block hash was never confirmed by the network at safe/finalized confidence — are flagged.

#### Summary — Cosmos mode vs Ethereum mode

| Feature | Ethereum mode | Cosmos mode (no consensus) | Cosmos mode (with consensus) |
|---|---|---|---|
| Consensus node | Required for full finality | Not used | Optional (`rpc.consensus.http`) |
| WS newHead: getBlockByHash | Yes (immediate, for reliable block number) | No (trusts event data) | No (trusts event data) |
| WS newHead: delayed HTTP verification | Yes (5s later, `rpc.block-verification-delay-ms`) | No | No |
| Safe blocks | From consensus node (applies to all nodes) | Per execution node (HTTP poll `safe` tag) | From consensus node (applies to all nodes) |
| Finalized blocks | From consensus node (applies to all nodes) | Per execution node (HTTP poll `finalized` tag) | From consensus node (applies to all nodes) |
| safeDelayMs baseline | Consensus node observation time | Block timestamp | Consensus node observation time |
| finalizedDelayMs baseline | Consensus node observation time | Block timestamp | Consensus node observation time |
| Attestations | Optional (beacon committees) | Not available | Not available |
| Orphan detection confidence | Finalized + safe + 3-attest rounds | Finalized + safe |
| Reference head | Consensus node head SSE | Majority vote across execution nodes |
| Block number reliability | Hash is identity; block number verified via getBlockByHash | Block number from event (may be wrong) |

---

## Ethereum newHeads Processing — Architecture Rules (MUST FOLLOW)

1. **Core Principles**
   - `eth_subscribe("newHeads")` is NOT a sequential block stream
   - WebSocket subscriptions are best-effort notifications, not a source of truth
   - Blocks are uniquely identified by block hash, NOT by block number
   - Multiple valid blocks with the same block number may exist (reorgs)
   - Block numbers from newHead events are unreliable and MUST NOT be used as unique identifiers

2. **Assumptions that MUST NOT be made**
   - Block numbers are strictly increasing
   - Each new head equals previous number + 1
   - Every intermediate head event is delivered
   - WebSocket events arrive in order, are unique, or are complete

3. **Source of Truth**
   - Canonical source: `eth_getBlockByHash`, `eth_getBlockByNumber`
   - WS newHead events are wake-up signals only

4. **Event Processing Model**
   - All newHead processing MUST be single-threaded and serialized: WS callback → thread-safe queue → single processing thread
   - Block identity MUST use blockHash as primary key

5. **Reorg Handling**
   - A reorg occurs when `newHead.parentHash != currentCanonicalHead.hash`
   - Walk backwards via parentHash, find common ancestor, roll back old branch, apply new branch
   - Never rely on block number continuity to detect reorgs

6. **Missing Events**
   - MUST tolerate dropped WS events
   - Fetch missing blocks via parent traversal; reconstruct chain from hashes

7. **Validation Rules**
   - MUST validate: `child.parentHash == parent.hash`, block hash integrity, chain connectivity via hashes
   - MUST NOT use: numeric adjacency alone, duplicate block numbers as correctness criteria

8. **Golden Rule**
   - Never derive chain state from WebSocket event order
   - Always derive chain state from block hashes and parent links

9. **Common Anti-Patterns (FORBIDDEN)**
   - Treating newHeads as an append-only stream
   - Using block number as unique key
   - Parallel processing of WS events
   - Raising errors on duplicate heights

## Block Finality Status and Updates

Blocks initially have the status NEW, then SAFE (optionally), and finally FINALIZED.

NEW blocks are streamed via WebSocket's newHeads subscription, if available, otherwise via HTTP polling ('latest').

When ChainCheck learns about SAFE and FINALIZED blocks, status is propagated backward through the parentHash chain:
- **FINALIZED**: tag this block finalized, then walk backward via parentHash tagging each ancestor finalized until reaching an already-finalized block
- **SAFE**: same, stopping at any block already safe or finalized

**In Ethereum mode**, the source of safe/finalized block hashes is the consensus node. Its observations are applied globally — all execution nodes' sample rows for matching block hashes are tagged safe/finalized in `DashboardService`, regardless of which node polled them. The parentHash chain enrichment also spans all execution nodes' samples when a consensus node is configured.

**In Cosmos mode**, each execution node's HTTP polling independently discovers its own safe and finalized blocks. The propagation applies only within that node's own samples.

## Attestation-Based Block Confidence

On Ethereum, SAFE takes ~6.4 min and FINALIZED takes ~12.8 min. Attestation tracking fills this blind spot by querying beacon committee data for each new head and computing a confidence percentage that updates every few seconds.

When `rpc.consensus.attestations-enabled: true` is set:
1. On each `head` SSE event, `ConsensusNodeClient` registers the slot for attestation tracking
2. A background loop polls `/eth/v1/beacon/states/head/committees?slot={slot}` at a configurable interval (`attestation-tracking-interval-ms`, default 3s)
3. `AttestationTracker` records up to 3 attestation rounds per block, computing confidence as `(round/3) * 100%`
4. The dashboard shows attestation round badges (1/2/3) on NEW blocks and the confidence percentage in the sample detail modal

The feature is opt-in and only works in Ethereum mode with a consensus node configured. Configuration options under `rpc.consensus`:
- `attestations-enabled` — enable/disable (default: false)
- `attestations-path` — beacon block attestations endpoint (default: `/eth/v1/beacon/blocks/{slot}/attestations`)
- `committees-path` — beacon committee endpoint (default: `/eth/v1/beacon/states/head/committees`)
- `attestation-tracking-interval-ms` — poll interval for committee data (default: 3000)
- `attestation-tracking-max-attempts` — max poll attempts per slot (default: 100)

## Conflicting and Invalid Blocks

- Only one finality status is allowed per block: NEW < SAFE < FINALIZED (each supersedes the previous)
- A block is marked **invalid** (orphan) when a more confident canonical block occupies the same parent slot — i.e., a different block at the same parentHash is known to be canonical at finalized (100%), safe (99%), or 3-attestation-round (90%) confidence. NewHead and 1–2 attestation round blocks are NOT sufficient to invalidate another block.
- Block number collisions are not a basis for marking a block invalid. Only parentHash chain linkage determines validity.
- **Conflict status** (two competing blocks at the same height where neither is yet resolved) is no longer used; the dashboard always sets `conflict=false`.

## Configuration

All config is in `src/main/resources/application.yml` under the `rpc` prefix, bound to `ChainCheckProperties`. The mock profile (`application-mock.yml`) provides fake RPC responses for local development. The `rpc.consensus` namespace configures the beacon/consensus node connection, finality checkpoint polling, and attestation tracking. Per-node WS gap recovery can be configured via `ws-gap-recovery-enabled` and `ws-gap-recovery-max-blocks` at both the global and per-node level. The `rpc.block-verification-delay-ms` property (default 5000) controls the delay before the post-newHead HTTP block verification call in Ethereum mode.

## Key Conventions

- All source files carry the Apache 2.0 license header (see `HEADER` file)
- No Lombok — plain Java POJOs with getters/setters for model/config classes; records used for value types (e.g., `NodeDefinition`, `ChartData`, `SampleRow`)
- Concurrency handled via `ConcurrentHashMap`, `ConcurrentLinkedDeque`, `ConcurrentSkipListMap`, `AtomicReference`, `AtomicLong`, `volatile` fields, and `NavigableMap`
- No external HTTP client library — uses `java.net.http.HttpClient` and `java.net.http.WebSocket` directly
- JSON-RPC communication uses Jackson for serialization
