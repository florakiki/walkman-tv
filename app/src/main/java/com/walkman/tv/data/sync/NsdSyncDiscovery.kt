package com.walkman.tv.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * mDNS 发现：同一 WiFi 下，电视和手机互相看到对方，不用输 IP。
 * 服务类型 _kitvmusic._tcp，服务名就是设备名（冲突时系统自动改名）。
 * 注意：部分路由器开了 AP 隔离会挡住 mDNS，那时用扫码 / 手动输地址一样能连。
 */
class NsdSyncDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    data class DiscoveredPeer(val serviceName: String, val host: String?, val port: Int) {
        val id: String get() = "nsd:$serviceName"
        val displayName: String get() = serviceName
    }

    private val _discovered = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discovered: StateFlow<List<DiscoveredPeer>> = _discovered.asStateFlow()

    private var registeredName: String? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discovering = false

    /** 对外广播本机同步服务（App 启动后调一次；端口是 LocalServer 的实际绑定端口）。 */
    fun advertise(port: Int, deviceName: String) {
        if (registrationListener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
                Log.i(TAG, "advertised as ${info.serviceName} on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "registerService threw", it) }
    }

    fun stopAdvertising() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
    }

    /** 开始找局域网里的其它 KITV音乐（进同步页时调，离开时 stopDiscovery）。 */
    fun startDiscovery() {
        if (discovering) return
        discovering = true
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
                Log.w(TAG, "discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceName == registeredName) return // 自己
                runCatching {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress
                            if (host == null) return
                            val peer = DiscoveredPeer(info.serviceName, host, info.port)
                            _discovered.value = (_discovered.value.filter { it.id != peer.id } + peer)
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _discovered.value = _discovered.value.filter { it.serviceName != service.serviceName }
            }
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovering = false; Log.w(TAG, "discoverServices threw", it) }
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    companion object {
        private const val TAG = "NsdSync"
        const val SERVICE_TYPE = "_kitvmusic._tcp"
    }
}
