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

public interface ReferenceStrategy {

    RpcMonitorService.ReferenceState getReference();

    String getReferenceNodeKey();

    void refresh(Map<String, RpcMonitorService.NodeState> nodeStates, Instant now, boolean warmupComplete, String currentReferenceNodeKey);

    /**
     * Returns the reason why warmup is waiting, or null if warmup conditions are met.
     */
    String getWarmupWaitingReason(Map<String, RpcMonitorService.NodeState> nodeStates, ChainCheckProperties properties);
}
