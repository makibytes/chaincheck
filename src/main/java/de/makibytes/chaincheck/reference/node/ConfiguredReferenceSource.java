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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AttestationConfidence;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.reference.attestation.AttestationTracker;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.NodeRegistry;
import de.makibytes.chaincheck.monitor.RpcMonitorService;
import de.makibytes.chaincheck.reference.block.BlockVotingService;
import de.makibytes.chaincheck.reference.block.ReferenceBlocks.Confidence;

public class ConfiguredReferenceSource {

    private static final Logger logger = LoggerFactory.getLogger(ConfiguredReferenceSource.class);

    private final NodeRegistry nodeRegistry;
    private final BlockVotingService blockVotingService;
    private final Map<String, RpcMonitorService.NodeState> nodeStates;
    private final String configuredReferenceNodeKey;
    private final ConsensusNodeClient consensusNode;
    private final String consensusNodeDisplayName;

    public ConfiguredReferenceSource(NodeRegistry nodeRegistry,
                         BlockVotingService blockVotingService,
                         Map<String, RpcMonitorService.NodeState> nodeStates,
                         ChainCheckProperties properties,
                         String configuredReferenceNodeKey) {
        this.nodeRegistry = nodeRegistry;
        this.blockVotingService = blockVotingService;
        this.nodeStates = nodeStates;
        this.configuredReferenceNodeKey = configuredReferenceNodeKey;
        AttestationTracker tracker = null;
        if (properties.getConsensus() != null && properties.getConsensus().isAttestationsEnabled()) {
            tracker = new AttestationTracker();
            logger.info("Attestation confidence tracking enabled");
        }
        this.consensusNode = new ConsensusNodeClient(properties.getConsensus(), tracker);
        this.consensusNodeDisplayName = properties.getConsensus() != null ? properties.getConsensus().getDisplayName() : "consensus";
    }

    public boolean isConsensusNodeEnabled() {
        return consensusNode.isEnabled();
    }

    boolean isSafePollingEnabled() {
        return consensusNode.isSafePollingEnabled();
    }

    boolean isFinalizedPollingEnabled() {
        return consensusNode.isFinalizedPollingEnabled();
    }

    public Long getSafePollIntervalMs() {
        return consensusNode.getSafePollIntervalMs();
    }

    public Long getFinalizedPollIntervalMs() {
        return consensusNode.getFinalizedPollIntervalMs();
    }

    ReferenceObservation getConsensusObservation(Confidence confidence) {
        return consensusNode.getObservation(confidence);
    }

    public AttestationConfidence getAttestationConfidence(long blockNumber) {
        return consensusNode.getAttestationConfidence(blockNumber);
    }

    public Map<String, AttestationConfidence> getRecentAttestationConfidences() {
        return consensusNode.getRecentAttestationConfidences();
    }

    public void ensureEventStream() {
        consensusNode.ensureEventStream();
    }

    public boolean refreshCheckpoints(boolean refreshSafe, boolean refreshFinalized) {
        if (refreshSafe) {
            logger.debug("Consensus poll ({}) blockTag=safe", consensusNodeDisplayName);
        }
        if (refreshFinalized) {
            logger.debug("Consensus poll ({}) blockTag=finalized", consensusNodeDisplayName);
        }
        return consensusNode.refreshCheckpoints(refreshSafe, refreshFinalized);
    }

    public ReferenceObservation getObservation(Confidence confidence) {
        ReferenceObservation consensusNodeObservation = consensusNode.getObservation(confidence);
        if (consensusNodeObservation != null) {
            return consensusNodeObservation;
        }
        if (configuredReferenceNodeKey == null || configuredReferenceNodeKey.isBlank()) {
            return null;
        }
        RpcMonitorService.NodeState state = nodeStates.get(configuredReferenceNodeKey);
        if (state == null) {
            return null;
        }
        Instant now = Instant.now();
        return switch (confidence) {
            case NEW -> {
                Long number = state.lastWsBlockNumber != null ? state.lastWsBlockNumber : state.lastHttpBlockNumber;
                String hash = state.lastWsBlockHash != null ? state.lastWsBlockHash : state.lastHttpBlockHash;
                Instant observedAt = state.lastWsEventReceivedAt != null ? state.lastWsEventReceivedAt : now;
                yield number == null || hash == null
                        ? null
                        : new ReferenceObservation(number, hash, null, observedAt, observedAt, null);
            }
            case SAFE -> {
                if (state.lastSafeBlockNumber == null || state.lastSafeBlockHash == null) {
                    yield null;
                }
                yield new ReferenceObservation(
                        state.lastSafeBlockNumber,
                        state.lastSafeBlockHash,
                        null,
                        now,
                        now,
                        null);
            }
            case FINALIZED -> {
                if (state.lastFinalizedBlockNumber == null || state.lastFinalizedBlockHash == null) {
                    yield null;
                }
                Instant observedAt = state.lastFinalizedFetchAt != null ? state.lastFinalizedFetchAt : now;
                yield new ReferenceObservation(
                        state.lastFinalizedBlockNumber,
                        state.lastFinalizedBlockHash,
                        null,
                        observedAt,
                        observedAt,
                        null);
            }
        };
    }

