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
package de.makibytes.chaincheck.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rpc")
public class ChainCheckProperties {

    public enum Mode {
        ETHEREUM,
        COSMOS
    }

    public enum ChartGradientMode {
        LATENCY,
        NEWHEAD,
        NONE
    }

    public enum SparklineDataSource {
        LATENCY,
        NEWHEAD
    }

    private String title = "";
    private String titleColor = "white";
    private Mode mode = Mode.COSMOS;
    private boolean getSafeBlocks = false;
    private boolean getFinalizedBlocks = false;
    private boolean wsGapRecoveryEnabled = false;
    private int wsGapRecoveryMaxBlocks = 5;
    private int scaleChangeMs = 500;
    private int scaleMaxMs = 30000;
    private ChartGradientMode chartGradientMode = ChartGradientMode.NONE;
    private SparklineDataSource sparklineDataSource = SparklineDataSource.LATENCY;
    private long blockVerificationDelayMs = 5000;
    private Consensus consensus = new Consensus();
    private Defaults defaults = new Defaults();
    private Persistence persistence = new Persistence();
    private AnomalyDetection anomalyDetection = new AnomalyDetection();
    private List<RpcNodeProperties> nodes = new ArrayList<>();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTitleColor() {
        return titleColor;
    }

    public void setTitleColor(String titleColor) {
        this.titleColor = titleColor;
    }

    public boolean isGetSafeBlocks() {
        return getSafeBlocks;
    }

    public void setGetSafeBlocks(boolean getSafeBlocks) {
        this.getSafeBlocks = getSafeBlocks;
    }

    public boolean isGetFinalizedBlocks() {
        return getFinalizedBlocks;
    }

    public void setGetFinalizedBlocks(boolean getFinalizedBlocks) {
        this.getFinalizedBlocks = getFinalizedBlocks;
    }

    public boolean isWsGapRecoveryEnabled() {
        return wsGapRecoveryEnabled;
    }

    public void setWsGapRecoveryEnabled(boolean wsGapRecoveryEnabled) {
        this.wsGapRecoveryEnabled = wsGapRecoveryEnabled;
    }

    public int getWsGapRecoveryMaxBlocks() {
        return wsGapRecoveryMaxBlocks;
    }

    public void setWsGapRecoveryMaxBlocks(int wsGapRecoveryMaxBlocks) {
        this.wsGapRecoveryMaxBlocks = wsGapRecoveryMaxBlocks;
    }

    public int getScaleChangeMs() {
        return scaleChangeMs;
    }

    public void setScaleChangeMs(int scaleChangeMs) {
        this.scaleChangeMs = scaleChangeMs;
    }

    public int getScaleMaxMs() {
        return scaleMaxMs;
    }

    public void setScaleMaxMs(int scaleMaxMs) {
        this.scaleMaxMs = scaleMaxMs;
    }

    public ChartGradientMode getChartGradientMode() {
        return chartGradientMode;
    }

    public void setChartGradientMode(ChartGradientMode chartGradientMode) {
        this.chartGradientMode = chartGradientMode;
    }

    public SparklineDataSource getSparklineDataSource() {
        return sparklineDataSource;
    }

    public void setSparklineDataSource(SparklineDataSource sparklineDataSource) {
        this.sparklineDataSource = sparklineDataSource;
    }

    public long getBlockVerificationDelayMs() {
        return blockVerificationDelayMs;
    }

    public void setBlockVerificationDelayMs(long blockVerificationDelayMs) {
        this.blockVerificationDelayMs = blockVerificationDelayMs;
    }

    public Consensus getConsensus() {
        return consensus;
    }

