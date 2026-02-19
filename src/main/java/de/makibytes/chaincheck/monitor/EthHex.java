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

public final class EthHex {

    private EthHex() {}

    public static Long parseLong(String hex) {
        if (hex == null) {
            return null;
        }
        String normalized = hex.startsWith("0x") ? hex.substring(2) : hex;
        if (normalized.isBlank()) {
            return null;
        }
        return new BigInteger(normalized, 16).longValue();
    }

    public static Instant parseTimestamp(String hex) {
        Long seconds = parseLong(hex);
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    public static Long parseDecimalOrHexLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            return new BigInteger(normalized.substring(2), 16).longValue();
        }
        return new BigInteger(normalized, 10).longValue();
    }

    public static Instant parseDecimalOrHexTimestamp(String value) {
        Long seconds = parseDecimalOrHexLong(value);
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    public static byte[] decodeHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return new byte[0];
        }
        String normalized = hex.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isEmpty() || normalized.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] bytes = new byte[normalized.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(normalized.charAt(i * 2), 16);
            int low = Character.digit(normalized.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return new byte[0];
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
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
