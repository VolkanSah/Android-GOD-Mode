// erstellt von mir nach best practis, geprüft von KIs wie Claude4.5, GPT5, Deepseek R1, Gemini Pro
// Ein Desaster! So leicht kann ich die architektonische Lücke in eine App bauen,
// und die KIs stufen den Code als "Top!" und "Best Practice" ein.
// Hauptschwaäche was KIs nichjt erkennen erst wenn man sie drauf hinweist:
// onCreate:
// proxyServer = DnsProxyServer(blocklistManager!!, port = 8765) 
// ------------------------------------------------------------------
// startVpnAndProxy:
// proxyServer?.start() // Startet den ungeschützten WS-Server
// ------------------------------------------------------------------
// DAS IST DIE LOGIK DER SYSTEMISCHEN FAULHEIT.
// State-of-the-Art-Code-Generierung und den faulen Architekturen!

package com.volkankucukbudak.dnsblock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class DnsVpnService : VpnService() {
    
    companion object {
        private const val TAG = "DnsVpnService"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val DNS_SERVER = "127.0.0.1"
        private const val DNS_PORT = 53
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "dns_blocker_channel"
    }
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyServer: DnsProxyServer? = null
    private var blocklistManager: BlocklistManager? = null
    private var dnsServerSocket: DatagramSocket? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        createNotificationChannel()
        blocklistManager = BlocklistManager(this)
        proxyServer = DnsProxyServer(blocklistManager!!, port = 8765)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting")
        
        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification())
        
        serviceScope.launch {
            try {
                startVpnAndProxy()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    private suspend fun startVpnAndProxy() = withContext(Dispatchers.IO) {
        // 1. Start WebSocket Proxy Server
        proxyServer?.start()
        Log.d(TAG, "WebSocket proxy started on port 8765")
        
        // 2. Start local DNS server
        startLocalDnsServer()
        
        // 3. Establish VPN
        val builder = Builder()
            .setSession("DNS Blocker")
            .addAddress(VPN_ADDRESS, 24)
            .addDnsServer(DNS_SERVER)
            .addRoute(VPN_ROUTE, 0)
            .setBlocking(false)
            .setMtu(1500)
        
        vpnInterface = builder.establish()
        
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN")
            return@withContext
        }
        
        Log.d(TAG, "VPN established successfully")
        
        // 4. Handle VPN traffic
        handleVpnTraffic(vpnInterface!!)
    }
    
    private fun startLocalDnsServer() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                dnsServerSocket = DatagramSocket(DNS_PORT, InetAddress.getByName(DNS_SERVER))
                Log.d(TAG, "Local DNS server listening on $DNS_SERVER:$DNS_PORT")
                
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (isActive && dnsServerSocket?.isClosed == false) {
                    try {
                        dnsServerSocket?.receive(packet)
                        
                        val queryData = packet.data.copyOf(packet.length)
                        
                        // Process in separate coroutine
                        launch {
                            handleDnsPacket(queryData, packet.address, packet.port)
                        }
                        
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "DNS server error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DNS server", e)
            }
        }
    }
    
    private suspend fun handleDnsPacket(queryData: ByteArray, clientAddress: InetAddress, clientPort: Int) {
        try {
            val query = DnsPacket.parse(queryData) ?: return
            
            Log.d(TAG, "DNS Query: ${query.qname}")
            
            val responseData = if (blocklistManager?.isBlocked(query.qname) == true) {
                Log.d(TAG, "Blocked: ${query.qname}")
                DnsPacket.buildResponse(query, "0.0.0.0")
            } else {
                // Forward to DoH upstream via WebSocket proxy
                // For simplicity, we use DoH directly here
                try {
                    val dohClient = DohClient()
                    dohClient.query(queryData)
                } catch (e: Exception) {
                    Log.e(TAG, "DoH query failed for ${query.qname}", e)
                    DnsPacket.buildNxDomain(query)
                }
            }
            
            // Send response back to client
            val responsePacket = DatagramPacket(
                responseData,
                responseData.size,
                clientAddress,
                clientPort
            )
            dnsServerSocket?.send(responsePacket)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle DNS packet", e)
        }
    }
    
    private suspend fun handleVpnTraffic(vpnInterface: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface.fileDescriptor)
        
        val buffer = ByteBuffer.allocate(32767)
        
        try {
            while (isActive) {
                buffer.clear()
                val length = inputStream.channel.read(buffer)
                
                if (length > 0) {
                    buffer.flip()
                    
                    // Parse IP packet
                    val packet = buffer.array().copyOf(length)
                    
                    // Check if it's a DNS packet (UDP port 53)
                    if (isDnsPacket(packet)) {
                        // DNS packets are handled by our local DNS server
                        // Just forward the packet
                        buffer.rewind()
                        outputStream.channel.write(buffer)
                    } else {
                        // Forward non-DNS traffic normally
                        buffer.rewind()
                        outputStream.channel.write(buffer)
                    }
                }
                
                delay(1) // Prevent tight loop
            }
        } catch (e: Exception) {
            if (isActive) {
                Log.e(TAG, "VPN traffic handling error", e)
            }
        }
    }
    
    private fun isDnsPacket(packet: ByteArray): Boolean {
        if (packet.size < 28) return false // Minimum IP + UDP header
        
        try {
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 17) return false // Not UDP
            
            // Check destination port (UDP header at offset 20)
            val destPort = ((packet[22].toInt() and 0xFF) shl 8) or (packet[23].toInt() and 0xFF)
            return destPort == 53
        } catch (e: Exception) {
            return false
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DNS Blocker Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps DNS blocking active"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stats = blocklistManager?.getStats()
        val contentText = if (stats != null) {
            "Blocked: ${stats.blockedQueries} / ${stats.totalQueries} queries"
        } else {
            "DNS filtering active"
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DNS Blocker Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification())
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service destroying")
        
        serviceScope.cancel()
        
        try {
            dnsServerSocket?.close()
            proxyServer?.stop()
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        
        super.onDestroy()
    }
    
    override fun onRevoke() {
        Log.d(TAG, "VPN permission revoked")
        stopSelf()
    }
}
