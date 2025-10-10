
# 1) Architekturtypen, die DNS‑MitM ermöglichen

1. **VPN‑/Proxy‑Based (local privileged resolver)**

   * App/Service etabliert VPN/Proxy und übernimmt Name‑Resolution (z. B. Adblocker‑VPN).
   * Warum anfällig: Kontrolliert komplette Data‑Plane; lokale control‑APIs können manipuliert.

2. **Localhost/Loopback Resolver‑Pattern**

   * Lokaler DNS‑Daemon (127.0.0.1) im Nutzer‑Device; andere Prozesse können darauf zugreifen.
   * Warum anfällig: fehlende IPC‑Auth erlaubt lokale Apps, Antworten zu injecten.

3. **Transparent Proxy / NAT / Router Level**

   * Router/ISP setzt transparenten DNS‑Proxy oder NAT mit DNS‑Rewrite ein.
   * Warum anfällig: zentraler Punkt, alle Clients betroffen; Manipulation möglich ohne Client‑Konfiguration.

4. **Public Wi‑Fi / Rogue AP**

   * Angreifer kontrolliert Gateway / DNS‑Settings im AP.
   * Warum anfällig: Simple Man‑in‑the‑Middle über DHCP/DNS.

5. **Recursive Resolver Compromise (Resolver Hijack)**

   * Rekursive DNS‑Server (ISP, public resolvers) kompromittiert oder böswillig konfiguriert.
   * Warum anfällig: Provider‑level Manipulation, großflächiger Impact.

6. **CDN / Edge Provider or Caching Proxy**

   * CDN/Edge oder Caching layer manipuliert DNS responses or redirects to malicious origin.
   * Warum anfällig: Vertrauensstellung gegenüber Edge.

7. **Enterprise Middleboxes / Security Appliances**

   * Corporate proxies, parental control, filtering appliances that intercept/resign traffic.
   * Warum anfällig: Oft MITM‑capability by design; misconfig or compromise yields abuse.

8. **Browser Extensions / Local Software Hooks**

   * Extension oder native agent intercepts DNS calls or rewrites network calls.
   * Warum anfällig: Runs in user context; can rewrite requests or DNS mappings.

9. **Compromised Updater / Policy Mechanism**

   * Signed policy updates abused or unsigned updates accepted — malicious rules injected.
   * Warum anfällig: Trusted update channel becomes attack vector.

10. **Rooted/Compromised Devices**

    * Full control over network stack → direct DNS table manipulation.
    * Warum anfällig: all protections can be bypassed.

---

# 2) Was benötigt ein Angreifer jeweils (Kurz)

* **Local VPN/Loopback**: local app privilege + ability to run background service + open control channel.
* **Transparent proxy/router**: control of gateway/ISP or ability to run rogue AP.
* **Resolver compromise**: access to recursive resolver or cache poisoning.
* **Enterprise appliances**: admin access or misconfiguration.
* **Browser/extension**: user installs malicious extension; no extra privileges.

---

# 3) Typische Angriffs‑Patterns / Beispiele

* **Inject fake A/AAAA records** → redirect to phishing IP.
* **NXDOMAIN spoofing** → block services or force fallback to malicious flows.
* **Response replay / delayed poisoning** → cache poisoning at recursive resolver.
* **Selective hijack** → only for targeted domains (banking).
* **Exfil via DNS** → encode data into DNS requests/responses.

---

# 4) Erkennungs‑Indikatoren (fast always useful)

* Plötzliche DNS‑Antworten, die von erwarteter IP abweichen.
* DNS TTLs ungewöhnlich kurz/long oder inkonsistente RRs.
* Unterschied zwischen DoH/DoT upstream vs system resolver.
* Unerwartete lokale Listener auf 127.0.0.1 (netstat/ss).
* Anomalous outbound connections to unknown C2 addresses after DNS queries.
* User‑reports: legit sites zeigen Zertifikat‑Mismatch (SSL Fehler nach Redirect).

---

# 5) Konkrete Gegenmaßnahmen (Priorisiert — kurz)

1. **End‑to‑end Integrity**: DNSSEC für signed data; DoH/DoT with cert validation.
2. **Client‑Side Protection**: resolvers use DoH/DoT with cert‑pinning to trusted upstreams.
3. **Control/Data Separation**: keine unauthenticated localhost control APIs; control only via signed policies or signature‑protected bound services.
4. **Least Privilege**: limit services that can act as resolvers (no default system VPN proxy w/o audit).
5. **Authenticated Control Channels**: mutual TLS, per‑client creds + HMAC + nonces.
6. **Monitoring & IDS**: DNS anomaly detection (unexpected IPs, frequency, NXDOMAINS).
7. **Endpoint Hardening**: detect/rooted devices, disable sensitive features on compromise.
8. **Policy & Supply Chain**: sign policy files, verify signature and expiry.
9. **Network Level**: source address validation (BCP38), secure resolver configs at ISP/network operator.
10. **User Awareness**: warn on new VPN activation; require explicit informed consent and display audit logs.

---

# 6) Forschungsperspektive / offene Fragen (für akademische Arbeit)

* Wie automatisiert man Erkennung von gefährlichen IPC‑Patterns in KI‑generated code?
* Wie misst man praktische Ausnutzbarkeit in real world (mobile sandbox constraints)?
* Trade‑offs: How to balance functionality (adblocker/VPN) vs platform hardening without breaking legit use cases?
* Wie können app‑store vetting heuristics adapt to detect *dangerous composition patterns* (not just malware signatures)?

---

# 7) Ein‑Satz‑Zusammenfassung

Architekturen, die Kontrolle über den Resolver‑Pfad oder die Netzwerk‑Routing‑Ebene erlauben (VPNs, lokale Resolvers, Router/ISP, middleboxes) ermöglichen DNS‑MitM — Abwehr braucht Integritätsprüfungen (DNSSEC/DoH‑with‑pinning), starke Auth für Control‑Channels und Monitoring für Anomalien.
