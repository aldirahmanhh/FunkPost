package com.gabut.autopost.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icons Material's *core* icon set is missing, drawn here so the app does not have to pull in
 * `material-icons-extended` (a very large dependency for a couple of glyphs).
 *
 * All are drawn on the standard 24x24 viewport and are tinted by `Icon`, so the fill color is
 * only a placeholder.
 */
object AutoPostIcons {

    /** A sun: shown while the app is in dark mode, meaning "switch to light". */
    val Sun: ImageVector by lazy {
        ImageVector.Builder(
            name = "Sun",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round
            ) {
                circle(12f, 12f, 4.6f)
                ray(12f, 2.6f, 12f, 5.4f)
                ray(12f, 18.6f, 12f, 21.4f)
                ray(2.6f, 12f, 5.4f, 12f)
                ray(18.6f, 12f, 21.4f, 12f)
                ray(5.1f, 5.1f, 7.1f, 7.1f)
                ray(16.9f, 16.9f, 18.9f, 18.9f)
                ray(18.9f, 5.1f, 16.9f, 7.1f)
                ray(7.1f, 16.9f, 5.1f, 18.9f)
            }
        }.build()
    }

    /** Discord's mark, so a channel row reads as a Discord target, not a bare id. */
    val Discord: ImageVector by lazy {
        ImageVector.Builder(
            name = "Discord",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = PathParser().parsePathString(DISCORD_PATH).toNodes(),
            fill = SolidColor(Color.Black)
        ).build()
    }

    private const val DISCORD_PATH =
        "M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189z"

    /** A crescent moon: shown while the app is in light mode, meaning "switch to dark". */
    val Moon: ImageVector by lazy {
        ImageVector.Builder(
            name = "Moon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Two overlapping circles with the even-odd rule: fill = A minus B, i.e. a crescent.
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd
            ) {
                circle(11.5f, 12f, 9f)
                circle(17.5f, 7.5f, 9f)
            }
        }.build()
    }
}

private fun PathBuilder.ray(x1: Float, y1: Float, x2: Float, y2: Float) {
    moveTo(x1, y1)
    lineTo(x2, y2)
}

/** PathBuilder has no addOval, so a circle is two half arcs. */
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, false, cx + r, cy)
    arcTo(r, r, 0f, true, false, cx - r, cy)
    close()
}
