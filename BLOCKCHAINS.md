# Supported Blockchains

ChainCheck ships with Spring Boot profile YAML files for the following blockchains. Activate a profile with `--spring.profiles.active=<profile>` (or set `SPRING_PROFILES_ACTIVE`).

> For the node list, polling intervals, and detailed configuration of each profile, open the corresponding `application-<profile>.yml` file in `src/main/resources/`.

---

## Mode Types

Each blockchain profile sets two fields:

| Field | Type | Purpose |
|-------|------|---------|
| `rpc.mode` | `String` | Concrete chain name — informational only (displayed in logs/UI) |
| `rpc.mode-type` | `ModeType` enum | Behavioral chain type — controls WS newHead processing and polling defaults |

The `ModeType` values and their behaviours:

| Mode Type | Chains | WS newHead handling | Protocol |
|-----------|--------|---------------------|----------|
| `ETHEREUM` | Ethereum | Calls `eth_getBlockByHash` immediately after newHeads; block number from event is **discarded**; requires a consensus (beacon) node for full finality | EVM JSON-RPC |
| `COSMOS` | Polygon, BNB Chain, and similar PoS chains | Trusts event payload directly; majority-vote reference; per-node finality | EVM JSON-RPC |
| `OPTIMISM` | Base, Optimism, Arbitrum | Trusts event payload directly; L2 sequencer guarantees sequential delivery | EVM JSON-RPC |
| `ZK` | zkSync Era | Trusts event payload directly; ZK-proof finality model | EVM JSON-RPC |
| `AVALANCHE` | Avalanche C-Chain | Trusts event payload directly; Snowman consensus; near-instant finality | EVM JSON-RPC |
| `TRON` | Tron | Trusts event payload directly; Ethereum-compatible JSON-RPC via TronGrid | EVM JSON-RPC |
| `SOLANA` | Solana | `slotSubscribe` WS → follow-up `getBlock` HTTP per slot; base58 hashes; `confirmed`/`finalized` commitment levels | Solana JSON-RPC |
| `COSMOS_SDK` | Cosmos Hub, Osmosis, CometBFT chains | Full block in WS NewBlock event; no extra HTTP fetch; BFT instant finality | CometBFT RPC (HTTP GET) |
| `STARKNET` | Starknet | `starknet_subscribeNewHeads` WS; full header in event; `starknet_*` JSON-RPC; integer block numbers | Starknet JSON-RPC |

---

## Request Profiles

HTTP polling intervals are configured at the **profile level** (not per node) via the `rpc.requests` block:

```yaml
rpc:
  requests:
    optimal-poll-interval-ms: 2000   # 1 poll per block — enough for all metrics
    sparse-poll-interval-ms: 30000   # For rate-limited public nodes
```

Each node selects its request profile:

```yaml
nodes:
  - name: My Private Node    # defaults to optimal
    http: http://my-node:8545

  - name: Public Node
    http: https://...
    requests: sparse          # rate-limited — poll every 30 s
```

---

## Mainnet Profiles

