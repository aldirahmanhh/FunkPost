package com.gabut.autopost.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.gabut.autopost.ui.components.BrandMark
import com.gabut.autopost.ui.components.DiscordMark
import com.gabut.autopost.ui.components.InitialAvatar
import com.gabut.autopost.ui.components.SectionHeader
import com.gabut.autopost.ui.components.SoftDivider
import com.gabut.autopost.ui.i18n.t

private data class CreditLink(
    val label: String,
    val subtitle: String,
    val url: String,
    val icon: ImageVector
)

/**
 * About tab: identity, what the app does, and the privacy promise.
 *
 * Everything here is static. The only outbound action is a link the user taps, so opening this
 * tab is provably network-free (IS-8).
 */
@Composable
fun CreditScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val versionName = remember(context) {
        @Suppress("DEPRECATION")
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "1.0" }
    }
    val links = listOf(
        CreditLink(
            label = "Tako",
            subtitle = "tako.id/anrizz",
            url = "https://tako.id/anrizz",
            icon = Icons.Filled.Favorite
        )
    )

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AboutHero(versionName = versionName)

        SectionHeader(
            title = t("What it does", "Fungsinya"),
            subtitle = t(
                "Posting and verification in one app",
                "Posting dan verifikasi dalam satu app"
            )
        )
        FeatureCard()

        SectionHeader(
            title = t("Privacy", "Privasi"),
            subtitle = t(
                "Nothing leaves this device except Discord calls",
                "Tidak ada yang keluar dari perangkat ini selain koneksi ke Discord"
            )
        )
        PrivacyCard()

        SectionHeader(title = t("Credits", "Kredit"))
        CreditsCard()

        SectionHeader(
            title = t("Links", "Tautan"),
            subtitle = t("Opens in your browser", "Terbuka di browser kamu")
        )
        links.forEach { link ->
            AboutLinkCard(link = link) { openLink(context, link.url) }
        }

        AboutFooter(versionName = versionName)
    }
}

@Composable
private fun AboutHero(versionName: String) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(primary, secondary)))
            .drawBehind {
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f),
                    radius = size.width * 0.42f,
                    center = Offset(size.width * 0.96f, size.height * 0.02f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.07f),
                    radius = size.width * 0.30f,
                    center = Offset(size.width * 0.04f, size.height * 1.02f)
                )
            }
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
                ) {
                    BrandMark(size = 62.dp, modifier = Modifier.padding(5.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "FunkPost",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = t("Discord auto-poster", "Auto-poster Discord"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.86f)
                    )
                }
            }
            Text(
                text = t(
                    "Schedule posts, watch channels and keep verification panels moving, " +
                        "all from one phone.",
                    "Jadwalkan posting, pantau channel, dan jalankan panel verifikasi, " +
                        "semua dari satu HP."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.92f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip("v$versionName")
                HeroChip(t("On-device", "Di perangkat"))
                HeroChip(t("No ads", "Tanpa iklan"))
            }
        }
    }
}

@Composable
private fun HeroChip(text: String) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun FeatureCard() {
    AboutCard {
        AboutRow(
            icon = Icons.AutoMirrored.Filled.Send,
            title = t("Scheduled posts", "Posting terjadwal"),
            body = t(
                "Each channel repeats on its own interval, per bot, until you stop it.",
                "Setiap channel berulang sesuai intervalnya sendiri, per bot, sampai kamu hentikan."
            )
        )
        SoftDivider()
        AboutRow(
            icon = Icons.Filled.CheckCircle,
            title = t("Auto-verify", "Verifikasi otomatis"),
            body = t(
                "Watches the channel you pick and presses Authenticate and Yes, Log Me In " +
                    "only for panels that belong to your account.",
                "Memantau channel yang kamu pilih dan menekan Authenticate serta Yes, Log Me In " +
                    "hanya untuk panel milik akunmu."
            )
        )
        SoftDivider()
        AboutRow(
            icon = Icons.AutoMirrored.Filled.List,
            title = t("Activity log", "Riwayat aktivitas"),
            body = t(
                "Every send and failure is recorded, newest first and capped so it never grows.",
                "Setiap pengiriman dan kegagalan dicatat, terbaru dulu, dan dibatasi agar tidak membengkak."
            )
        )
    }
}

@Composable
private fun PrivacyCard() {
    AboutCard {
        AboutRow(
            icon = Icons.Filled.Lock,
            title = t("Encrypted at rest", "Terenkripsi saat disimpan"),
            body = t(
                "Bot and user tokens are sealed with AES-256-GCM; the key is held by the " +
                    "Android Keystore and never leaves the device.",
                "Token bot dan user disegel dengan AES-256-GCM; kuncinya dipegang Android " +
                    "Keystore dan tidak pernah keluar dari perangkat."
            )
        )
        SoftDivider()
        AboutRow(
            icon = Icons.Filled.CheckCircle,
            title = t("No telemetry", "Tanpa telemetri"),
            body = t(
                "No analytics or ad SDKs. Requests go only to discord.com, using the tokens " +
                    "you provide.",
                "Tanpa analytics atau SDK iklan. Permintaan hanya ke discord.com, memakai token " +
                    "yang kamu berikan."
            )
        )
    }
}

@Composable
private fun CreditsCard() {
    AboutCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(name = "MrFunk", size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MrFunk",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = t("Built and designed by", "Dibuat dan didesain oleh"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Text(
                    text = t("Author", "Penulis"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        SoftDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiscordMark(size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = t("Built on the Discord API", "Dibangun di atas Discord API"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = t(
                        "Posting and verification use your own tokens.",
                        "Posting dan verifikasi memakai token kamu sendiri."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AboutLinkCard(link: CreditLink, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("credit_link_${link.label}")
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = link.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = link.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = link.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = t("Open ${link.label}", "Buka ${link.label}"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AboutFooter(versionName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiscordMark(size = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "FunkPost v$versionName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = t("Made for Discord communities", "Dibuat untuk komunitas Discord"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun AboutRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }.onFailure {
        Toast.makeText(
            context,
            t("No app can open this link", "Tidak ada app yang bisa membuka tautan ini"),
            Toast.LENGTH_SHORT
        ).show()
    }
}
