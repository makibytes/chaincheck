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
package de.makibytes.chaincheck.chain.cosmos;

import java.time.Instant;
import java.util.Map;

import de.makibytes.chaincheck.chain.shared.BlockAgreementTracker;
import de.makibytes.chaincheck.chain.shared.Confidence;
import de.makibytes.chaincheck.chain.shared.ReferenceStrategy;
import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.monitor.NodeRegistry;
import de.makibytes.chaincheck.monitor.RpcMonitorService;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

/**
 * Reference strategy that determines reference state via majority voting across monitored nodes.
 * Used in Cosmos mode where no consensus node is configured.
 */
public class VotingReferenceStrategy implements ReferenceStrategy {

    private final NodeRegistry nodeRegistry;
    private final BlockVotingService blockVotingService;
    private final BlockVotingCoordinator blockVotingCoordinator;
    private final ReferenceNodeSelector referenceNodeSelector;
    private final BlockAgreementTracker blockAgreementTracker;
    private final InMemoryMetricsStore store;
    private RpcMonitorService.ReferenceState currentReference;
    private String currentReferenceNodeKey;

    public VotingReferenceStrategy(NodeRegistry nodeRegistry,
                                   BlockVotingService blockVotingService,
                                   BlockVotingCoordinator blockVotingCoordinator,
                                   ReferenceNodeSelector referenceNodeSelector,
                                   BlockAgreementTracker blockAgreementTracker,
                                   InMemoryMetricsStore store) {
        this.nodeRegistry = nodeRegistry;
        this.blockVotingService = blockVotingService;
        this.blockVotingCoordinator = blockVotingCoordinator;
        this.referenceNodeSelector = referenceNodeSelector;
        this.blockAgreementTracker = blockAgreementTracker;
        this.store = store;
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
        if (nodeRegistry.getNodes().isEmpty()) {
            currentReference = null;
            blockVotingService.getBlockConfidenceTracker().clear();
            return;
        }

        blockVotingCoordinator.collectVotesFromNodes(nodeStates);
        Map<Long, Map<Confidence, String>> oldBlocks = blockVotingCoordinator.snapshotOldReferenceBlocks();
        blockVotingCoordinator.performVotingAndScoring(oldBlocks, inputReferenceNodeKey, now, warmupComplete, nodeStates);

        BlockVotingCoordinator.ReferenceHead referenceHead = blockVotingCoordinator.resolveReferenceHead();
        if (referenceHead == null) {
            currentReference = null;
            return;
        }

        currentReference = new RpcMonitorService.ReferenceState(referenceHead.headNumber(), referenceHead.headHash(), now);
        currentReferenceNodeKey = referenceNodeSelector.select(
                nodeStates,
                blockVotingService.getBlockConfidenceTracker(),
                store,
                now,
                blockAgreementTracker,
                inputReferenceNodeKey);
    }

    @Override
    public String getWarmupWaitingReason(Map<String, RpcMonitorService.NodeState> nodeStates, ChainCheckProperties properties) {
        boolean safeRequired = properties.isGetSafeBlocks();
        boolean finalizedRequired = properties.isGetFinalizedBlocks();

        if (currentReference == null || currentReference.headNumber() == null || currentReference.headHash() == null) {
            return "reference head by voting across monitored nodes";
        }
        if (currentReferenceNodeKey == null) {
            return "reference node selection by voting";
        }
        if (safeRequired) {
            boolean hasSafe = nodeStates.values().stream().anyMatch(
                    state -> state.lastSafeBlockNumber != null && state.lastSafeBlockHash != null);
            if (!hasSafe) {
                return "first safe block from monitored nodes";
            }
        }
        if (finalizedRequired) {
            boolean hasFinalized = nodeStates.values().stream().anyMatch(
                    state -> state.lastFinalizedBlockNumber != null && state.lastFinalizedBlockHash != null);
            if (!hasFinalized) {
                return "first finalized block from monitored nodes";
            }
        }
        return null;
    }
}