| Profile | Chain | Mode Type | Block Time | Optimal Poll | Sparse Poll | Spring Profile |
|---------|-------|-----------|------------|-------------|-------------|----------------|
| `ethereum` | Ethereum Mainnet | `ETHEREUM` | ~12 s | 12 s | 60 s | `--spring.profiles.active=ethereum` |
| `polygon` | Polygon Mainnet | `COSMOS` | ~2 s | 2 s | 30 s | `--spring.profiles.active=polygon` |
| `bnb` | BNB Smart Chain | `COSMOS` | ~0.45 s | 1 s | 30 s | `--spring.profiles.active=bnb` |
| `base` | Base Mainnet | `OPTIMISM` | ~2 s | 2 s | 30 s | `--spring.profiles.active=base` |
| `optimism` | Optimism Mainnet | `OPTIMISM` | ~2 s | 2 s | 30 s | `--spring.profiles.active=optimism` |
| `arbitrum` | Arbitrum One | `OPTIMISM` | ~0.25 s | 1 s | 30 s | `--spring.profiles.active=arbitrum` |
| `zksync` | zkSync Era | `ZK` | ~1–2 s | 2 s | 30 s | `--spring.profiles.active=zksync` |
| `starknet` | Starknet Mainnet | `STARKNET` | ~4–6 s | 5 s | 30 s | `--spring.profiles.active=starknet` |
| `solana` | Solana Mainnet-Beta | `SOLANA` | ~0.4 s | 0.4 s | 5 s | `--spring.profiles.active=solana` |
| `cosmos` | Cosmos Hub | `COSMOS_SDK` | ~6 s | 6 s | 30 s | `--spring.profiles.active=cosmos` |
| `avalanche` | Avalanche C-Chain | `AVALANCHE` | ~2 s | 2 s | 30 s | `--spring.profiles.active=avalanche` |
| `tron` | Tron Mainnet | `TRON` | ~3 s | 3 s | 30 s | `--spring.profiles.active=tron` |

---

## Testnet Profiles

| Profile | Chain | Parent Mainnet | Spring Profile |
|---------|-------|----------------|----------------|
| `ethereum-sepolia` | Ethereum Sepolia | `ethereum` | `--spring.profiles.active=ethereum-sepolia` |
| `polygon-amoy` | Polygon Amoy | `polygon` | `--spring.profiles.active=polygon-amoy` |
| `bnb-testnet` | BNB Smart Chain Chapel | `bnb` | `--spring.profiles.active=bnb-testnet` |
| `base-sepolia` | Base Sepolia | `base` | `--spring.profiles.active=base-sepolia` |
| `optimism-sepolia` | Optimism Sepolia | `optimism` | `--spring.profiles.active=optimism-sepolia` |
| `arbitrum-sepolia` | Arbitrum Sepolia | `arbitrum` | `--spring.profiles.active=arbitrum-sepolia` |
| `zksync-sepolia` | zkSync Sepolia | `zksync` | `--spring.profiles.active=zksync-sepolia` |
| `starknet-sepolia` | Starknet Sepolia | `starknet` | `--spring.profiles.active=starknet-sepolia` |
| `solana-devnet` | Solana Devnet | `solana` | `--spring.profiles.active=solana-devnet` |
| `avalanche-fuji` | Avalanche Fuji | `avalanche` | `--spring.profiles.active=avalanche-fuji` |
| `tron-shasta` | Tron Shasta | `tron` | `--spring.profiles.active=tron-shasta` |

---

## Notes

### EVM Node Health (all EVM chains)

On every HTTP poll ChainCheck issues `eth_syncing` — the EVM analogue of Solana's `getHealth`. `false` means the node is fully synced; an object reports `currentBlock`/`highestBlock`, and the difference (blocks behind) drives a `SYNC_LAG` anomaly when it meets `anomaly-detection.health-slots-behind-threshold`. The anomaly is opened once and closed on recovery (not re-raised every poll), so a node doing a long initial sync produces a single event. On the slow metadata cadence (~60 s) `web3_clientVersion` is recorded and condensed to `client/version` (e.g. `Geth/v1.13.5`) for fleet version-skew detection. This covers every EVM mode type: `ETHEREUM`, `COSMOS` (Polygon/BNB), `OPTIMISM`, `ZK`, `AVALANCHE`, and `TRON`.

### Starknet

Starknet uses the **`starknet_*` JSON-RPC protocol** (`starknet_blockNumber`, `starknet_getBlockWithTxHashes`, etc.). ChainCheck's `StarknetProtocol` adapter handles this natively. Block numbers are plain integers (not hex). Since v0.14 ("Grinta", September 2025) Starknet produces blocks every ~4–6 s under decentralized Tendermint sequencing (previously ~30 s) — the bundled profile polls at 5 s with a 60 s stale threshold to absorb the network's documented block-time jitter and sequencer stalls. Finality stages: `PRE_CONFIRMED` (v0.14+) → `ACCEPTED_ON_L2` (default, ~seconds) → `ACCEPTED_ON_L1` (hours; not tracked by default). WebSocket subscriptions (`starknet_subscribeNewHeads`) are supported on Pathfinder and Juno nodes; HTTP polling is used as fallback.

