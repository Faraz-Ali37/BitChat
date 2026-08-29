package com.bitchat.android.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A single dropped MESSAGE/FILE_TRANSFER packet, recorded for display so the effect of
 * the allowlist is visible in the UI in real time rather than a silent no-op.
 */
data class BlockedFloodAttempt(
    val fingerprint: String,
    val peerID: String,
    val nickname: String,
    val messageType: String,
    val timestamp: Long
)

/**
 * Flood-authorization allowlist.
 *
 * When enabled, [SecurityManager] consults this manager to decide whether a public
 * broadcast packet (MESSAGE / FILE_TRANSFER — the packet types that get flooded across
 * the whole mesh) from a given peer is allowed to be accepted and relayed onward.
 * Only peers whose verified identity fingerprint is in [authorizedFingerprints] pass.
 *
 * This is a *local, per-device* policy. For it to behave as a network-wide restriction,
 * every relaying node needs the feature turned on with a matching set of fingerprints —
 * there is no central authority to push that config in a decentralized mesh, so
 * fingerprints must be shared and entered on each device out-of-band (e.g. read aloud,
 * QR code, or copy/paste over another trusted channel).
 *
 * Disabled by default so a stock build keeps today's open-relay behavior.
 */
class AuthorizedSendersManager private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: AuthorizedSendersManager? = null

        fun getInstance(): AuthorizedSendersManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthorizedSendersManager().also { INSTANCE = it }
            }
        }

        // Bounded so a determined unauthorized sender can't grow this list forever;
        // it only needs to be big enough to be visibly useful in a live demo/debug view.
        private const val MAX_BLOCKED_ATTEMPTS = 50
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _authorizedFingerprints = MutableStateFlow<Set<String>>(emptySet())
    val authorizedFingerprints: StateFlow<Set<String>> = _authorizedFingerprints.asStateFlow()

    // In-memory only (not persisted): this is a live "is it working" signal for the
    // current session, not an audit trail, so it intentionally resets on app restart.
    private val _blockedAttempts = MutableStateFlow<List<BlockedFloodAttempt>>(emptyList())
    val blockedAttempts: StateFlow<List<BlockedFloodAttempt>> = _blockedAttempts.asStateFlow()

    init {
        try {
            _enabled.value = AuthorizedSendersPreferenceManager.getEnabled(false)
            _authorizedFingerprints.value = AuthorizedSendersPreferenceManager.getAuthorizedFingerprints()
        } catch (_: Exception) {
            // Preferences not ready yet (e.g. accessed before BitchatApplication.onCreate);
            // keep in-memory defaults and persist normally on the next mutation.
        }
    }

    /**
     * Re-read persisted values from [AuthorizedSendersPreferenceManager]. Call this once
     * preferences are known to be initialized (e.g. from BitchatApplication.onCreate) to
     * cover the case where this singleton was first touched earlier than that with defaults.
     */
    fun reloadFromPreferences() {
        try {
            _enabled.value = AuthorizedSendersPreferenceManager.getEnabled(false)
            _authorizedFingerprints.value = AuthorizedSendersPreferenceManager.getAuthorizedFingerprints()
        } catch (_: Exception) { }
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        try { AuthorizedSendersPreferenceManager.setEnabled(value) } catch (_: Exception) { }
    }

    /**
     * True if [fingerprint] (hex SHA-256 of a peer's Noise static public key — see
     * [PeerFingerprintManager.fingerprintFor]) is on the allowlist.
     */
    fun isAuthorized(fingerprint: String): Boolean {
        return _authorizedFingerprints.value.contains(fingerprint.lowercase())
    }

    fun addAuthorizedFingerprint(fingerprint: String) {
        val normalized = fingerprint.trim().lowercase()
        if (normalized.isEmpty()) return
        val updated = _authorizedFingerprints.value + normalized
        _authorizedFingerprints.value = updated
        try { AuthorizedSendersPreferenceManager.setAuthorizedFingerprints(updated) } catch (_: Exception) { }
    }

    fun removeAuthorizedFingerprint(fingerprint: String) {
        val normalized = fingerprint.trim().lowercase()
        val updated = _authorizedFingerprints.value - normalized
        _authorizedFingerprints.value = updated
        try { AuthorizedSendersPreferenceManager.setAuthorizedFingerprints(updated) } catch (_: Exception) { }
    }

    /**
     * Record a dropped MESSAGE/FILE_TRANSFER packet so it's visible in the UI. Called by
     * [SecurityManager] at the exact point a packet is rejected — see
     * SecurityManager.isAuthorizedToFlood.
     */
    fun recordBlockedAttempt(fingerprint: String, peerID: String, nickname: String, messageType: String) {
        val attempt = BlockedFloodAttempt(
            fingerprint = fingerprint,
            peerID = peerID,
            nickname = nickname,
            messageType = messageType,
            timestamp = System.currentTimeMillis()
        )
        // Newest first, capped.
        _blockedAttempts.value = (listOf(attempt) + _blockedAttempts.value).take(MAX_BLOCKED_ATTEMPTS)
    }

    fun clearBlockedAttempts() {
        _blockedAttempts.value = emptyList()
    }

    fun getDebugInfo(): String = buildString {
        appendLine("=== Authorized Senders Manager Debug Info ===")
        appendLine("Enabled: ${_enabled.value}")
        appendLine("Authorized fingerprint count: ${_authorizedFingerprints.value.size}")
        appendLine("Blocked attempts this session: ${_blockedAttempts.value.size}")
    }
}
