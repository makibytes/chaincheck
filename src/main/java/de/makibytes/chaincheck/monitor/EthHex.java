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

import java.math.BigInteger;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

final class EthHex {

    private EthHex() {}

    static Long parseLong(String hex) {
        if (hex == null) {
            return null;
        }
        String normalized = hex.startsWith("0x") ? hex.substring(2) : hex;
        if (normalized.isBlank()) {
            return null;
        }
        return new BigInteger(normalized, 16).longValue();
    }

    static Instant parseTimestamp(String hex) {
        Long seconds = parseLong(hex);
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    static RpcMonitorService.BlockInfo parseBlockFields(JsonNode result) {
        if (result == null || result.isNull()) {
            return null;
        }
        Long blockNumber = parseLong(result.path("number").asText(null));
        String blockHash = result.path("hash").asText(null);
        String parentHash = result.path("parentHash").asText(null);
        Instant blockTimestamp = parseTimestamp(result.path("timestamp").asText(null));
        Long gasPriceWei = parseLong(result.path("baseFeePerGas").asText(null));
        Integer txCount = null;
        JsonNode transactions = result.path("transactions");
        if (transactions.isArray()) {
            txCount = transactions.size();
        }
        return new RpcMonitorService.BlockInfo(blockNumber, blockHash, parentHash, txCount, gasPriceWei, blockTimestamp);
    }
}
