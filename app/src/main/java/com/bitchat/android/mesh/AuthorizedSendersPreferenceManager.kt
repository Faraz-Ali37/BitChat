package com.bitchat.android.mesh

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed persistence for the flood-authorization allowlist.
 *
 * Stores:
 *  - a master enable/disable flag (feature defaults OFF: an un-configured node keeps
 *    today's open-relay behavior, so this is strictly opt-in)
 *  - the set of authorized sender fingerprints (hex-encoded SHA-256 of each peer's
 *    Noise static public key, i.e. the same "fingerprint" already surfaced by
 *    VerificationSheet / PeerFingerprintManager)
 *
 * NOTE: This allowlist is local to this device/app install. There is no central
 * authority in the mesh, so "network-wide" enforcement only holds if every
 * participating node runs this build with flood-authorization enabled and is
 * configured with the same set of trusted fingerprints (share fingerprints via
 * an out-of-band channel and add them on each device).
 */
object AuthorizedSendersPreferenceManager {
    private const val PREFS_NAME = "bitchat_authorized_senders"
    private const val KEY_ENABLED = "flood_authorization_enabled"
    private const val KEY_FINGERPRINTS = "authorized_fingerprints"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ready(): Boolean = ::prefs.isInitialized

    fun getEnabled(default: Boolean = false): Boolean =
        if (ready()) prefs.getBoolean(KEY_ENABLED, default) else default

    fun setEnabled(value: Boolean) {
        if (ready()) prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun getAuthorizedFingerprints(): Set<String> =
        if (ready()) (prefs.getStringSet(KEY_FINGERPRINTS, null) ?: emptySet()).toSet() else emptySet()

    fun setAuthorizedFingerprints(fingerprints: Set<String>) {
        if (ready()) {
            // Copy into a fresh HashSet: SharedPreferences must not persist a mutable set
            // instance that the caller might go on to mutate in place.
            prefs.edit().putStringSet(KEY_FINGERPRINTS, HashSet(fingerprints)).apply()
        }
    }
}
