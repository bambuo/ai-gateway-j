package ai.gateway

data class DeviceFingerprint(
    val deviceId: String,
    val userAgent: String,
    val ipAddress: String,
) {
    fun obfuscatedDeviceId(): String {
        require(deviceId.length >= 8) { "deviceId too short" }
        return "${deviceId.take(8)}****${deviceId.takeLast(4)}"
    }

    companion object {
        private val IP_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

        fun isValidIp(ip: String): Boolean = ip.matches(IP_REGEX)

        fun maskIp(ip: String): String {
            if (!isValidIp(ip)) return ip
            val parts = ip.split(".")
            return "${parts[0]}.${parts[1]}.*.*"
        }
    }
}
