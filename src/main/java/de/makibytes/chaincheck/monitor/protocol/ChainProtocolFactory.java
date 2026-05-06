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
package de.makibytes.chaincheck.monitor.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.config.ChainCheckProperties.ModeType;

/**
 * Constructs the correct {@link ChainProtocol} implementation for a given {@link ModeType}.
 */
public final class ChainProtocolFactory {

    private ChainProtocolFactory() {}

    public static ChainProtocol create(ModeType mode, ObjectMapper mapper) {
        return switch (mode) {
            case ETHEREUM, COSMOS, OPTIMISM, ZK, AVALANCHE, TRON -> new EvmProtocol(mapper, mode);
            case SOLANA    -> new SolanaProtocol(mapper);
            case COSMOS_SDK -> new CosmosProtocol(mapper);
            case STARKNET  -> new StarknetProtocol(mapper);
        };
    }
}
