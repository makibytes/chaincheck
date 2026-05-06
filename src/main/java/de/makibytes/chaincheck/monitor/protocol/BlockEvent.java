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

import java.time.Instant;

/**
 * Normalised block data parsed from a WebSocket new-block notification.
 *
 * <p>When {@code hasFullData} is {@code false} an extra HTTP fetch is required to
 * populate the missing fields (Ethereum: getBlockByHash; Solana: getBlock by slot).
 * When {@code true} the event already carries everything needed (Cosmos, Starknet).
 *
 * <p>{@code identifier} is the value passed to
 * {@link ChainProtocol#buildFetchAfterWsEventRequest}: a block hash for EVM, a slot
 * number string for Solana, a block height string for Cosmos.
 */
public record BlockEvent(
        String identifier,
        Long blockNumber,
        String blockHash,
        String parentHash,
        Instant blockTimestamp,
        boolean hasFullData) {}
