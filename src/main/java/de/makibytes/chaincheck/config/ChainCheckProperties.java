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
    private ReferenceNode reference = new ReferenceNode();
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

    public ReferenceNode getReference() {
        return reference;
    }

    public void setReference(ReferenceNode reference) {
        this.reference = reference;
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
        private long anomalyDelayMs = 2000;
        private boolean safeBlocksEnabled = false;
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

        public long getAnomalyDelayMs() {
            return anomalyDelayMs;
        }

        public void setAnomalyDelayMs(long anomalyDelayMs) {
            this.anomalyDelayMs = anomalyDelayMs;
        }

        public boolean isSafeBlocksEnabled() {
            return safeBlocksEnabled;
        }

        public void setSafeBlocksEnabled(boolean safeBlocksEnabled) {
            this.safeBlocksEnabled = safeBlocksEnabled;
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

    public static class ReferenceNode {
        private String http;
        private long timeoutMs = 2000;

        public String getHttp() {
            return http;
        }

        public void setHttp(String http) {
            this.http = http;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
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
        private Integer maxBlockLag = 5;

        public Integer getMaxBlockLag() {
            return maxBlockLag;
        }

        public void setMaxBlockLag(Integer maxBlockLag) {
            this.maxBlockLag = maxBlockLag;
        }
    }
}
