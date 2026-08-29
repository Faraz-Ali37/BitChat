package com.bitchat.android.ui.debug

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mesh.AuthorizedSendersManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.ui.theme.BitchatFontFamily
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Section for the flood-authorization allowlist: only peers whose verified fingerprint is
 * listed here will have their public messages/files accepted and relayed by this node once
 * enabled. See [AuthorizedSendersManager] and SecurityManager.isAuthorizedToFlood for the
 * enforcement side.
 *
 * This is a *local* policy editor. For it to act as a network-wide restriction, every
 * relaying device needs this feature on with the same fingerprint set — share fingerprints
 * with other operators out-of-band and add them on each device.
 */
@Composable
fun AuthorizedSendersSection(meshService: BluetoothMeshService) {
    val colorScheme = MaterialTheme.colorScheme
    val manager = remember { AuthorizedSendersManager.getInstance() }
    val enabled by manager.enabled.collectAsState()
    val authorizedFingerprints by manager.authorizedFingerprints.collectAsState()
    val blockedAttempts by manager.blockedAttempts.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var manualFingerprint by remember { mutableStateOf("") }
    var showTrustQrSheet by remember { mutableStateOf(false) }

    // Own fingerprint, so the operator can share it with other nodes to get authorized there.
    val myFingerprint = remember {
        try { meshService.getIdentityFingerprint() } catch (_: Exception) { null }
    }

    // Nearby peers, refreshed periodically while this section is visible so newly-seen
    // peers show up without needing a dedicated ViewModel wiring.
    var nearbyPeers by remember { mutableStateOf<List<Triple<String, String, String?>>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            val nicknames = try { meshService.getPeerNicknames() } catch (_: Exception) { emptyMap() }
            nearbyPeers = try {
                meshService.getActivePeerIDs().map { peerID ->
                    Triple(peerID, nicknames[peerID] ?: peerID, meshService.getPeerFingerprint(peerID))
                }
            } catch (_: Exception) { emptyList() }
            delay(3000)
        }
    }

    Surface(shape = RoundedCornerShape(12.dp), color = colorScheme.surfaceVariant.copy(alpha = 0.2f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFF34C759))
                Text(
                    "Flood authorization",
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { manager.setEnabled(it) })
            }

            Text(
                "When on, this device only accepts and relays public messages/files from " +
                    "peers whose fingerprint is in the list below. Other traffic (handshakes, " +
                    "presence, private messages) is unaffected. This is a local, per-device " +
                    "setting — every relaying node needs it enabled with the same fingerprints " +
                    "to enforce it network-wide.",
                fontFamily = BitchatFontFamily,
                fontSize = 11.sp,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Divider()

            // Live signal that the block is actually happening — updates the instant
            // SecurityManager drops a packet, no polling needed (real StateFlow).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Blocked attempts (${blockedAttempts.size})",
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (blockedAttempts.isNotEmpty()) {
                    TextButton(onClick = { manager.clearBlockedAttempts() }) { Text("Clear") }
                }
            }
            if (blockedAttempts.isEmpty()) {
                Text(
                    "Nothing blocked yet. Send a public message from an un-authorized " +
                        "device and an entry will appear here the instant it's dropped.",
                    fontFamily = BitchatFontFamily,
                    fontSize = 11.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    blockedAttempts.take(5).forEach { attempt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${attempt.nickname} · ${attempt.messageType}",
                                    fontFamily = BitchatFontFamily,
                                    fontSize = 12.sp,
                                    color = Color(0xFFD32F2F)
                                )
                                Text(
                                    "${attempt.fingerprint.take(12)}… · ${timeFormat.format(Date(attempt.timestamp))}",
                                    fontFamily = BitchatFontFamily,
                                    fontSize = 10.sp,
                                    color = colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            TextButton(onClick = { manager.addAuthorizedFingerprint(attempt.fingerprint) }) {
                                Text("Authorize")
                            }
                        }
                    }
                }
            }

            Divider()

            if (myFingerprint != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Your fingerprint (share this so others can authorize you)",
                            fontFamily = BitchatFontFamily,
                            fontSize = 11.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(myFingerprint, fontFamily = BitchatFontFamily, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(myFingerprint))
                        Toast.makeText(context, "Fingerprint copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                }

                TextButton(onClick = { showTrustQrSheet = true }) {
                    Text("Show / Scan Trust QR")
                }
            }

            Divider()

            Text(
                "Authorized senders (${authorizedFingerprints.size})",
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            if (authorizedFingerprints.isEmpty()) {
                Text(
                    "No authorized senders yet — add a fingerprint below, or authorize a " +
                        "nearby peer from the list.",
                    fontFamily = BitchatFontFamily,
                    fontSize = 11.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    authorizedFingerprints.sorted().forEach { fp ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                fp,
                                fontFamily = BitchatFontFamily,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { manager.removeAuthorizedFingerprint(fp) }) {
                                Text("Revoke")
                            }
                        }
                    }
                }
            }

            // Manual add (for fingerprints shared out-of-band, e.g. not currently nearby)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualFingerprint,
                    onValueChange = { manualFingerprint = it },
                    label = { Text("Paste fingerprint") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = manualFingerprint.trim().isNotEmpty(),
                    onClick = {
                        manager.addAuthorizedFingerprint(manualFingerprint)
                        manualFingerprint = ""
                    }
                ) { Text("Add") }
            }

            if (nearbyPeers.isNotEmpty()) {
                Divider()
                Text(
                    "Nearby peers",
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    nearbyPeers.forEach { (_, nickname, fingerprint) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(nickname, fontFamily = BitchatFontFamily, fontSize = 12.sp)
                                Text(
                                    fingerprint ?: "no fingerprint yet (not handshaked)",
                                    fontFamily = BitchatFontFamily,
                                    fontSize = 10.sp,
                                    color = colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            if (fingerprint != null) {
                                val isAuthorized = authorizedFingerprints.contains(fingerprint.lowercase())
                                TextButton(onClick = {
                                    if (isAuthorized) manager.removeAuthorizedFingerprint(fingerprint)
                                    else manager.addAuthorizedFingerprint(fingerprint)
                                }) {
                                    Text(if (isAuthorized) "Revoke" else "Authorize")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    TrustQrExchangeSheet(
        isPresented = showTrustQrSheet,
        onDismiss = { showTrustQrSheet = false },
        myPeerID = meshService.myPeerID
    )
}
