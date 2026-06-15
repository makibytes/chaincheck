/*
 * Copyright (c) 2026 MakiBytes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.makibytes.chaincheck.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.makibytes.chaincheck.model.TimeRange;
import de.makibytes.chaincheck.monitor.NodeRegistry;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;

/**
 * Exposes per-node monitoring data as Micrometer gauges so operators can scrape ChainCheck
 * into their own Prometheus/Grafana/Alertmanager stack via {@code /actuator/prometheus}.
 *
 * <p>Design: one gauge per (metric, node) pair is registered once at startup, each reading
 * from an atomically-replaced snapshot map. A scheduled refresh recomputes the fleet view and
 * swaps the snapshot, so gauge reads are lock-free and always observe a consistent set of
 * values. Nodes are fixed at startup (driven by configuration), so no dynamic (de)registration
 * is needed.
 *
 * <p>The metrics are derived from the same {@link FleetView} the dashboard renders, keeping the
 * scraped values identical to what an operator sees in the UI.
 */
@Component
public class PrometheusMetricsExporter {

    /** Fleet view window used for the exported gauges (matches the dashboard default). */
    private static final TimeRange EXPORT_RANGE = TimeRange.HOURS_2;

    private final DashboardService dashboardService;
    private final NodeRegistry nodeRegistry;
    private final MeterRegistry meterRegistry;

    /** Latest per-node snapshot, replaced wholesale on each refresh. */
    private volatile Map<String, NodeMetrics> snapshot = Map.of();

    public PrometheusMetricsExporter(DashboardService dashboardService,
                                     NodeRegistry nodeRegistry,
                                     MeterRegistry meterRegistry) {
        this.dashboardService = dashboardService;
        this.nodeRegistry = nodeRegistry;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerGauges() {
        // Prime the snapshot so the first scrape (which may precede the first scheduled
        // refresh) reports real values rather than zeros.
        refresh();
        for (NodeRegistry.NodeDefinition node : nodeRegistry.getNodes()) {
            String key = node.key();
            registerGauge("chaincheck_node_health_score", key, node.name(),
                    "Composite node health score 0-100", m -> m.healthScore());
            registerGauge("chaincheck_node_up", key, node.name(),
                    "Node reachable over HTTP (1) or not (0)", m -> m.httpUp() ? 1.0 : 0.0);
            registerGauge("chaincheck_node_ws_up", key, node.name(),
                    "Node WebSocket connected (1) or not (0)", m -> m.wsUp() ? 1.0 : 0.0);
            registerGauge("chaincheck_node_block_lag_blocks", key, node.name(),
                    "Blocks this node is behind the fleet's highest block", m -> m.blockLag());
            registerGauge("chaincheck_node_latency_p95_ms", key, node.name(),
                    "P95 request latency in milliseconds", m -> m.p95LatencyMs());
            registerGauge("chaincheck_node_head_delay_p95_ms", key, node.name(),
                    "P95 head-delay in milliseconds", m -> m.p95HeadDelayMs());
            registerGauge("chaincheck_node_anomalies_total", key, node.name(),
                    "Anomalies recorded in the export window", m -> m.anomalyCount());
            registerGauge("chaincheck_node_ws_disconnects_total", key, node.name(),
                    "WebSocket disconnects in the export window", m -> m.wsDisconnects());
            registerGauge("chaincheck_node_latest_block", key, node.name(),
                    "Latest block number observed from this node", m -> m.latestBlock());
            registerGauge("chaincheck_node_reference", key, node.name(),
                    "Whether this node is the current reference (1) or not (0)",
                    m -> m.reference() ? 1.0 : 0.0);
        }
    }

    private void registerGauge(String name, String nodeKey, String nodeName, String help,
                               java.util.function.ToDoubleFunction<NodeMetrics> extractor) {
        Gauge.builder(name, this, self -> {
                    NodeMetrics m = self.snapshot.get(nodeKey);
                    return m == null ? Double.NaN : extractor.applyAsDouble(m);
                })
                .description(help)
                .tag("node", nodeKey)
                .tag("node_name", nodeName)
                .register(meterRegistry);
    }

    /** Recompute the fleet view and publish a fresh snapshot for the gauges to read. */
    @Scheduled(fixedDelayString = "${chaincheck.metrics.refresh-interval-ms:15000}")
    void refresh() {
        try {
            FleetView fleet = dashboardService.getFleetView(EXPORT_RANGE, null);
            Map<String, NodeMetrics> next = new ConcurrentHashMap<>();
            for (FleetNodeSummary n : fleet.getNodes()) {
                next.put(n.nodeKey(), new NodeMetrics(
                        n.healthScore(),
                        n.httpUp(),
                        n.wsUp(),
                        n.blockLagBlocks(),
                        n.p95LatencyMs(),
                        n.p95HeadDelayMs(),
                        n.anomalyCount(),
                        n.wsDisconnectCount(),
                        n.latestBlockNumber() == null ? Double.NaN : n.latestBlockNumber(),
                        n.referenceNode()));
            }
            this.snapshot = next;
        } catch (RuntimeException ex) {
            // Never let a transient computation error kill the scheduler; keep the last
            // good snapshot and try again on the next tick.
        }
    }

    /** Immutable per-node value snapshot read by the registered gauges. */
    private record NodeMetrics(int healthScore,
                               boolean httpUp,
                               boolean wsUp,
                               double blockLag,
                               double p95LatencyMs,
                               double p95HeadDelayMs,
                               double anomalyCount,
                               double wsDisconnects,
                               double latestBlock,
                               boolean reference) {
    }
}
