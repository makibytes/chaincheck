# ChainCheck

**ChainCheck** is a comprehensive Ethereum (and EVM-compatible) blockchain node monitoring solution designed to provide real-time insights into the performance, stability, and finality status of HTTP and WebSocket connections to RPC nodes.

![Dashboard Screenshot](docs/screenshot.png)

## Features

- **Real-time Monitoring**: Tracks latency and error rates for both HTTP and WebSocket interfaces
- **Checkpoint Propagation Delays**: Monitors head, safe, and finalized block delays to track blockchain finality
- **Protocol Comparison**: Visualizes differences between HTTP and WebSocket performance side-by-side
- **Anomaly Detection**: Automatically flags block skips, reorgs, high latency, and connection drops
- **Block Finality Tracking**: Tags blocks as safe or finalized based on actual RPC queries with backward propagation
- **Unified Block View**: Merges WebSocket and HTTP samples by block hash for complete block lifecycle tracking
- **Metric Aggregation**: Efficiently stores raw samples (2 hours) and aggregated metrics (30 days) for long-term analysis
- **Responsive Dashboard**: Modern web interface built with Thymeleaf and Chart.js
- **Multi-Node Support**: Monitor multiple RPC endpoints and easily switch between them
- **Reference Consensus**: Automatically selects a reference head via node majority; when many nodes stall, it trusts the majority of nodes still emitting `newHeads`
- **Pluggable Storage**: In-memory by default with optional on-disk snapshotting for persistence across restarts

## Getting Started

### Supported Chains

ChainCheck targets EVM chains that expose `eth_blockNumber`, `eth_getBlockByNumber`, and `eth_subscribe` (newHeads). Finality tracking (safe/finalized) is accurate on chains that surface those tags (e.g., Ethereum mainnet, Sepolia, Holesky, recent Geth/Nethermind). On networks without safe/finalized tags, the dashboard still works but finalized/safe delay charts remain empty.

### Prerequisites

- Java 21 or higher
- Maven

### Building the Application

```bash
mvn clean install
```

### Running the Application

```bash
mvn spring-boot:run
```

The dashboard will be available at `http://localhost:8080`.

## Documentation

- [docs/QUICKSTART.md](docs/QUICKSTART.md)
- [docs/ANOMALIES.md](docs/ANOMALIES.md)

## Configuration

Configuration is managed via `application.yml`. Example configuration:

```yaml
spring:
  application:
    name: ChainCheck

server:
  port: 8080

rpc:
  title: "Polygon Mainnet"  # Optional: Dashboard title
  title-color: "#8247e5"     # Optional: Title color
  defaults:
    connect-timeout-ms: 2000
    read-timeout-ms: 4000
    max-retries: 1
    retry-backoff-ms: 200
  persistence:
    enabled: false
    file: ./chaincheck-snapshot.json
    flush-interval-seconds: 30
  anomaly-detection:
    high-latency-ms: 2000
    long-delay-block-count: 15
  scale-change-ms: 500
  scale-max-ms: 30000
  nodes:
    - name: polygon-rpc.com
      http: https://polygon-rpc.com
      ws: wss://polygon-rpc.com            # Optional: WebSocket endpoint
      poll-interval-ms: 3000               # HTTP polling interval (milliseconds)
      safe-blocks-enabled: false           # Only enable when available (e.g. on Ethereum)
      headers:                             # Optional: custom headers / auth
        Authorization: "Bearer <token>"
      read-timeout-ms: 4000                # Optional per-node override
      max-retries: 1
```

### Configuration Options

- **poll-interval-ms**: How often to poll the HTTP endpoint (default: 3000ms)
- **anomaly-detection.high-latency-ms**: Threshold for flagging high latency anomalies (default: 2000ms)
- **nodes[].anomaly-detection.high-latency-ms**: Optional per-node override for high latency anomalies
- **scale-change-ms**: Threshold (ms) used as the low band limit when scaling chart values; values below this are mapped into a low band for better visual resolution (default: 500ms)
- **scale-max-ms**: Maximum Y-axis scale in milliseconds used to clamp chart max delay and avoid extremely large spikes on the charts (default: 30000ms)
- **safe-blocks-enabled**: When true, HTTP queries alternate between safe and finalized blocks
- **http**: HTTP RPC endpoint URL (required)
- **ws**: WebSocket RPC endpoint URL (optional, enables real-time head block tracking)
- **headers**: Map of custom headers (useful for API keys and auth)
- **connect-timeout-ms / read-timeout-ms**: HTTP connect and read timeouts (defaults: connect 2000ms, read 4000ms)
- **max-retries / retry-backoff-ms**: Retry attempts and base backoff for transient HTTP failures (defaults: 1 retry, 200ms backoff)
- **Reference selection**: Reference head is chosen automatically by majority; when many nodes are stale, only nodes still emitting newHeads are trusted and their majority is used
- **persistence.enabled/file/flush-interval-seconds**: Enable simple on-disk snapshots of recent data

## Development

We welcome contributions!

### Contributing

1. Fork the repository.
2. Create a feature branch (`git checkout -b feature/amazing-feature`).
3. Commit your changes (`git commit -m 'Add some amazing feature'`).
4. Push to the branch (`git push origin feature/amazing-feature`).
5. Open a Pull Request.

### Project Structure

The project is organized into the following packages under `de.makibytes.chaincheck`:

- `config`: Configuration classes.
- `model`: Domain models (MetricSample, AnomalyEvent).
- `monitor`: Core monitoring logic (RpcMonitorService, AnomalyDetector).
- `store`: In-memory data storage and aggregation.
- `web`: Web controllers and dashboard logic.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

SPDX-License-Identifier: Apache-2.0