### Solana

Solana uses `slotSubscribe` WebSocket subscriptions (not `eth_subscribe`). ChainCheck also issues `getHealth` on every HTTP poll (piggybacked on the existing batch, so no extra round-trip): a healthy node returns `ok`, while a lagging node returns error `-32005` with `data.numSlotsBehind`. When a node reports itself behind the cluster tip by at least `health-slots-behind-threshold` slots, a `SYNC_LAG` anomaly is recorded — a direct node-self-reported liveness signal that other chains can only approximate through cross-node comparison. Each slot notification triggers a follow-up `getBlock` HTTP call. ChainCheck requests `transactionDetails: "signatures"` with `rewards: false`, keeping each response to a few KB (full transaction JSON on mainnet blocks runs into megabytes) while still deriving the transaction count from the signatures array. Block hashes are base58-encoded (not hex). On a slow (~60 s) cadence ChainCheck also issues `getVersion` and `getRecentPerformanceSamples`: the former surfaces each node's `solana-core` software version (for spotting fleet version skew), the latter the node's observed network TPS and mean slot time (validating the ~400 ms slot assumption and flagging a node whose view diverges from its peers). Both are stored as slow-changing node metadata rather than in the high-frequency sample stream. Commitment levels map to ChainCheck tags: `processed` = latest, `confirmed` = safe (~1.6 s), `finalized` = finalized (~13 s).

### Cosmos Hub / CometBFT Chains

Cosmos SDK chains use CometBFT's **HTTP GET RPC** (`GET /status`, `GET /block?height=N`) instead of JSON-RPC POST. WebSocket subscriptions use CometBFT's `subscribe` method with the query `tm.event='NewBlock'`. Block hashes are uppercase hex without `0x` prefix (stored lowercase in ChainCheck). BFT consensus provides instant finality — every committed block is final, so `get-safe-blocks` and `get-finalized-blocks` should be `false`. The WS URL must include the `/websocket` path suffix (e.g., `wss://my-node:26657/websocket`).

### Tron

Tron exposes an Ethereum-compatible JSON-RPC interface via [TronGrid](https://developers.tron.network/docs/json-rpc). ChainCheck is fully compatible. The free tier of `api.trongrid.io` is rate-limited; configure your nodes with `requests: sparse` or supply a TronGrid API key via `headers`.

### BNB Smart Chain

BNB Smart Chain runs the `COSMOS` mode type (trust-the-event PoS behavior, same as Polygon). The Fermi hardfork (January 2026) reduced block time to ~0.45 s, following Maxwell's 0.75 s (June 2025) and Lorentz's 1.5 s (April 2025) — the bundled profile polls at 1 s as a public-RPC compromise; lower `optimal-poll-interval-ms` only against your own node. Fast Finality means the `finalized` tag is supported and lands within a few seconds; `get-finalized-blocks` is enabled by default, `get-safe-blocks` is off to spare rate-limited endpoints.

### Arbitrum

Arbitrum One produces blocks every ~0.25 s, but the `optimal-poll-interval-ms` is set to 1 s to avoid hammering public endpoints. Adjust it lower only if you control your own Arbitrum node.

### Adding a Custom Blockchain

1. Copy the YAML of the nearest mode type (e.g., copy `application-polygon.yml` for a new COSMOS chain)
2. Set `rpc.mode` to your chain name (e.g., `"bnb"`)
3. Set `rpc.mode-type` to the matching enum value
4. Tune `rpc.requests.optimal-poll-interval-ms` to the chain's block time
5. Add your node endpoints under `rpc.nodes`
6. Activate with `--spring.profiles.active=<your-profile>`