    public Instant getObservedAt(Confidence confidence, Long blockNumber, String blockHash) {
        ReferenceObservation observation = getObservation(confidence);
        if (observation == null || observation.knowledgeAt() == null) {
            return null;
        }
        if (blockHash != null && observation.blockHash() != null
                && observation.blockHash().equalsIgnoreCase(blockHash)) {
            return observation.knowledgeAt();
        }
        if (blockNumber != null && observation.blockNumber() != null
                && observation.blockNumber().equals(blockNumber)) {
            return observation.knowledgeAt();
        }
        return null;
    }

    public List<MetricSample> getDelaySamplesSince(Instant since) {
        if (!consensusNode.isEnabled()) {
            return List.of();
        }
        List<MetricSample> result = new ArrayList<>();
        // Only include safe and finalized delays from consensus node
        // Head (NEW) delay should come from the execution node monitoring, not consensus
        appendReferenceDelaySamples(result, consensusNode.getObservationHistorySince(Confidence.SAFE, since), Confidence.SAFE);
        appendReferenceDelaySamples(result, consensusNode.getObservationHistorySince(Confidence.FINALIZED, since), Confidence.FINALIZED);
        result.sort(java.util.Comparator.comparing(MetricSample::getTimestamp));
        return result;
    }

    ReferenceUpdate refresh() {
        if (consensusNode.isEnabled()) {
            consensusNode.ensureEventStream();
        }

        ReferenceObservation headObservation = getObservation(Confidence.NEW);
        RpcMonitorService.ReferenceState state = null;
        if (headObservation != null) {
            state = new RpcMonitorService.ReferenceState(headObservation.blockNumber(), headObservation.blockHash(), headObservation.observedAt());
        }

        String referenceNodeKey = configuredReferenceNodeKey != null && !configuredReferenceNodeKey.isBlank()
                && nodeRegistry.getNode(configuredReferenceNodeKey) != null
                ? configuredReferenceNodeKey
                : null;

        blockVotingService.clearVotes();
        blockVotingService.getReferenceBlocks().clear();

        if (headObservation != null) {
            blockVotingService.getReferenceBlocks().setHash(headObservation.blockNumber(), Confidence.NEW, headObservation.blockHash());
        }

        ReferenceObservation safeObservation = getObservation(Confidence.SAFE);
        if (safeObservation != null) {
            blockVotingService.getReferenceBlocks().setHash(safeObservation.blockNumber(), Confidence.SAFE, safeObservation.blockHash());
        }

        ReferenceObservation finalizedObservation = getObservation(Confidence.FINALIZED);
        if (finalizedObservation != null) {
            blockVotingService.getReferenceBlocks().setHash(finalizedObservation.blockNumber(), Confidence.FINALIZED, finalizedObservation.blockHash());
        }

        return new ReferenceUpdate(state, referenceNodeKey);
    }

    private void appendReferenceDelaySamples(List<MetricSample> target,
                                             List<ReferenceObservation> observations,
                                             Confidence confidence) {
        for (ReferenceObservation observation : observations) {
            Long headDelay = confidence == Confidence.NEW ? observation.delayMs() : null;
            Long safeDelay = confidence == Confidence.SAFE ? observation.delayMs() : null;
            Long finalizedDelay = confidence == Confidence.FINALIZED ? observation.delayMs() : null;
            target.add(new MetricSample(
                    observation.observedAt(),
                    MetricSource.HTTP,
                    true,
                    -1,
                    observation.blockNumber(),
                    observation.blockTimestamp(),
                    observation.blockHash(),
                    null,
                    null,
                    null,
                    null,
                    headDelay,
                    safeDelay,
                    finalizedDelay));
        }
    }

    record ReferenceUpdate(RpcMonitorService.ReferenceState referenceState, String referenceNodeKey) {
    }
}
