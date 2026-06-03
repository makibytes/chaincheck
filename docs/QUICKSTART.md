# Quick Start Guide

## ChainCheck

This guide will help you get ChainCheck up and running in minutes.

## Prerequisites

- Java 25 or higher installed
- Access to one or more blockchain RPC endpoints

### Check Java Version

```bash
java -version
# Should show version 25 or higher
```

## Installation

### Option 1: Run Pre-built JAR (Recommended)

1. Download `chaincheck-*.jar` from the releases page
2. Create an `application.yml` configuration file (see below)
3. Run the application:

```bash
java -jar chaincheck-*.jar
```

### Option 2: Build from Source

```bash
# Clone the repository
git clone https://github.com/makibytes/chaincheck.git
cd chaincheck

# Build the project
./mvnw clean package

# Run the application
java -jar target/chaincheck-*.jar
```

## Configuration

Create an `application.yml` file in the same directory as the JAR:

```yaml
spring:
  application:
    name: ChainCheck

server:
  port: 8080

rpc:
  mode: polygon
  mode-type: cosmos
  title: "Polygon Mainnet"
  title-color: "#8247e5"
  get-safe-blocks: false
  get-finalized-blocks: false
  anomaly-detection:
    high-latency-ms: 2000
  nodes:
    - name: polygon-rpc.com       # defaults to optimal — polls every 2 s
      http: https://polygon-rpc.com
      ws: wss://polygon-rpc.com
```

### Minimal Configuration

The absolute minimum configuration requires just one node:

```yaml
rpc:
  mode-type: cosmos
  nodes:
    - name: my-node
      http: https://polygon-rpc.com
```

## Running the Application

### Standard Mode

```bash
java -jar chaincheck-*.jar
```

### Custom Port

```bash
java -jar chaincheck-*.jar --server.port=9090
```

### Custom Configuration File

```bash
java -jar chaincheck-*.jar --spring.config.location=file:./my-config.yml
```

### With Increased Memory

```bash
java -Xmx1g -jar chaincheck-*.jar
```

## Accessing the Dashboard

Once started, open your browser and navigate to:

```text
http://localhost:8080
```

You should see the ChainCheck dashboard with real-time metrics.

## Docker Deployment (Optional)

Create a `Dockerfile`:

```dockerfile
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY chaincheck-*.jar app.jar
COPY application.yml application.yml
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build and run:

```bash
docker build -t chaincheck:1.0.0 .
docker run -p 8080:8080 chaincheck:1.0.0
```

## Systemd Service (Linux)

Create `/etc/systemd/system/chaincheck.service`:

```ini
[Unit]
Description=ChainCheck Blockchain Node Monitor
After=network.target

[Service]
Type=simple
User=chaincheck
WorkingDirectory=/opt/chaincheck
ExecStart=/usr/bin/java -jar /opt/chaincheck/chaincheck-2.1.0.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl enable chaincheck
sudo systemctl start chaincheck
sudo systemctl status chaincheck
```

## Monitoring Multiple Nodes

To monitor multiple nodes, add them to your configuration:

```yaml
rpc:
  mode-type: cosmos
  get-safe-blocks: false
  get-finalized-blocks: false
  anomaly-detection:
    high-latency-ms: 2000
  nodes:
    - name: Node 1
      http: https://rpc1.example.com
      ws: wss://rpc1.example.com

    - name: Node 2 (rate-limited public endpoint)
      http: https://rpc2.example.com
      requests: sparse             # polls at sparse-poll-interval-ms instead of optimal
```

Use the dropdown in the dashboard to switch between nodes.

## Troubleshooting

### Application won't start

Check if port 8080 is already in use:

```bash
lsof -i :8080
```

Use a different port if needed:

```bash
java -jar chaincheck-1.0.0.jar --server.port=9090
```

### No data appearing in dashboard

1. Check your RPC endpoint is accessible:

```bash
curl -X POST https://your-rpc-endpoint \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
```

1. Check application logs for connection errors

1. Verify your configuration file is loaded correctly

### High memory usage

Adjust the retention period or reduce polling frequency:

```yaml
rpc:
  requests:
    optimal-poll-interval-ms: 5000  # Slower polling
  nodes:
    - name: my-node
      http: https://your-rpc-endpoint
      requests: sparse               # Or use sparse profile for even less frequent polling
```

## Next Steps

- Explore the dashboard features
- Set up anomaly thresholds for your use case
- Configure multiple nodes for comparison
- See the project README for full configuration reference and feature documentation

## Support

- **Documentation**: README.md
- **Issues**: GitHub Issues
- **License**: Apache 2.0

Enjoy monitoring your blockchain nodes! 🚀
