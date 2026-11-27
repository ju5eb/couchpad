package dev.couchpad.boxart

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

class BoxArtStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val available = setOf("Super Bomberman 5 (Japan)")
    private val requests = mutableListOf<String>()
    private lateinit var server: ServerSocket

    /** Minimal HTTP/1.0 server: serves [png] for names in [available], 404 otherwise. */
    @Before
    fun startServer() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val path = it.getInputStream().bufferedReader().readLine().split(' ')[1]
                    val name = URLDecoder.decode(path.removePrefix("/art/").removeSuffix(".png"), "UTF-8")
                    synchronized(requests) { requests += name }
                    val out = it.getOutputStream()
                    if (name in available) {
                        out.write("HTTP/1.0 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n\r\n".toByteArray())
                        out.write(png)
                    } else {
                        out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    }
                    out.flush()
                }
            }
        }
    }

    @After
    fun stopServer() = server.close()

    private fun store() = BoxArtStore(temp.root, "http://127.0.0.1:${server.localPort}/art")

    @Test
    fun `finds art through a fallback name and caches it`() {
        val file = store().fetch("Super Bomberman 5 Gold Cartridge (J) ___.smc")

        assertNotNull(file)
        assertArrayEquals(png, file!!.readBytes())
        val before = synchronized(requests) { requests.size }
        assertNotNull(store().fetch("Super Bomberman 5 Gold Cartridge (J) ___.smc"))
        assertEquals("second fetch must come from the cache", before, synchronized(requests) { requests.size })
    }

    @Test
    fun `a definitive miss is remembered`() {
        assertNull(store().fetch("Latency Test.sfc"))
        val before = synchronized(requests) { requests.size }
        assertNull(store().fetch("Latency Test.sfc"))
        assertEquals("miss must not be retried right away", before, synchronized(requests) { requests.size })
    }

    @Test
    fun `each console looks in its own libretro-thumbnails repository`() {
        val root = "https://raw.githubusercontent.com/libretro-thumbnails"
        assertEquals("$root/Nintendo_-_Nintendo_Entertainment_System/master/Named_Boxarts", BoxArtStore.libretroThumbnails("Mario.nes"))
        assertEquals("$root/Sega_-_Mega_Drive_-_Genesis/master/Named_Boxarts", BoxArtStore.libretroThumbnails("Sonic.md"))
        assertEquals("$root/Nintendo_-_Game_Boy_Color/master/Named_Boxarts", BoxArtStore.libretroThumbnails("Zelda.gbc"))
        assertNull(BoxArtStore.libretroThumbnails("notes.txt"))
    }

    @Test
    fun `network failures are not remembered as misses`() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val offline = BoxArtStore(temp.root, "http://127.0.0.1:$unusedPort/art")

        assertNull(offline.fetch("Top Gear (USA).sfc"))
        assertFalse(File(temp.root, "Top Gear (USA).sfc.miss").exists())
    }
}
