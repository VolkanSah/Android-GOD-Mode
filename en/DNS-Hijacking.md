## 1. DoH-Client with OkHttp + Cert-Pinning (Encrypted + Trustworthy)

Advantage: Prevents MITM at the transport layer.

```kotlin
// Gradle: implementation "com.squareup.okhttp3:okhttp:4.11.0"
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.CertificatePinner
import java.util.concurrent.TimeUnit

fun createDoHClient(): OkHttpClient {
    // Pin to known resolver cert (example host: dns.example.com)
    val pinner = CertificatePinner.Builder()
        .add("dns.example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .build()

    return OkHttpClient.Builder()
        .certificatePinner(pinner)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
}

suspend fun dohQuery(client: OkHttpClient, dohUrl: String, dnsQueryWire: ByteArray): ByteArray? {
    val req = Request.Builder()
        .url(dohUrl) // e.g. "https://dns.example.com/dns-query"
        .post(dnsQueryWire.toRequestBody("application/dns-message".toMediaType()))
        .header("Accept", "application/dns-message")
        .build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IllegalStateException("DoH failed: ${resp.code}")
        return resp.body?.bytes()
    }
}
```

-----

## 2. DNSSEC Validation (Concept / dnsjava)

Advantage: Verifies that the response is actually signed by the authoritative server.

```kotlin
// Gradle: implementation "dnsjava:dnsjava:3.5.2"
import org.xbill.DNS.Message
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.DNSSEC
import org.xbill.DNS.Record
import org.xbill.DNS.RRset

fun verifyDnssec(responseBytes: ByteArray, qname: String): Boolean {
    // Parse response
    val msg = Message(responseBytes)
    // Simplified: in real world, fetch DNSKEY and DS or use a validating resolver
    // DNSSEC verification is non-trivial: offload to validating resolver if possible.
    // This placeholder returns false-safe if you can't fully verify.
    return try {
        // Example: DNSSEC.validate(...) with appropriate RRset & keys
        true // stub: implement full DNSSEC flow or use validating upstream
    } catch (e: Exception) {
        false
    }
}
```

**Note:** DNSSEC validation is complex — best practice: use a **validating upstream** (DoH that validates) or implement full chain (DNSKEY, DS) carefully.

-----

## 3. Authenticated Control Channel (HMAC + nonce) — Server-Side Verify

Advantage: Prohibits unauthorized runtime rule changes.

```kotlin
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.security.SecureRandom

fun hmacSha256(key: ByteArray, msg: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    val sig = mac.doFinal(msg)
    return Base64.encodeToString(sig, Base64.NO_WRAP)
}

// Server side:
suspend fun verifyAndProcessCommand(clientId: String, nonce: String, ts: Long, payloadJson: String, receivedMac: String) {
    val clientSecret = lookupClientSecret(clientId) ?: throw SecurityException("unknown client")
    // check timestamp
    if (kotlin.math.abs(System.currentTimeMillis() - ts) > 60_000) throw SecurityException("stale")
    if (isNonceUsed(clientId, nonce)) throw SecurityException("replay")
    val message = "$clientId|$nonce|$ts|$payloadJson".toByteArray(Charsets.UTF_8)
    val expected = hmacSha256(clientSecret, message)
    if (!constantTimeEquals(expected, receivedMac)) throw SecurityException("bad mac")
    markNonceUsed(clientId, nonce)
    // Authorized — process but validate payload (no raw DNS injection)
}
```

-----

## 4. Local Control via Bound Service (signature) — Android Pattern (Safer than Open WS)

Advantage: Only apps with the same signing key can bind.

**Manifest**

```xml
<permission android:name="com.example.safe.BIND_CTRL"
            android:protectionLevel="signature" />

<service android:name=".ControlService"
         android:exported="false"
         android:permission="com.example.safe.BIND_CTRL" />
```

**Service (Kotlin)**

