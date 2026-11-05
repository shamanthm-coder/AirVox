package com.airvox.map

import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert

internal data class MapBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
)

internal data class OfflinePatch(
    val saving: Boolean,
    val progress: Float,
    val savedAreas: Int,
    val detail: String?,
)

/**
 * Street tiles for the area on screen, kept in the app's documents folder.
 * Apple Maps has no download of its own, so these tiles are what shows with no internet.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosOfflineTiles(
    private val listener: (OfflinePatch) -> Unit,
) {
    @Volatile
    private var generation = 0

    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.defaultSessionConfiguration(),
    )
    private val ioQueue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0u)

    fun savedCount(): Int = areaNames().size

    fun hasTiles(): Boolean = savedCount() > 0

    fun template(): String = "file://${tilesDir()}/{z}/{x}/{y}.png"

    fun save(bounds: MapBounds) {
        if (bounds.north <= bounds.south || bounds.east <= bounds.west) {
            report(saving = false, progress = 0f, detail = "The map is still loading. Try again in a moment.")
            return
        }
        val total = countTiles(bounds)
        if (total > TILE_LIMIT) {
            report(saving = false, progress = 0f, detail = "This area is too big to save. Zoom in and try again.")
            return
        }
        if (total == 0) {
            report(saving = false, progress = 0f, detail = "The map is still loading. Try again in a moment.")
            return
        }
        val ticket = ++generation
        val tiles = listTiles(bounds)
        report(saving = true, progress = 0f, detail = null)
        dispatch_async(ioQueue) {
            val missing = tiles.filter { tile -> !NSFileManager.defaultManager.fileExistsAtPath(tilePath(tile)) }
            dispatch_async(dispatch_get_main_queue()) {
                if (ticket != generation) return@dispatch_async
                if (missing.isEmpty()) {
                    markArea()
                    report(saving = false, progress = 1f, detail = "Area saved. It shows with no internet.")
                } else {
                    download(missing, already = tiles.size - missing.size, total = tiles.size, ticket = ticket)
                }
            }
        }
    }

    fun deleteAll() {
        generation += 1
        NSFileManager.defaultManager.removeItemAtPath(rootDir(), error = null)
        report(saving = false, progress = 0f, savedAreas = 0, detail = "Saved maps deleted.")
    }

    private fun download(missing: List<Tile>, already: Int, total: Int, ticket: Int) {
        val queue = ArrayDeque(missing)
        var inFlight = 0
        var done = already
        var failed = 0
        var bytes = 0L
        var marked = false

        fun finish() {
            if (ticket != generation) return
            val detail = when {
                done == 0 -> "Could not save this area. Check the internet connection."
                bytes >= 1024 * 1024 -> "Area saved (${bytes / (1024 * 1024)} MB). It shows with no internet."
                else -> "Area saved. It shows with no internet."
            }
            report(saving = false, progress = if (done == 0) 0f else 1f, detail = detail)
        }

        fun pump() {
            if (ticket != generation) return
            while (inFlight < 4 && queue.isNotEmpty()) {
                val tile = queue.removeFirst()
                inFlight += 1
                val request = NSMutableURLRequest(uRL = NSURL(string = tile.url))
                request.setTimeoutInterval(20.0)
                request.setValue(USER_AGENT, forHTTPHeaderField = "User-Agent")
                val task = session.dataTaskWithRequest(request) { data, response, error ->
                    val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    val body = data?.takeIf { error == null && code == 200 && it.length.toLong() > 0L }
                    val wrote = body != null && ticket == generation && writeTile(tile, body)
                    val size = if (wrote) body.length.toLong() else 0L
                    dispatch_async(dispatch_get_main_queue()) {
                        inFlight -= 1
                        if (ticket != generation) return@dispatch_async
                        if (wrote) {
                            done += 1
                            bytes += size
                            if (!marked) {
                                markArea()
                                marked = true
                            }
                        } else {
                            failed += 1
                        }
                        if (queue.isEmpty() && inFlight == 0) {
                            finish()
                        } else {
                            report(
                                saving = true,
                                progress = (done + failed).toFloat() / total,
                                detail = null,
                            )
                            pump()
                        }
                    }
                }
                task.resume()
            }
            if (queue.isEmpty() && inFlight == 0) finish()
        }
        pump()
    }

    private fun writeTile(tile: Tile, data: NSData): Boolean {
        if (generation != this.generation) return false
        val path = tilePath(tile)
        val folder = path.substringBeforeLast('/')
        NSFileManager.defaultManager.createDirectoryAtPath(
            folder,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return NSFileManager.defaultManager.createFileAtPath(path, contents = data, attributes = null)
    }

    private fun report(
        saving: Boolean,
        progress: Float,
        detail: String?,
        savedAreas: Int = savedCount(),
    ) {
        listener(OfflinePatch(saving = saving, progress = progress, savedAreas = savedAreas, detail = detail))
    }

    private fun markArea() {
        val folder = areasDir()
        NSFileManager.defaultManager.createDirectoryAtPath(
            folder,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val name = Random.nextLong().toString()
        NSFileManager.defaultManager.createFileAtPath("$folder/$name", contents = null, attributes = null)
    }

    private fun areaNames(): List<*> {
        return NSFileManager.defaultManager.contentsOfDirectoryAtPath(areasDir(), error = null) ?: emptyList<Any>()
    }

    private fun rootDir(): String = documentsDir() + "/airvox-offline-map"

    private fun tilesDir(): String = rootDir() + "/tiles"

    private fun areasDir(): String = rootDir() + "/areas"

    private fun tilePath(tile: Tile): String = "${tilesDir()}/${tile.zoom}/${tile.x}/${tile.y}.png"

    private fun documentsDir(): String {
        val urls = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        val url = urls.firstOrNull() as? NSURL
        return url?.path ?: NSHomeDirectory() + "/Documents"
    }

    private data class Tile(val zoom: Int, val x: Int, val y: Int) {
        val url: String
            get() {
                val host = HOSTS[(x + y) and 3]
                return "https://$host.basemaps.cartocdn.com/dark_all/$zoom/$x/$y.png"
            }
    }

    private fun listTiles(bounds: MapBounds): List<Tile> {
        val tiles = ArrayList<Tile>()
        for (zoom in MIN_ZOOM..MAX_ZOOM) {
            val count = 1 shl zoom
            val x0 = tileX(bounds.west, count)
            val x1 = tileX(bounds.east, count)
            val y0 = tileY(bounds.north, count)
            val y1 = tileY(bounds.south, count)
            for (x in x0..x1) {
                for (y in y0..y1) tiles += Tile(zoom, x, y)
            }
        }
        return tiles
    }

    private fun countTiles(bounds: MapBounds): Int {
        var total = 0
        for (zoom in MIN_ZOOM..MAX_ZOOM) {
            val count = 1 shl zoom
            val width = tileX(bounds.east, count) - tileX(bounds.west, count) + 1
            val height = tileY(bounds.south, count) - tileY(bounds.north, count) + 1
            if (width <= 0 || height <= 0) return TILE_LIMIT + 1
            total += width * height
            if (total > TILE_LIMIT) return total
        }
        return total
    }

    private fun tileX(longitude: Double, tiles: Int): Int {
        return floor((longitude + 180.0) / 360.0 * tiles).toInt().coerceIn(0, tiles - 1)
    }

    private fun tileY(latitude: Double, tiles: Int): Int {
        val radians = latitude.coerceIn(-85.0511, 85.0511) * PI / 180.0
        val y = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * tiles
        return floor(y).toInt().coerceIn(0, tiles - 1)
    }

    companion object {
        const val MIN_ZOOM = 8
        const val MAX_ZOOM = 14
        private const val TILE_LIMIT = 10_000
        private const val USER_AGENT = "Airvox/1.0 (offline map)"
        private val HOSTS = arrayOf("a", "b", "c", "d")
    }
}
