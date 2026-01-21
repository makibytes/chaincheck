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

## Getting Started

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
  nodes:
    - name: polygon-rpc.com
      http: https://polygon-rpc.com
      ws: wss://polygon-rpc.com  # Optional: WebSocket endpoint
      poll-interval-ms: 1000     # HTTP polling interval (milliseconds)
      anomaly-delay-ms: 2000     # Threshold for delay anomalies (milliseconds)
      safe-blocks-enabled: false # Only enable when available (e.g. on Ethereum)
```

### Configuration Options

- **poll-interval-ms**: How often to poll the HTTP endpoint (default: 1000ms)
- **anomaly-delay-ms**: Threshold for flagging delay anomalies (default: 2000ms)
- **safe-blocks-enabled**: When true, HTTP queries alternate between safe and finalized blocks
- **http**: HTTP RPC endpoint URL (required)
- **ws**: WebSocket RPC endpoint URL (optional, enables real-time head block tracking)

## Development

We welcome contributions!

### Contributing

1.  Fork the repository.
2.  Create a feature branch (`git checkout -b feature/amazing-feature`).
3.  Commit your changes (`git commit -m 'Add some amazing feature'`).
4.  Push to the branch (`git push origin feature/amazing-feature`).
5.  Open a Pull Request.

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
