package com.bitchat.android.mesh

import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.security.SecureRandom

/**
 * Signed QR "trust cards" for the flood-authorization allowlist (see
 * [AuthorizedSendersManager]).
 *
 * Scanning someone's trust card and having its signature check out proves they hold the
 * private key for the identity being authorized — nothing more. It does NOT vouch for
 * their trustworthiness; that judgment (typically made by physically meeting the person
 * showing the code) stays with whoever scans it, same as bitchat's existing safety-number
 * verification QR.
 *
 * Deliberately mirrors [com.bitchat.android.services.VerificationService]'s pattern —
 * canonical-byte signing, nonce + timestamp replay protection — but with its own context
 * string and URI host, so a trust card can never be replayed as, or confused with, a
 * safety-number verification QR.
 */
object TrustQrService {
    private const val CONTEXT = "bitchat-trust-v1"
    private const val SCHEME = "bitchat"
    private const val HOST = "trust"

    private var encryptionServiceRef: WeakReference<EncryptionService>? = null

    fun configure(encryptionService: EncryptionService) {
        this.encryptionServiceRef = WeakReference(encryptionService)
    }

    data class TrustQR(
        val v: Int,
        val noiseKeyHex: String,
        val signKeyHex: String,
        val nickname: String,
        val ts: Long,
        val nonceB64: String,
        val sigHex: String
    ) {
        /**
         * The flood-authorization fingerprint this card vouches for: SHA-256(noise key),
         * normalized to lowercase to match [AuthorizedSendersManager]'s storage format.
         */
        val fingerprint: String
            get() = PeerFingerprintManager.fingerprintFor(noiseKeyHex.dataFromHexString() ?: ByteArray(0))
                .lowercase()

        fun canonicalBytes(): ByteArray {
            val out = ByteArrayOutputStream()

            fun appendField(value: String) {
                val data = value.toByteArray(Charsets.UTF_8)
                val len = minOf(data.size, 255)
                out.write(len)
                out.write(data, 0, len)
            }

            appendField(CONTEXT)
            appendField(v.toString())
            appendField(noiseKeyHex.lowercase())
            appendField(signKeyHex.lowercase())
            appendField(nickname)
            appendField(ts.toString())
            appendField(nonceB64)
            return out.toByteArray()
        }

        fun toUrlString(): String {
            return Uri.Builder()
                .scheme(SCHEME)
                .authority(HOST)
                .appendQueryParameter("v", v.toString())
                .appendQueryParameter("noise", noiseKeyHex)
                .appendQueryParameter("sign", signKeyHex)
                .appendQueryParameter("nick", nickname)
                .appendQueryParameter("ts", ts.toString())
                .appendQueryParameter("nonce", nonceB64)
                .appendQueryParameter("sig", sigHex)
                .build()
                .toString()
        }

        companion object {
            fun fromUrlString(urlString: String): TrustQR? {
                val uri = runCatching { urlString.toUri() }.getOrNull() ?: return null
                if (uri.scheme != SCHEME || uri.host != HOST) return null

                val v = uri.getQueryParameter("v")?.toIntOrNull() ?: return null
                val noise = uri.getQueryParameter("noise") ?: return null
                val sign = uri.getQueryParameter("sign") ?: return null
                val nick = uri.getQueryParameter("nick") ?: return null
                val ts = uri.getQueryParameter("ts")?.toLongOrNull() ?: return null
                val nonce = uri.getQueryParameter("nonce") ?: return null
                val sig = uri.getQueryParameter("sig") ?: return null

                return TrustQR(v, noise, sign, nick, ts, nonce, sig)
            }
        }
    }

    /** Build a signed trust card for my own identity, to render as a QR code. */
    fun buildMyTrustQRString(nickname: String): String? {
        val service = encryptionServiceRef?.get() ?: return null
        val noiseKey = service.getStaticPublicKey()?.hexEncodedString() ?: return null
        val signKey = service.getSigningPublicKey()?.hexEncodedString() ?: return null
        val ts = System.currentTimeMillis() / 1000L
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonceB64 = Base64.encodeToString(nonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val payload = TrustQR(
            v = 1,
            noiseKeyHex = noiseKey,
            signKeyHex = signKey,
            nickname = nickname,
            ts = ts,
            nonceB64 = nonceB64,
            sigHex = ""
        )
        val signature = service.signData(payload.canonicalBytes()) ?: return null
        return payload.copy(sigHex = signature.hexEncodedString()).toUrlString()
    }

    /**
     * Verify a scanned trust card. Returns it — with a derived [TrustQR.fingerprint] —
     * only if the signature checks out and the card isn't stale. This is the only gate
     * before a fingerprint reaches [AuthorizedSendersManager.addAuthorizedFingerprint], so
     * both checks matter: staleness rejects a photographed/reused old card, and the
     * signature rejects a forged one.
     */
    fun verifyScannedTrustQR(
        urlString: String,
        maxAgeSeconds: Long = AppConstants.Verification.QR_MAX_AGE_SECONDS
    ): TrustQR? {
        val service = encryptionServiceRef?.get() ?: return null
        val qr = TrustQR.fromUrlString(urlString) ?: return null

        val now = System.currentTimeMillis() / 1000L
        if (now - qr.ts > maxAgeSeconds) return null

        val sig = qr.sigHex.dataFromHexString() ?: return null
        val signKey = qr.signKeyHex.dataFromHexString() ?: return null
        val ok = service.verifyEd25519Signature(sig, qr.canonicalBytes(), signKey)
        return if (ok) qr else null
    }
}
