package dev.couchpad.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.couchpad.ControllerHub
import dev.couchpad.boxart.BoxArtStore
import dev.couchpad.CouchpadApp
import dev.couchpad.domain.Console
import dev.couchpad.domain.ControllerButton
import dev.couchpad.domain.ControllerEvent
import dev.couchpad.domain.GridNavigator
import dev.couchpad.network.TvHotspot
import dev.couchpad.server.LocalAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Home screen: QR to join, who is connected, and the game library. Any phone's
 * joystick (or the TV remote) moves the selection; A / Start / OK plays.
 */
class LobbyActivity : ComponentActivity(), ControllerHub.Listener {
    private lateinit var hub: ControllerHub
    private lateinit var gamesColumn: LinearLayout
    private lateinit var scroll: ScrollView
    private val playerRows = mutableMapOf<Int, PlayerRow>()
    private val cards = mutableListOf<View>()
    private var games: List<String> = emptyList()
    private var selected = 0
    private val navigator = GridNavigator(COLUMNS)
    private val boxArt by lazy { BoxArtStore(File(filesDir, "boxart")) }
    private lateinit var joinPanel: LinearLayout
    private lateinit var fastJoinPanel: FastJoinPanel
    private var askedForWifiPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hub = (application as CouchpadApp).hub
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        hub.listener = this
        hub.setInGame(false)
        refreshGames()
        refreshPlayers()
        renderJoinPanel(hub.directNetwork.state)
        refreshFastJoin()
        startDirectNetwork()
    }

    override fun onPause() {
        if (hub.listener === this) hub.listener = null
        super.onPause()
    }

    // ---- Input -------------------------------------------------------------

    override fun onControllerEvent(event: ControllerEvent) {
        runOnUiThread {
            when (event) {
                is ControllerEvent.Button -> if (event.change.pressed) onButton(event.change.button)
                else -> refreshPlayers()
            }
        }
    }

    override fun onGameSelected() {
        runOnUiThread { startActivity(Intent(this, GameActivity::class.java)) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val button = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> ControllerButton.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> ControllerButton.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> ControllerButton.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerButton.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> ControllerButton.A
            else -> return super.onKeyDown(keyCode, event)
        }
        onButton(button)
        return true
    }

    private fun onButton(button: ControllerButton) {
        when (button) {
            ControllerButton.A, ControllerButton.START -> games.getOrNull(selected)?.let(hub::selectGame)
            else -> select(navigator.move(selected, games.size, button))
        }
    }

    // ---- Games -------------------------------------------------------------

    private fun refreshGames() {
        games = hub.library.list()
        gamesColumn.removeAllViews()
        cards.clear()
        if (games.isEmpty()) {
            gamesColumn.addView(text("No games yet.\nOn your phone: ☰ Games → Upload a ROM.", 18f, MUTED).apply {
                setPadding(0, 48.dp, 0, 0)
            })
            return
        }
        games.chunked(COLUMNS).forEach { rowGames ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowGames.forEach { name ->
                val card = gameCard(name)
                cards += card
                row.addView(card, LinearLayout.LayoutParams(CARD_W.dp, CARD_H.dp).apply { setMargins(10.dp, 10.dp, 10.dp, 10.dp) })
            }
            gamesColumn.addView(row)
        }
        val last = games.indexOf(hub.selectedGame)
        select(if (last >= 0) last else selected.coerceIn(0, games.size - 1))
    }

    private fun select(index: Int) {
        if (cards.isEmpty()) return
        selected = index.coerceIn(0, cards.size - 1)
        cards.forEachIndexed { i, card ->
            val on = i == selected
            card.animate().scaleX(if (on) 1.07f else 1f).scaleY(if (on) 1.07f else 1f).setDuration(120).start()
            (card.foreground as GradientDrawable).setStroke(if (on) 3.dp else 0, Color.WHITE)
            card.elevation = if (on) 12.dp.toFloat() else 0f
        }
        val row = cards[selected].parent as View
        scroll.post { scroll.smoothScrollTo(0, (row.top - 16.dp).coerceAtLeast(0)) }
    }

    private fun gameCard(file: String): FrameLayout {
        val title = file.substringBeforeLast('.')
            .replace(Regex("\\s*[\\(\\[].*$"), "")   // drop "(USA)", "[!]" and similar tags
            .ifBlank { file }
        val hue = (abs(title.hashCode()) % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.62f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 30) % 360, 0.65f, 0.28f))
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        val initials = text(initials(title), 54f, Color.argb(70, 255, 255, 255), bold = true)
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply { cornerRadius = 16.dp.toFloat() }
            foreground = GradientDrawable().apply { cornerRadius = 16.dp.toFloat(); setColor(Color.TRANSPARENT) }
            clipToOutline = true
            addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Darkens the bottom so the title stays readable over any box art.
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(210, 0, 0, 0), Color.TRANSPARENT))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (CARD_H * 0.6f).toInt().dp, Gravity.BOTTOM))
            addView(initials, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END,
            ).apply { setMargins(0, 0, 12.dp, 10.dp) })
            loadBoxArt(file, art, initials)
            // Which console runs it: SNES, NES, GENESIS, GBA (or GB / GBC).
            Console.forFile(file)?.let { console ->
                addView(text(console.badge(file), 10f, Color.WHITE, bold = true).apply {
                    letterSpacing = 0.1f
                    setPadding(7.dp, 2.dp, 7.dp, 2.dp)
                    background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(Color.argb(170, 0, 0, 0)) }
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
                ).apply { setMargins(0, 10.dp, 10.dp, 0) })
            }
            addView(text(title, 17f, Color.WHITE, bold = true).apply {
                maxLines = 2
                setShadowLayer(6f, 0f, 2f, Color.argb(160, 0, 0, 0))
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
            ).apply { setMargins(14.dp, 0, 14.dp, 12.dp) })
            if (file == hub.selectedGame) {
                addView(text("LAST PLAYED", 10f, Color.WHITE, bold = true).apply {
                    letterSpacing = 0.12f
                    setPadding(8.dp, 3.dp, 8.dp, 3.dp)
                    background = GradientDrawable().apply { cornerRadius = 99f; setColor(Color.argb(110, 0, 0, 0)) }
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
                ).apply { setMargins(12.dp, 12.dp, 0, 0) })
            }
        }
    }

    /** Downloads (once) and fades in the cover; the coloured card stays if there is none. */
    private fun loadBoxArt(rom: String, target: ImageView, initials: View) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { boxArt.fetch(rom)?.let { decodeScaled(it, CARD_W.dp) } }.getOrNull()
            } ?: return@launch
            target.setImageBitmap(bitmap)
            target.animate().alpha(1f).setDuration(250).start()
            initials.visibility = View.GONE
        }
    }

    /** Decodes at roughly [targetWidth] px so covers do not eat the TV's 1 GB of RAM. */
    private fun decodeScaled(file: File, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun initials(title: String) = title.split(Regex("\\s+"))
        .filter { it.firstOrNull()?.isLetterOrDigit() == true }
        .take(2).joinToString("") { it.first().uppercase() }

    // ---- Players -----------------------------------------------------------

    private fun refreshPlayers() {
        val connected = hub.connectedPlayers()
        val latencies = hub.latencies()
        val direct = hub.directPlayers()
        playerRows.forEach { (player, row) -> row.update(player in connected, latencies[player], player in direct) }
    }

    // ---- Layout ------------------------------------------------------------

    private fun buildLayout(): View {
        joinPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        renderJoinPanel(hub.directNetwork.state)

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("COUCHPAD", 22f, ACCENT, bold = true).apply { letterSpacing = 0.3f })
            addView(View(context), LinearLayout.LayoutParams(1, 20.dp))
            addView(joinPanel)
            addView(View(context), LinearLayout.LayoutParams(1, 24.dp))
            addView(sectionTitle("PLAYERS"))
            (1..ControllerHub.MAX_PLAYERS).forEach { player ->
                addView(PlayerRow(context, player, PLAYER_COLORS.getValue(player)).also { playerRows[player] = it })
            }
        }

        gamesColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 6.dp, 0, 24.dp) }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(gamesColumn)
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(sectionTitle("GAMES"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text("Joystick or remote to move  ·  A to play", 13f, MUTED))
            })
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(28, 26, 44), Color.rgb(12, 12, 18)))
            setPadding(48.dp, 36.dp, 40.dp, 0)
            addView(left, LinearLayout.LayoutParams(250.dp, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = 32.dp })
        }
        fastJoinPanel = FastJoinPanel(this)
        return FrameLayout(this).apply {
            addView(content)
            addView(fastJoinPanel)
        }
    }

    // ---- Joining ----------------------------------------------------------

    /**
     * One QR: the controller on the home Wi-Fi, so anyone can play right away.
     * The TV's own Wi-Fi is an opt-in upgrade from the phone ("⚡ Faster
     * connection"), which brings up [FastJoinPanel].
     */
    private fun renderJoinPanel(state: TvHotspot.State) {
        joinPanel.removeAllViews()
        val hotspotAddress = (state as? TvHotspot.State.On)?.info?.address
        val homeUrl = LocalAddress.find(exclude = setOfNotNull(hotspotAddress))?.let(::controllerUrl)
        if (homeUrl == null) {
            joinPanel.addView(text("No network.\nConnect the TV to Wi-Fi.", 16f, Color.WHITE))
            return
        }
        joinPanel.addView(qrCard(homeUrl, 176), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        joinPanel.addView(text("Scan to join", 16f, Color.WHITE, bold = true).apply { setPadding(0, 12.dp, 0, 0) })
        joinPanel.addView(text(homeUrl, 11f, MUTED))
        val note = when (state) {
            is TvHotspot.State.On -> "⚡ Lag? Tap ⚡ on your phone for a direct connection."
            TvHotspot.State.Starting -> "Starting the TV's own Wi-Fi…"
            is TvHotspot.State.Failed -> "TV Wi-Fi unavailable: ${state.reason}"
            else -> null
        }
        note?.let { joinPanel.addView(text(it, 11f, if (state is TvHotspot.State.On) Color.WHITE else MUTED).apply { setPadding(0, 8.dp, 0, 0) }) }
    }

    private fun refreshFastJoin() = fastJoinPanel.update(hub.directNetworkInfo(), hub.fastJoinRequests())

    override fun onFastJoinChanged() = runOnUiThread {
        refreshFastJoin()
        refreshPlayers()
    }

    private fun qrCard(content: String, sizeDp: Int) = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = 14.dp.toFloat(); setColor(Color.WHITE) }
        setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        addView(ImageView(context).apply { setImageBitmap(QrCode.render(content, sizeDp.dp)) }, FrameLayout.LayoutParams(sizeDp.dp, sizeDp.dp))
    }

    private fun controllerUrl(host: String) = "http://$host:${ControllerHub.PORT}/?t=${hub.token}"

    override fun onDirectNetworkChanged(state: TvHotspot.State) {
        renderJoinPanel(state)
        refreshFastJoin()
    }

    /** The TV's Wi-Fi needs the "nearby Wi-Fi devices" permission on Android 13+. */
    private fun startDirectNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) {
            hub.startDirectNetwork()
        } else if (!askedForWifiPermission) {
            askedForWifiPermission = true   // ask once per screen; declining keeps the home-network QR
            wifiPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private val wifiPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) hub.startDirectNetwork()
    }

    private fun sectionTitle(value: String) = text(value, 12f, MUTED, bold = true).apply {
        letterSpacing = 0.18f
        setPadding(0, 0, 0, 10.dp)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    /** One line in the players list: colour dot, name, and connection state with latency. */
    private class PlayerRow(context: android.content.Context, player: Int, private val dotColor: Int) : LinearLayout(context) {
        private val dot = View(context)
        private val status = TextView(context).apply { textSize = 13f }
        private val density = resources.displayMetrics.density

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            addView(dot, LayoutParams((12 * density).toInt(), (12 * density).toInt()).apply { rightMargin = (12 * density).toInt() })
            addView(TextView(context).apply {
                text = "Player $player"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(status)
            update(connected = false, latencyMs = null)
        }

        fun update(connected: Boolean, latencyMs: Int?, direct: Boolean = false) {
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (connected) dotColor else Color.rgb(70, 70, 90))
            }
            val via = if (direct) "⚡ " else ""
            status.text = when {
                !connected -> "waiting…"
                latencyMs == null -> "${via}connected"
                else -> "$via$latencyMs ms"
            }
            status.setTextColor(when {
                !connected -> MUTED
                latencyMs == null || latencyMs < 30 -> Color.rgb(74, 222, 128)
                latencyMs < 60 -> Color.rgb(250, 204, 21)
                else -> Color.rgb(248, 113, 113)
            })
        }
    }

    private companion object {
        const val COLUMNS = 3
        // 3 columns must fit the ~590 dp next to the QR column on a 960 dp wide TV, plus the selected card's zoom.
        const val CARD_W = 172
        const val CARD_H = 104
        val ACCENT = Color.rgb(124, 92, 255)
        val MUTED = Color.rgb(140, 140, 166)
        val PLAYER_COLORS = mapOf(1 to Color.rgb(255, 107, 107), 2 to Color.rgb(77, 171, 247))
    }
}
