# ChainCheck

**ChainCheck** is a comprehensive Ethereum and Polygon node monitoring solution designed to provide real-time insights into the performance and stability of your RPC nodes.

![Dashboard Screenshot](docs/screenshot_placeholder.png)

*Note: Screenshot to be updated.*

## Features

- **Real-time Monitoring**: Tracks latency and error rates for both HTTP and WebSocket interfaces.
- **Protocol Comparison**: Visualizes differences between HTTP and WebSocket performance side-by-side.
- **Anomaly Detection**: Automatically flags block skips, high latency, and connection drops.
- **Metric Aggregation**: Efficiently stores and aggregates metrics over time using `InMemoryMetricsStore`.
- **Dashboard**: A responsive web interface built with Thymeleaf and Chart.js.
- **Multi-Node Support**: Easily switch between monitored nodes (e.g., Ethereum Mainnet, Polygon).

## Getting Started

### Prerequisites

- Java 25 or higher
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

Configuration is managed via `application.properties`. You can define your nodes and monitoring intervals there.

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