```kotlin
class ControlService : Service() {
    private val binder = object : Binder() {
        fun getService(): ControlService = this@ControlService
    }
    override fun onBind(intent: Intent): IBinder = binder

    fun requestPolicyUpdate(signedPolicyJson: String) {
        val uid = Binder.getCallingUid()
        if (!isCallerSignatureMatch(uid)) {
            Log.w(TAG, "Rejected caller uid=$uid")
            return
        }
        // verify signature, expiry, then apply
    }

    private fun isCallerSignatureMatch(callingUid: Int): Boolean {
        val pkgs = packageManager.getPackagesForUid(callingUid) ?: return false
        return pkgs.any { packageManager.checkSignatures(it, packageName) == PackageManager.SIGNATURE_MATCH }
    }
}
```

-----

## 5.LocalServerSocket + Peer UID Check (If Socket Needed)

Advantage: No TCP; can check peer credentials.

```kotlin
import android.net.LocalServerSocket
import android.net.LocalSocket

fun startLocalSocketServer() {
    val server = LocalServerSocket("safe_control")
    GlobalScope.launch(Dispatchers.IO) {
        while (isActive) {
            val client = server.accept()
            // attempt to check peer UID via reflection/native if API lacks direct method
            // Alternative: require HMAC auth for each request too
            handleClient(client)
        }
    }
}
```

**Note:** On some Android versions, `LocalSocket.getPeerCredentials()` isn't available; combine with HMAC + signature checks.

-----

## 6. Signed Policy Update Flow (No Runtime Injects)

Advantage: Admins sign policies offline; clients only accept signed policies.

**Policy JSON + Signature**
Server publishes `policy.json` and `policy.sig`. Client flow:

```kotlin
// 1) Download policy.json + policy.sig via HTTPS with cert pinning (DoH style).
// 2) Verify signature using embedded public key (RSA/ECDSA)
// 3) Check "expires" field, version, nonce
// 4) Apply rules only if signature valid
```

**Signature Verify (Example using Java Security)**

```kotlin
import java.security.Signature
import java.security.PublicKey
import java.util.Base64

fun verifySignature(publicKey: PublicKey, data: ByteArray, signatureB64: String): Boolean {
    val sig = Signature.getInstance("SHA256withRSA")
    sig.initVerify(publicKey)
    sig.update(data)
    val signatureBytes = Base64.getDecoder().decode(signatureB64)
    return sig.verify(signatureBytes)
}
```

-----

## 7. Protect Sockets from Being Routed Through VPN (VpnService.protect)

Advantage: Control/telemetry sockets don't leak into tunnel loops.

```kotlin
// Inside your VpnService subclass
val s = Socket()
protect(s) // prevents this socket from going through the VPN
s.connect(InetSocketAddress("1.2.3.4", 443))
```

-----

## 8. Detect VPN Changes / onRevoke and Root Detection

Advantage: React quickly when another VPN or revoke happens.

```kotlin
override fun onRevoke() {
    // Disable sensitive operations, persist immutable evidence, notify user
    stopSensitiveServices()
}

// Basic root detection (heuristic)
fun isDeviceRooted(): Boolean {
    val paths = arrayOf("/system/bin/su", "/system/xbin/su")
    return paths.any { java.io.File(it).exists() }
}
```

-----

## 9. Telemetry: Only Hashed/Aggregated Data (No PII)

Advantage: Even if exfiltrated, no plaintext domains.

```kotlin
import java.security.MessageDigest
import android.util.Base64

fun hashDomain(domain: String, salt: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(salt)
    val d = md.digest(domain.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(d, Base64.NO_WRAP)
}
```

-----

## 10. Put It Together: Minimal Flow (Summary)

1.  Client builds DNS query → **DoH to validating upstream (cert-pinned)** OR local resolver with **DNSSEC validation**.
2.  Control plane (policy updates) only via **Bound Service / signed policy** or **authenticated HMAC channel**.
3.  **No open `127.0.0.1` TCP/WS**; if LocalSocket used, verify peer UID and require per-request HMAC.
4.  **Protect telemetry sockets with `protect()`**; disable features on `onRevoke()` or root.
5.  Log only **aggregated/hashed telemetry**; rotate & expire.
