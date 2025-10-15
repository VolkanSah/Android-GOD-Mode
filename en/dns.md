# Documentation: Common DNS Security Flaws (Brief & Practical)

## 1. Open Localhost Listeners (TCP/WS) without Authentication

**What:** Services bind to `127.0.0.1:PORT` and accept connections without authentication.
**Impact:** Any app on the device can send commands, inject DNS responses, or dump history.
**Detection:** Local port scanner finds open ports; logs show incoming connections.
**Fix:** No unauthenticated TCP/WS listeners. Use **Bound Service / LocalSocket** or enforce authentication (mutual TLS / HMAC).
**In short:** No `/ctl` on 127.0.0.1 without strong auth.

-----

## 2. Control Channel + Data Plane in the Same Process

**What:** The admin API runs inside the VPN/Resolver process.
**Impact:** An attacker with access to the Control plane can manipulate the Data plane (MITM, redirects).
**Detection:** Review of the architectural layout; WebSocket found inside the `VpnService`.
**Fix:** Separate Control and Data. Place Control in a separate, **signature-protected Bound Service** or a secured management app.
**In short:** Separation instead of "everything in one process."

-----

## 3. Runtime Rule Changes without Signature

**What:** Rules (blocklists, redirects) are changed via an unsecured API.
**Impact:** Runtime Poisoning — Malicious apps add phishing redirects.
**Detection:** Unchecked rule-adds in logs; missing signature verification.
**Fix:** Only accept **signed policy updates**. Policies should be signed offline, and the client must verify the signature and expiry time.
**In short:** Rules only with a signature.

-----

## 4. Storing Raw DNS Logs / PII (Personally Identifiable Information)

**What:** Storing complete query logs in plaintext.
**Impact:** Massive privacy leak; profiling + exfiltration possible.
**Detection:** DB/table containing domains; large log dumps.
**Fix:** No raw logs. Only **aggregated, anonymized, or hashed buckets**. Opt-in for telemetry. Set a TTL for logs.
**In short:** No plaintext history.

-----

## 5. Missing Hardening for Control Auth (Single Static Token)

**What:** A single static token or UUID protects control access.
**Impact:** Token leak $\rightarrow$ full control. No replay protection.
**Detection:** Hardcoded token in code/strings; missing rotation.
**Fix:** Use **per-client credentials + HMAC (nonce+ts)** or mTLS. Enable rotation + revocation. Use the Android Keystore.
**In short:** A single token is a single point of failure.

-----

## 6. DNS Responses without Integrity Check

**What:** App uses plain UDP DNS and accepts answers, or generates responses without DNSSEC/DoH validation.
**Impact:** Easily forgeable; MITM is trivial.
**Detection:** No DoH/DoT; UDP-only; no cert-pinning.
**Fix:** Use **DoH/DoT + cert-pinning** or DNSSEC where possible. Validate upstream responses.
**In short:** Encryption + Validation.

-----

## 7. Unprotected Update/Fetch Mechanisms (Blocklist, C2)

**What:** Blocklist updates or C2 URLs are fetched over plain HTTP without authentication.
**Impact:** Man-in-the-Middle can replace rules.
**Detection:** Plain HTTP requests; no signature on resources.
**Fix:** **HTTPS + cert-pinning + signed payloads + integrity checks** (hash).
**In short:** Never trust unsecured endpoints.

-----

## 8. Missing Checks on Caller (UID / Signature)

**What:** LocalSocket/Binder usage without Peer UID / signature checks.
**Impact:** Any app can impersonate a legitimate client.
**Detection:** No `Binder.getCallingUid()` checks; no signature comparison.
**Fix:** Check `Binder.getCallingUid()` and `packageManager.checkSignatures(...)`. For LocalSocket, use SO\_PEERCRED (if available).
**In short:** Verify who is talking.

-----

## 9. Sending Sensitive Data via Unprotected Telemetry Channels

**What:** Full query logs or per-user data sent to remote C2/telemetry without mTLS/DB encryption.
**Impact:** Exfiltration, tracking, compliance breach.
**Detection:** Outbound connections to unknown endpoints; logs show data dumps.
**Fix:** Telemetry should only be **anonymized, aggregated, and opt-in**. Always use **mTLS + pinned certificates**. Rate-limit.
**In short:** Telemetry = minimal, encrypted only, with consent only.

-----

## 10\) Failure to Handle VPN Swap / onRevoke / Root Detection

**What:** App ignores `onRevoke()` and VPN competition. No root/device integrity checks.
**Impact:** Other VPNs can redirect traffic. Root can bypass protection.
**Detection:** No `onRevoke` handler; missing PlayIntegrity checks.
**Fix:** Implement `onRevoke()` $\rightarrow$ stop/lock immediately. Detect other VPNs, use **Play Integrity / SafetyNet**, warn the user and disable sensitive features on root.
**In short:** Monitor VPN state + device integrity.

-----

## Quick Developer Checklist (Copy/Paste)

  * [ ] No unauthenticated TCP/WS on 127.0.0.1.
  * [ ] Control in Bound Service (`protectionLevel=signature`) or LocalSocket + peer UID checks.
  * [ ] Policies must be signed + expire.
  * [ ] No plaintext DNS logs. Only hashed/aggregated.
  * [ ] Per-client creds + HMAC/mTLS + nonce/timestamp.
  * [ ] Use DoH/DoT + cert-pinning where possible.
  * [ ] HTTPS + signed update payloads.
  * [ ] Implement `onRevoke()` and detect VPN changes.
  * [ ] Detect root & disable sensitive features.
  * [ ] SAST + Code review specifically for IPC patterns.

-----

## Mini Snippets (Defensive Patterns)

**Caller UID check (Binder):**

```kotlin
val callingUid = Binder.getCallingUid()
val pkgs = packageManager.getPackagesForUid(callingUid)
val ok = pkgs.any { packageManager.checkSignatures(it, packageName) == PackageManager.SIGNATURE_MATCH }
if (!ok) throw SecurityException("Unauthorized caller")
```

**HMAC verify (concept):**

```kotlin
val message = "$clientId|$nonce|$timestamp|$payload".toByteArray()
val expected = hmac(clientSecret, message)
if (!constantTimeEquals(expected, receivedMac)) reject()
```

**Policy verification (high-level):**

  * Download `policy.json` + `policy.sig`.
  * Verify `sig` with embedded public key.
  * Check `expires`. Then apply.

-----

## Conclusion — Priorities (1-Sentence)

1.  Immediate: Remove unauthenticated localhost endpoints.
2.  Short-term: Separate Control & Data, check Caller UID.
3.  Mid-term: Signed policies + DoH + minimal telemetry.
