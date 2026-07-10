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

import java.io.IOException;

/**
 * A JSON-RPC level error ({@code {"error":{"code":...,"message":...}}}) returned with an
 * otherwise successful HTTP exchange. Carries the structured error code and message so
 * callers can classify the error (e.g. Solana {@code -32004} "block not available yet")
 * instead of string-matching. The exception message is the raw error object's JSON text,
 * matching the plain {@code IOException} this replaces, so log output and anomaly
 * classification are unchanged.
 */
public class JsonRpcErrorException extends IOException {

    private final int code;
    private final String rpcMessage;

    public JsonRpcErrorException(int code, String rpcMessage, String rawErrorJson) {
        super(rawErrorJson);
        this.code = code;
        this.rpcMessage = rpcMessage;
    }

    public int getCode() {
        return code;
    }

    public String getRpcMessage() {
        return rpcMessage;
    }
}
