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
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rpc")
public class ChainCheckProperties {

    private String title = "";
    private String titleColor = "white";
    private boolean getSafeBlocks = false;
    private boolean getFinalizedBlocks = false;
    private int scaleChangeMs = 500;
    private int scaleMaxMs = 30000;
    private Consensus consensus = new Consensus();
    private Defaults defaults = new Defaults();
    private Persistence persistence = new Persistence();
    private AnomalyDetection anomalyDetection = new AnomalyDetection();
    private List<RpcNodeProperties> nodes = new ArrayList<>();

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
        private java.util.Map<String, String> headers = new java.util.HashMap<>();

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

        public java.util.Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(java.util.Map<String, String> headers) {
            this.headers = headers;
        }
    }

    public static class Defaults {
        private long connectTimeoutMs = 2000;
        private long readTimeoutMs = 4000;
        private int maxRetries = 1;
        private long retryBackoffMs = 200;
        private java.util.Map<String, String> headers = new java.util.HashMap<>();

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

        public java.util.Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(java.util.Map<String, String> headers) {
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
    }
}
