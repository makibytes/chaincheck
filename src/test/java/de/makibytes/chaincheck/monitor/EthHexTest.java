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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EthHex utility")
class EthHexTest {

    // --- parseLong ---

    @Test
    @DisplayName("parseLong: null input returns null")
    void parseLongNull() {
        assertNull(EthHex.parseLong(null));
    }

    @Test
    @DisplayName("parseLong: blank input returns null")
    void parseLongBlank() {
        assertNull(EthHex.parseLong("0x"));
    }

    @Test
    @DisplayName("parseLong: 0x prefix is stripped")
    void parseLongWithPrefix() {
        assertEquals(255L, EthHex.parseLong("0xff"));
        assertEquals(255L, EthHex.parseLong("0xFF"));
    }

    @Test
    @DisplayName("parseLong: no prefix treated as hex")
    void parseLongWithoutPrefix() {
        assertEquals(16L, EthHex.parseLong("10"));
        assertEquals(255L, EthHex.parseLong("ff"));
    }

    @Test
    @DisplayName("parseLong: known Ethereum block number")
    void parseLongBlockNumber() {
        assertEquals(20_000_000L, EthHex.parseLong("0x1312D00"));
    }

    // --- parseTimestamp ---

    @Test
    @DisplayName("parseTimestamp: null input returns null")
    void parseTimestampNull() {
        assertNull(EthHex.parseTimestamp(null));
    }

    @Test
    @DisplayName("parseTimestamp: converts hex seconds to Instant")
    void parseTimestampValid() {
        Instant result = EthHex.parseTimestamp("0x60000000");
        assertNotNull(result);
        assertEquals(0x60000000L, result.getEpochSecond());
    }

    // --- parseDecimalOrHexLong ---

    @Test
    @DisplayName("parseDecimalOrHexLong: null returns null")
    void parseDecimalOrHexLongNull() {
        assertNull(EthHex.parseDecimalOrHexLong(null));
    }

    @Test
    @DisplayName("parseDecimalOrHexLong: blank returns null")
    void parseDecimalOrHexLongBlank() {
        assertNull(EthHex.parseDecimalOrHexLong("   "));
    }

    @Test
    @DisplayName("parseDecimalOrHexLong: hex with 0x prefix")
    void parseDecimalOrHexLongHex() {
        assertEquals(256L, EthHex.parseDecimalOrHexLong("0x100"));
        assertEquals(256L, EthHex.parseDecimalOrHexLong("0X100"));
    }

    @Test
    @DisplayName("parseDecimalOrHexLong: plain decimal string")
    void parseDecimalOrHexLongDecimal() {
        assertEquals(12345L, EthHex.parseDecimalOrHexLong("12345"));
    }

    @Test
    @DisplayName("parseDecimalOrHexLong: trims whitespace")
    void parseDecimalOrHexLongTrims() {
        assertEquals(100L, EthHex.parseDecimalOrHexLong("  100  "));
    }

    // --- decodeHex ---

    @Test
    @DisplayName("decodeHex: null returns empty array")
    void decodeHexNull() {
        assertArrayEquals(new byte[0], EthHex.decodeHex(null));
    }

    @Test
    @DisplayName("decodeHex: blank returns empty array")
    void decodeHexBlank() {
        assertArrayEquals(new byte[0], EthHex.decodeHex(""));
        assertArrayEquals(new byte[0], EthHex.decodeHex("0x"));
    }

    @Test
    @DisplayName("decodeHex: odd-length hex returns empty array")
    void decodeHexOddLength() {
        assertArrayEquals(new byte[0], EthHex.decodeHex("abc"));
    }

    @Test
    @DisplayName("decodeHex: invalid characters return empty array")
    void decodeHexInvalidChars() {
        assertArrayEquals(new byte[0], EthHex.decodeHex("0xGG"));
    }

    @Test
    @DisplayName("decodeHex: well-formed hex with 0x prefix")
    void decodeHexWithPrefix() {
        byte[] result = EthHex.decodeHex("0xdeadbeef");
        assertArrayEquals(new byte[]{(byte)0xde, (byte)0xad, (byte)0xbe, (byte)0xef}, result);
    }

    @Test
    @DisplayName("decodeHex: well-formed hex without prefix")
    void decodeHexWithoutPrefix() {
        byte[] result = EthHex.decodeHex("deadbeef");
        assertArrayEquals(new byte[]{(byte)0xde, (byte)0xad, (byte)0xbe, (byte)0xef}, result);
    }

    @Test
    @DisplayName("decodeHex: uppercase hex")
    void decodeHexUppercase() {
        byte[] result = EthHex.decodeHex("0xDEADBEEF");
        assertArrayEquals(new byte[]{(byte)0xde, (byte)0xad, (byte)0xbe, (byte)0xef}, result);
    }

    @Test
    @DisplayName("decodeHex: all-zero bytes")
    void decodeHexAllZeros() {
        byte[] result = EthHex.decodeHex("0x0000");
        assertArrayEquals(new byte[]{0x00, 0x00}, result);
    }

    @Test
    @DisplayName("malformed hex degrades to null instead of throwing")
    void malformedHexReturnsNull() {
        assertNull(EthHex.parseLong("0xzz"));
        assertNull(EthHex.parseLong("0x12g4"));
        assertNull(EthHex.parseDecimalOrHexLong("not-a-number"));
        assertNull(EthHex.parseDecimalOrHexLong("0xNOPE"));
        assertNull(EthHex.parseTimestamp("0x??"));
    }
}
