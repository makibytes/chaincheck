# Anomaly Detection Reference

This document describes how ChainCheck detects and closes each anomaly type. The logic below reflects the current production behavior.

## Overview

- Anomalies are generated from live samples (HTTP and WS) and reference comparisons.
- Some anomalies are created by the dashboard layer when conflicting finalized blocks are observed.
- Ongoing anomalies remain open until a closing signal is recorded.

## ERROR

**When it triggers**
- Any failed RPC sample that is not classified as RATE_LIMIT or TIMEOUT.
- The error message is truncated to 50 characters for display, with full details stored in the anomaly.

**Signal source**
- RPC error response or transport failure.

## RATE_LIMIT

**When it triggers**
- Failed RPC sample whose error message contains “rate limit”, “rate-limit”, or “HTTP 429” (case-insensitive).

**Signal source**
- RPC error response or HTTP status.

## TIMEOUT

**When it triggers**
- Failed RPC sample whose error message contains “timeout” or “timed out” (case-insensitive).

**Signal source**
- HTTP timeout or related error.

## DELAY

Two independent delay signals use the same anomaly type:

**1) High latency**
- A successful RPC sample whose latency is greater than or equal to the configured `rpc.anomaly-detection.high-latency-ms`.

**2) Behind reference**
- A node’s head is behind the reference head by at least the long-delay threshold.
- The threshold is `rpc.anomaly-detection.long-delay-block-count` (default 15).
- Reference comparisons start only after a reference node is selected and at least 10 `newHeads` events are observed.

## BLOCK_GAP

**When it triggers**
- WebSocket head stream skips blocks: current height is more than previous height + 1.

**Signal source**
- WS `newHeads` stream only.

## REORG

Reorg detection is conservative and can be triggered in multiple ways:

**1) Height regression**
- Current block number is less than the previous block number.

**2) Same height, different hash**
- Current block number equals the previous number but the hash changed.

**3) Parent mismatch**
- Current height is exactly previous height + 1, but the current parent hash is not the previous hash.

**4) Finalized block invalidated**
- A finalized block is observed at the same height with a different hash than the previously observed finalized block.

**Guardrails**
- Reorg checks are suppressed for HTTP “latest” samples to avoid false positives when polling.

**Closure**
- The most recent HTTP REORG anomaly is closed when a perfect finalized chain of length 3 is observed.
- A perfect chain means three consecutive finalized blocks where each block’s parent hash matches the previous block’s hash.

## WRONG_HEAD

**When it triggers**
- The node’s safe or finalized block hash differs from the reference node at the same checkpoint height.

**Signal source**
- HTTP safe/finalized polling with reference comparison.

## CONFLICT

**When it triggers**
- Multiple distinct hashes are observed for the same block height in the sample window.
- The conflict is surfaced as an anomaly only when at least one of those hashes was observed as finalized.

**Signal source**
- Derived from the dashboard’s merged sample view (not emitted directly by the RPC monitor).

**Resolution behavior**
- If a later block links to one of the conflicting hashes as its parent, that hash becomes the resolved winner.
- Conflicting hashes that are not selected by parent linkage are tagged as invalid in the samples list.

## Notes on Ongoing vs Closed

- An anomaly marked as open (“ongoing”) continues to appear as such until the corresponding close condition is met.
- Error anomalies (ERROR, RATE_LIMIT, TIMEOUT) are closed when the underlying error changes or clears.
- Reorg anomalies are closed only by the finalized-chain closure rule described above.