    public void setConsensus(Consensus consensus) {
        this.consensus = consensus;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    public AnomalyDetection getAnomalyDetection() {
        return anomalyDetection;
    }

    public void setAnomalyDetection(AnomalyDetection anomalyDetection) {
        this.anomalyDetection = anomalyDetection;
    }

    public List<RpcNodeProperties> getNodes() {
        return nodes;
    }

    public void setNodes(List<RpcNodeProperties> nodes) {
        this.nodes = nodes;
    }

    public static class RpcNodeProperties {

        private String name;
        private String http;
        private String ws;
        private long pollIntervalMs = 3000;
        private AnomalyDetection anomalyDetection = new AnomalyDetection();
        private long connectTimeoutMs = -1;
        private long readTimeoutMs = -1;
        private int maxRetries = -1;
        private long retryBackoffMs = -1;
        private Map<String, String> headers = new HashMap<>();
        private Boolean wsGapRecoveryEnabled;
        private Integer wsGapRecoveryMaxBlocks;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getHttp() {
            return http;
        }

        public void setHttp(String http) {
            this.http = http;
        }

        public String getWs() {
            return ws;
        }

        public void setWs(String ws) {
            this.ws = ws;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public AnomalyDetection getAnomalyDetection() {
            return anomalyDetection;
        }

        public void setAnomalyDetection(AnomalyDetection anomalyDetection) {
            this.anomalyDetection = anomalyDetection;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(long retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public Boolean getWsGapRecoveryEnabled() {
            return wsGapRecoveryEnabled;
        }

        public void setWsGapRecoveryEnabled(Boolean wsGapRecoveryEnabled) {
            this.wsGapRecoveryEnabled = wsGapRecoveryEnabled;
        }

        public Integer getWsGapRecoveryMaxBlocks() {
            return wsGapRecoveryMaxBlocks;
        }

        public void setWsGapRecoveryMaxBlocks(Integer wsGapRecoveryMaxBlocks) {
            this.wsGapRecoveryMaxBlocks = wsGapRecoveryMaxBlocks;
        }
    }

    public static class Defaults {
        private long connectTimeoutMs = 2000;
        private long readTimeoutMs = 4000;
        private int maxRetries = 1;
        private long retryBackoffMs = 200;
        private Map<String, String> headers = new HashMap<>();

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(long retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }

    public static class Consensus {
        private String nodeKey;
        private String displayName = "consensus";
        private String http;
        private String eventsPath = "/eth/v1/events?topics=head&topics=finalized_checkpoint";
        private String finalityCheckpointsPath = "/eth/v1/beacon/states/head/finality_checkpoints";
        private Long safePollIntervalMs;
        private Long finalizedPollIntervalMs;
        private long timeoutMs = 2000;
        private boolean attestationsEnabled = false;
        private String attestationsPath = "/eth/v1/beacon/blocks/{slot}/attestations";
        private String committeesPath = "/eth/v1/beacon/states/head/committees";
        private long attestationTrackingIntervalMs = 3000;
        private int attestationTrackingMaxAttempts = 100;

        public String getNodeKey() {
            return nodeKey;
        }

        public void setNodeKey(String nodeKey) {
            this.nodeKey = nodeKey;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getHttp() {
            return http;
        }

        public void setHttp(String http) {
            this.http = http;
        }

        public String getEventsPath() {
            return eventsPath;
        }

        public void setEventsPath(String eventsPath) {
            this.eventsPath = eventsPath;
        }

        public String getFinalityCheckpointsPath() {
            return finalityCheckpointsPath;
        }

        public void setFinalityCheckpointsPath(String finalityCheckpointsPath) {
            this.finalityCheckpointsPath = finalityCheckpointsPath;
        }

        public Long getSafePollIntervalMs() {
            return safePollIntervalMs;
        }

        public void setSafePollIntervalMs(Long safePollIntervalMs) {
            this.safePollIntervalMs = safePollIntervalMs;
        }

        public Long getFinalizedPollIntervalMs() {
            return finalizedPollIntervalMs;
        }

        public void setFinalizedPollIntervalMs(Long finalizedPollIntervalMs) {
            this.finalizedPollIntervalMs = finalizedPollIntervalMs;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public boolean isAttestationsEnabled() {
            return attestationsEnabled;
        }

        public void setAttestationsEnabled(boolean attestationsEnabled) {
            this.attestationsEnabled = attestationsEnabled;
        }

        public String getAttestationsPath() {
            return attestationsPath;
        }

        public void setAttestationsPath(String attestationsPath) {
            this.attestationsPath = attestationsPath;
        }

        public String getCommitteesPath() {
            return committeesPath;
        }

        public void setCommitteesPath(String committeesPath) {
            this.committeesPath = committeesPath;
        }

        public long getAttestationTrackingIntervalMs() {
            return attestationTrackingIntervalMs;
        }

        public void setAttestationTrackingIntervalMs(long attestationTrackingIntervalMs) {
            this.attestationTrackingIntervalMs = attestationTrackingIntervalMs;
        }

        public int getAttestationTrackingMaxAttempts() {
            return attestationTrackingMaxAttempts;
        }

        public void setAttestationTrackingMaxAttempts(int attestationTrackingMaxAttempts) {
            this.attestationTrackingMaxAttempts = attestationTrackingMaxAttempts;
        }

        public boolean hasConfiguredReferenceNode() {
            return (nodeKey != null && !nodeKey.isBlank())
                    || (http != null && !http.isBlank());
        }
    }

    public static class Persistence {
        private boolean enabled = false;
        private String file = "./chaincheck-snapshot.json";
        private long flushIntervalSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }

        public long getFlushIntervalSeconds() {
            return flushIntervalSeconds;
        }

        public void setFlushIntervalSeconds(long flushIntervalSeconds) {
            this.flushIntervalSeconds = flushIntervalSeconds;
        }
    }

    public static class AnomalyDetection {
        private Integer longDelayBlockCount = 15;
        private long highLatencyMs = 2000;
        private long staleBlockThresholdMs = 30000;

        public Integer getLongDelayBlockCount() {
            return longDelayBlockCount;
        }

        public void setLongDelayBlockCount(Integer longDelayBlockCount) {
            this.longDelayBlockCount = longDelayBlockCount;
        }

        public long getHighLatencyMs() {
            return highLatencyMs;
        }

        public void setHighLatencyMs(long highLatencyMs) {
            this.highLatencyMs = highLatencyMs;
        }

        public long getStaleBlockThresholdMs() {
            return staleBlockThresholdMs;
        }

        public void setStaleBlockThresholdMs(long staleBlockThresholdMs) {
            this.staleBlockThresholdMs = staleBlockThresholdMs;
        }
    }
}
