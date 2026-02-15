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
package de.makibytes.chaincheck.monitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.ReferenceBlocks.Confidence;

class ConsensusNodeService {

    private static final Logger logger = LoggerFactory.getLogger(ConsensusNodeService.class);

    private final NodeRegistry nodeRegistry;
    private final BlockVotingService blockVotingService;
    private final Map<String, NodeMonitorService.NodeState> nodeStates;
    private final String configuredReferenceNodeKey;
    private final BeaconReferenceService beaconClient;

    ConsensusNodeService(NodeRegistry nodeRegistry,
                         BlockVotingService blockVotingService,
                         Map<String, NodeMonitorService.NodeState> nodeStates,
                         ChainCheckProperties properties,
                         String configuredReferenceNodeKey) {
        this.nodeRegistry = nodeRegistry;
        this.blockVotingService = blockVotingService;
        this.nodeStates = nodeStates;
        this.configuredReferenceNodeKey = configuredReferenceNodeKey;
        this.beaconClient = new BeaconReferenceService(properties.getConsensus());
    }

    boolean isBeaconEnabled() {
        return beaconClient.isEnabled();
    }

    boolean isSafePollingEnabled() {
        return beaconClient.isSafePollingEnabled();
    }

    boolean isFinalizedPollingEnabled() {
        return beaconClient.isFinalizedPollingEnabled();
    }

    Long getSafePollIntervalMs() {
        return beaconClient.getSafePollIntervalMs();
    }

    Long getFinalizedPollIntervalMs() {
        return beaconClient.getFinalizedPollIntervalMs();
    }

    void ensureEventStream() {
        beaconClient.ensureEventStream();
    }

    void refreshCheckpoints(boolean refreshSafe, boolean refreshFinalized) {
        if (refreshSafe) {
            logger.debug("Consensus poll ({}) blockTag=safe", configuredReferenceNodeKey);
        }
        if (refreshFinalized) {
            logger.debug("Consensus poll ({}) blockTag=finalized", configuredReferenceNodeKey);
        }
        beaconClient.refreshCheckpoints(refreshSafe, refreshFinalized);
    }

    BeaconReferenceService.ReferenceObservation getConfiguredObservation(Confidence confidence) {
        BeaconReferenceService.ReferenceObservation beaconObservation = beaconClient.getObservation(confidence);
        if (beaconObservation != null) {
            return beaconObservation;
        }
        if (configuredReferenceNodeKey == null || configuredReferenceNodeKey.isBlank()) {
            return null;
        }
        NodeMonitorService.NodeState state = nodeStates.get(configuredReferenceNodeKey);
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
                        : new BeaconReferenceService.ReferenceObservation(number, hash, null, observedAt, observedAt, null);
            }
            case SAFE -> {
                if (state.lastSafeBlockNumber == null || state.lastSafeBlockHash == null) {
                    yield null;
                }
                yield new BeaconReferenceService.ReferenceObservation(
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
                yield new BeaconReferenceService.ReferenceObservation(
                        state.lastFinalizedBlockNumber,
                        state.lastFinalizedBlockHash,
                        null,
                        observedAt,
                        observedAt,
                        null);
            }
        };
    }

    Instant getReferenceObservedAt(Confidence confidence, Long blockNumber, String blockHash) {
        BeaconReferenceService.ReferenceObservation observation = getConfiguredObservation(confidence);
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

    List<MetricSample> getConfiguredReferenceDelaySamplesSince(Instant since) {
        if (!beaconClient.isEnabled()) {
            return List.of();
        }
        Instant effectiveSince = since == null ? Instant.EPOCH : since;
        List<MetricSample> result = new ArrayList<>();
        appendReferenceDelaySamples(result, beaconClient.getObservationHistorySince(Confidence.NEW, effectiveSince), Confidence.NEW);
        appendReferenceDelaySamples(result, beaconClient.getObservationHistorySince(Confidence.SAFE, effectiveSince), Confidence.SAFE);
        appendReferenceDelaySamples(result, beaconClient.getObservationHistorySince(Confidence.FINALIZED, effectiveSince), Confidence.FINALIZED);
        result.sort(java.util.Comparator.comparing(MetricSample::getTimestamp));
        return result;
    }

    ConfiguredReferenceUpdate refreshReferenceFromConfiguredSource() {
        if (nodeRegistry == null) {
            return new ConfiguredReferenceUpdate(null, null);
        }

        if (beaconClient.isEnabled()) {
            beaconClient.ensureEventStream();
        }

        BeaconReferenceService.ReferenceObservation headObservation = getConfiguredObservation(Confidence.NEW);
        NodeMonitorService.ReferenceState state = null;
        if (headObservation != null && headObservation.blockNumber() != null && headObservation.blockHash() != null) {
            state = new NodeMonitorService.ReferenceState(headObservation.blockNumber(), headObservation.blockHash(), headObservation.observedAt());
        }

        String referenceNodeKey = configuredReferenceNodeKey != null && !configuredReferenceNodeKey.isBlank()
                && nodeRegistry.getNode(configuredReferenceNodeKey) != null
                ? configuredReferenceNodeKey
                : null;

        blockVotingService.clearVotes();
        blockVotingService.getReferenceBlocks().clear();

        if (headObservation != null && headObservation.blockNumber() != null && headObservation.blockHash() != null) {
            blockVotingService.getReferenceBlocks().setHash(headObservation.blockNumber(), Confidence.NEW, headObservation.blockHash());
        }

        BeaconReferenceService.ReferenceObservation safeObservation = getConfiguredObservation(Confidence.SAFE);
        if (safeObservation != null && safeObservation.blockNumber() != null && safeObservation.blockHash() != null) {
            blockVotingService.getReferenceBlocks().setHash(safeObservation.blockNumber(), Confidence.SAFE, safeObservation.blockHash());
        }

        BeaconReferenceService.ReferenceObservation finalizedObservation = getConfiguredObservation(Confidence.FINALIZED);
        if (finalizedObservation != null && finalizedObservation.blockNumber() != null && finalizedObservation.blockHash() != null) {
            blockVotingService.getReferenceBlocks().setHash(finalizedObservation.blockNumber(), Confidence.FINALIZED, finalizedObservation.blockHash());
        }

        return new ConfiguredReferenceUpdate(state, referenceNodeKey);
    }

    private void appendReferenceDelaySamples(List<MetricSample> target,
                                             List<BeaconReferenceService.ReferenceObservation> observations,
                                             Confidence confidence) {
        for (BeaconReferenceService.ReferenceObservation observation : observations) {
            if (observation == null || observation.observedAt() == null) {
                continue;
            }
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

    record ConfiguredReferenceUpdate(NodeMonitorService.ReferenceState referenceState, String referenceNodeKey) {
    }
}
