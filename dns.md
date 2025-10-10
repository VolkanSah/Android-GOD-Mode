# Dokumentation: Häufige DNS‑Sicherheitsfehler (kurz & praktisch)

## 1) Offene localhost‑Listener (TCP/WS) ohne Auth

**Was:** Dienste binden `127.0.0.1:PORT` und akzeptieren Verbindungen ohne Auth.
**Impact:** Jede App auf dem Gerät kann Befehle senden, DNS‑Antworten injizieren, History dumpen.
**Erkennung:** Lokaler Portscanner findet offene Ports; Logs zeigen eingehende Verbindungen.
**Fix:** Keine unauthenticated TCP/WS‑Listener. Nutze Bound Service / LocalSocket oder erzwungene Auth (mutual TLS / HMAC).
**Kurz:** Kein `/ctl` auf 127.0.0.1 ohne starke Auth.

---

## 2) Control‑Channel + Data‑Plane im selben Prozess

**Was:** Admin‑API läuft im VPN/Resolver‑Process.
**Impact:** Angreifer mit Zugriff auf Control kann Data manipulieren (MITM, Redirects).
**Erkennung:** Review des Architektur‑Layouts; WebSocket im VpnService gefunden.
**Fix:** Trenne Control und Data. Control in separatem, signatur‑geschützten Bound Service oder gesicherter Management‑App.
**Kurz:** Trennung statt „alles in einem Prozess“.

---

## 3) Runtime‑Regeländerungen ohne Signatur

**Was:** Regeln (Blocklists, Redirects) werden per ungesicherter API geändert.
**Impact:** Runtime‑Poisoning — Böse Apps fügen Phishing‑Redirects hinzu.
**Erkennung:** Ungeprüfte Rule‑Adds in Logs; fehlende Signaturprüfung.
**Fix:** Nur signierte Policy‑Updates akzeptieren. Policies offline signieren + client prüft Signatur + Ablaufzeit.
**Kurz:** Regeln nur mit Signatur.

---

## 4) Speicherung von Roh‑DNS‑Logs / PII

**Was:** Speicherung kompletter Query‑Logs in Klartext.
**Impact:** Massive Privacy‑Leak; Profiling + Exfiltration möglich.
**Erkennung:** DB/Table mit Domains; große Log‑Dumps.
**Fix:** Keine Rohlogs. Nur aggregierte, anonymisierte oder gehashte Buckets. Opt‑in für Telemetrie. TTL für Logs.
**Kurz:** Keine Klartext‑Historie.

---

## 5) Fehlende Härtung bei Control‑Auth (single static token)

**Was:** Ein statischer Token oder UUID schützt Control.
**Impact:** Token‑Leak → volle Kontrolle. Kein Replay‑Schutz.
**Erkennung:** Hardcoded token in code/strings; missing rotation.
**Fix:** Per‑client creds + HMAC (nonce+ts) oder mTLS. Rotation + revocation möglich. Keystore nutzen.
**Kurz:** Single token ist single point of failure.

---

## 6) DNS antworten ohne Integritätsprüfung

**Was:** App nutzt plain UDP DNS und nimmt Antworten an, oder generiert responses ohne DNSSEC/DoH validation.
**Impact:** Leicht fälschbar; MITM trivial.
**Erkennung:** Kein DoH/DoT; UDP‑only, kein cert‑pinning.
**Fix:** Use DoH/DoT + cert‑pinning or DNSSEC where possible. Validate upstream responses.
**Kurz:** Verschlüsselung + Validierung.

---

## 7) Nicht‑geschützte Update‑/Fetch‑Mechanismen (Blocklist, C2)

**Was:** Blocklist‑Updates oder C2‑URLs per HTTP ohne Auth.
**Impact:** Man‑in‑the‑Middle kann Regeln ersetzen.
**Erkennung:** Plain HTTP requests; no signature on resources.
**Fix:** HTTPS + cert‑pinning + signed payloads + integrity checks (hash).
**Kurz:** Vertraue nie ungesicherten Endpoints.

---

## 8) Fehlende Prüfungen auf Caller (UID / Signature)

**Was:** LocalSocket/Binder ohne Peer UID / signature checks.
**Impact:** Jede App kann sich als legitimen Client ausgeben.
**Erkennung:** No `Binder.getCallingUid()` checks; no signature compare.
**Fix:** Prüfe `Binder.getCallingUid()` und `packageManager.checkSignatures(...)`. Bei LocalSocket nutze SO_PEERCRED (wenn möglich).
**Kurz:** Prüfe, wer redet.

---

## 9) Senden sensibler Daten über ungeschützte Telemetrie‑Kanäle

**Was:** Vollständige query logs oder per‑user data an remote C2/telemetry ohne mTLS/DB encryption.
**Impact:** Exfiltration, Tracking, Compliance‑Breach.
**Erkennung:** Outbound connections zu unknown endpoints; logs show dumps.
**Fix:** Telemetrie nur anonymisiert, aggregiert, opt‑in. Immer mTLS + pinned certs. Rate‑limit.
**Kurz:** Telemetrie = nur minimal, nur verschlüsselt, nur mit Consent.

---

## 10) Kein Umgang mit VPN‑Swap / onRevoke / Root Detection

**Was:** App ignoriert `onRevoke()` und VPN‑Konkurrenz. Keine Root/Device‑Integrity Checks.
**Impact:** Andere VPNs können Traffic umleiten. Root kann Schutz umgehen.
**Erkennung:** Keine onRevoke‑Handler; missing PlayIntegrity checks.
**Fix:** Implementiere `onRevoke()` → sofort stop/lock. Detect other VPNs, use Play Integrity / SafetyNet, warn user and disable sensitive features on root.
**Kurz:** Monitor VPN state + device integrity.

---

## Quick‑Checkliste für Entwickler (Copy/Paste)

* [ ] Keine unauthenticated TCP/WS auf 127.0.0.1.
* [ ] Control in Bound Service (protectionLevel=signature) oder LocalSocket + peer UID checks.
* [ ] Policies nur signed + expire.
* [ ] Keine Klartext DNS‑Logs. Nur hashed/aggregated.
* [ ] Per‑client creds + HMAC/mTLS + nonce/timestamp.
* [ ] Use DoH/DoT + cert‑pinning where possible.
* [ ] HTTPS + signed update payloads.
* [ ] Implement `onRevoke()` and detect VPN changes.
* [ ] Detect root & disable sensitive features.
* [ ] SAST + Code review specifically for IPC patterns.

---

## Mini‑Snippets (defensive patterns)

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

**Policy verification (high‑level):**

* Download `policy.json` + `policy.sig`.
* Verify `sig` with embedded public key.
* Check `expires`. Then apply.

---

## Abschluss — Prioritäten (1‑Satz)

1. Sofort: entferne unauthenticated localhost endpoints.
2. Kurzfristig: trenne Control & Data, prüfe Caller UID.
3. Mittelfristig: signierte Policies + DoH + Telemetrie minimal.


