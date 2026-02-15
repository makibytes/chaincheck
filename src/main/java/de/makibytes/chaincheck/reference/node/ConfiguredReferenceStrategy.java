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
package de.makibytes.chaincheck.reference.node;

import java.time.Instant;
import java.util.Map;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.monitor.RpcMonitorService;
import de.makibytes.chaincheck.reference.block.ReferenceBlocks.Confidence;

public class ConfiguredReferenceStrategy implements ReferenceStrategy {

    private final ConfiguredReferenceSource source;
    private final String configuredReferenceNodeKey;
    private RpcMonitorService.ReferenceState currentReference;
    private String currentReferenceNodeKey;

    public ConfiguredReferenceStrategy(ConfiguredReferenceSource source, String configuredReferenceNodeKey) {
        this.source = source;
        this.configuredReferenceNodeKey = configuredReferenceNodeKey;
    }

    @Override
    public RpcMonitorService.ReferenceState getReference() {
        return currentReference;
    }

    @Override
    public String getReferenceNodeKey() {
        return currentReferenceNodeKey;
    }

    @Override
    public void refresh(Map<String, RpcMonitorService.NodeState> nodeStates, Instant now, boolean warmupComplete, String inputReferenceNodeKey) {
        ConfiguredReferenceSource.ReferenceUpdate update = source.refresh();
        currentReference = update.referenceState();
        currentReferenceNodeKey = update.referenceNodeKey();
    }

    @Override
    public String getWarmupWaitingReason(Map<String, RpcMonitorService.NodeState> nodeStates, ChainCheckProperties properties) {
        // Check consensus node first if enabled
        if (source.isConsensusNodeEnabled()) {
            if (source.getConsensusObservation(Confidence.NEW) == null) {
                return "reference head from configured consensus node";
            }
            boolean safePollingEnabled = source.isSafePollingEnabled();
            boolean finalizedPollingEnabled = source.isFinalizedPollingEnabled();
            if (safePollingEnabled && source.getConsensusObservation(Confidence.SAFE) == null) {
                return "safe block from configured consensus node";
            }
            if (finalizedPollingEnabled && source.getConsensusObservation(Confidence.FINALIZED) == null) {
                return "finalized block from configured consensus node";
            }
            return null;
        }

        // Check configured execution node
        boolean safeRequired = properties.isGetSafeBlocks();
        boolean finalizedRequired = properties.isGetFinalizedBlocks();
        if (configuredReferenceNodeKey != null && !configuredReferenceNodeKey.isBlank()) {
            RpcMonitorService.NodeState state = nodeStates.get(configuredReferenceNodeKey);
            if (state == null) {
                return "configured reference execution node state";
            }
            boolean hasHead = (state.lastWsBlockNumber != null && state.lastWsBlockHash != null)
                    || (state.lastHttpBlockNumber != null && state.lastHttpBlockHash != null);
            if (!hasHead) {
                return "head block from configured reference execution node";
            }
            if (safeRequired && (state.lastSafeBlockNumber == null || state.lastSafeBlockHash == null)) {
                return "safe block from configured reference execution node";
            }
            if (finalizedRequired && (state.lastFinalizedBlockNumber == null || state.lastFinalizedBlockHash == null)) {
                return "finalized block from configured reference execution node";
            }
            return null;
        }

        return null;
    }
}
