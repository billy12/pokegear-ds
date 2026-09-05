package com.enrpau.pokegeards

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.enrpau.pokegeards.data.RomManager
import com.enrpau.pokegeards.detection.ACTION_CATCH_TEXT
import com.enrpau.pokegeards.detection.ACTION_DEX_TEXT
import com.enrpau.pokegeards.detection.ACTION_LOCATION_TEXT
import com.enrpau.pokegeards.detection.ACTION_TITLE_COLOR
import com.enrpau.pokegeards.detection.ACTION_TITLE_TEXT
import com.enrpau.pokegeards.detection.DexScanState
import com.enrpau.pokegeards.detection.GameStateRepository
import com.enrpau.pokegeards.detection.TitleScanState
import com.enrpau.pokegeards.detection.TitleScreenColorClassifier
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class DualDexAccessibilityService : AccessibilityService() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var repository: PokemonRepository
    private var pokemonList: List<Pokemon> = emptyList()

    private var isScanning = false
    private var lastScanTime = 0L
    private val SCAN_COOLDOWN = 600L // ms

    private val loopHandler = Handler(Looper.getMainLooper())
    private val loopRunnable = object : Runnable {
        override fun run() {
            if (isScanning) {
            } else {
                triggerScreenScan()
            }
            loopHandler.postDelayed(this, 1500L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        RomManager.initialize(this)
        repository = PokemonRepository(this)

        executor.submit {
            repository.reloadDatabase()
            pokemonList = repository.getAllPokemon()
            android.util.Log.d("DualDex_Service", "Service loaded ${pokemonList.size} Pokemon")
        }

        loopHandler.post(loopRunnable)
        android.util.Log.d("DualDex_Service", "Polling Loop Started")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // whichever app just took the screen — the title window re-arms when
            // that app is Eden, since a game is probably about to boot
            GameStateRepository.onForegroundApp(event.packageName?.toString())
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            triggerScreenScan()
        }
    }

    private fun triggerScreenScan() {
        val now = System.currentTimeMillis()
        if (isScanning || (now - lastScanTime) < SCAN_COOLDOWN) return

        isScanning = true
        lastScanTime = now

        val prefs = getSharedPreferences("DualDexPrefs", MODE_PRIVATE)
        val scanSource = prefs.getString("SCAN_SOURCE", "top") ?: "top"
        val targetDisplayId = getTargetDisplayId(scanSource)

        takeScreenshot(targetDisplayId, executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = try {
                    val buffer = screenshot.hardwareBuffer
                    Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, true)
                        .also { buffer.close() }
                } catch (e: Exception) {
                    null
                }

                if (bitmap != null) {
                    processImage(bitmap)
                    processExtraCrops(bitmap)   // route banner + catch dialogue (fire-and-forget)
                } else {
                    isScanning = false
                }
            }

            override fun onFailure(errorCode: Int) {
                isScanning = false
                android.util.Log.e("DualDex_Service", "Screenshot failed on Display $targetDisplayId: $errorCode")
            }
        })
    }

    private fun getTargetDisplayId(scanSource: String): Int {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

        return if (scanSource == "bottom" && displays.size > 1) {
            displays[1].displayId
        } else {
            if (displays.isNotEmpty()) displays[0].displayId else Display.DEFAULT_DISPLAY
        }
    }

    private fun processImage(bitmap: Bitmap) {
        val prefs = getSharedPreferences("DualDexPrefs", MODE_PRIVATE)
        // This build targets Eden running BDSP/LP, whose battle nameplate sits
        // top-right — so "right" is the standard calibration here, not "left".
        val scanAlign = prefs.getString("SCAN_ALIGN", "right") ?: "right"

        val width = bitmap.width
        val height = bitmap.height

        val startY = 0
        val cropHeight = height / 2  // top half

        val startX = if (scanAlign == "right") width / 2 else 0
        val cropWidth = width / 2

        if (startY + cropHeight > height || startX + cropWidth > width) {
            isScanning = false
            return
        }

        try {
            val croppedBitmap = Bitmap.createBitmap(bitmap, startX, startY, cropWidth, cropHeight)
            val image = InputImage.fromBitmap(croppedBitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    processOcrResult(visionText.text)
                    isScanning = false
                }
                .addOnFailureListener {
                    isScanning = false
                }
        } catch (e: Exception) {
            isScanning = false
        }
    }

    /**
     * Extra OCR passes on the same screenshot, independent of the battle
     * scanner: the area-name banner (top-left) and the catch dialogue (bottom).
     * These only broadcast raw text; matching happens in the receivers, which
     * have the active data pack. Fire-and-forget — they don't gate the loop.
     */
    private fun processExtraCrops(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height

        // Pokédex rebuild scan: one wide full-height pass, nothing else, so the
        // OCR budget goes entirely to reading the in-game dex list.
        if (DexScanState.active) {
            crop(bitmap, 0, (h * 0.08f).toInt(), w, (h * 0.86f).toInt())?.let { region ->
                recognizer.process(InputImage.fromBitmap(region, 0))
                    .addOnSuccessListener { vt -> broadcastText(ACTION_DEX_TEXT, vt.text) }
            }
            return
        }

        // area-name banner: top-left ~half width, top ~18%
        crop(bitmap, 0, 0, (w * 0.55f).toInt(), (h * 0.20f).toInt())?.let { region ->
            recognizer.process(InputImage.fromBitmap(region, 0))
                .addOnSuccessListener { vt -> broadcastText(ACTION_LOCATION_TEXT, vt.text) }
        }
        // catch dialogue: bottom ~28%, full width
        crop(bitmap, 0, (h * 0.72f).toInt(), w, (h * 0.28f).toInt())?.let { region ->
            recognizer.process(InputImage.fromBitmap(region, 0))
                .addOnSuccessListener { vt -> broadcastText(ACTION_CATCH_TEXT, vt.text) }
        }
        // game title logo: centre ~60% wide, upper-middle band — only useful at
        // boot; TitleDetector ignores it after its window closes
        crop(bitmap, (w * 0.20f).toInt(), (h * 0.12f).toInt(), (w * 0.60f).toInt(), (h * 0.56f).toInt())?.let { region ->
            recognizer.process(InputImage.fromBitmap(region, 0))
                .addOnSuccessListener { vt -> broadcastText(ACTION_TITLE_TEXT, vt.text) }
        }
        // and the non-OCR half of the same job: the party icons Eden draws
        // bottom-right while it compiles shaders. Runs only while
        // TitleDetector's window is open, same as the pass above.
        classifyTitleColors(bitmap)
    }

    /**
     * Reads the bottom-right corner of the boot screen and broadcasts the pack
     * it looks like. One [Bitmap.getPixels] for the whole region, then pure
     * arithmetic — cheap enough to sit alongside the OCR passes, and gated so it
     * stops once the pack is settled.
     */
    private fun classifyTitleColors(bitmap: Bitmap) {
        if (!TitleScanState.colorScanActive) return
        val r = TitleScreenColorClassifier.region(bitmap.width, bitmap.height)
        if (r.width <= 0 || r.height <= 0) return
        val profile = try {
            val pixels = IntArray(r.width * r.height)
            bitmap.getPixels(pixels, 0, r.width, r.x, r.y, r.width, r.height)
            TitleScreenColorClassifier.profileRegion(pixels, r.width, r.height)
        } catch (e: Exception) {
            android.util.Log.w("DualDex_Service", "title colour pass failed", e)
            return
        }
        // logged every pass, not just on a verdict: the window is short and this
        // is the only way to see why a boot screen did or didn't get recognised
        android.util.Log.d("DualDex_Service", "title colour -> $profile")
        val packId = profile.result.packId ?: return
        sendBroadcast(Intent(ACTION_TITLE_COLOR).apply {
            setPackage(packageName)
            putExtra("PACK_ID", packId)
        })
    }

    private fun crop(src: Bitmap, x: Int, y: Int, cw: Int, ch: Int): Bitmap? = try {
        if (cw <= 0 || ch <= 0 || x + cw > src.width || y + ch > src.height) null
        else Bitmap.createBitmap(src, x, y, cw, ch)
    } catch (e: Exception) { null }

    private fun broadcastText(action: String, text: String) {
        if (text.isBlank()) return
        sendBroadcast(Intent(action).apply {
            setPackage(packageName)
            putExtra("TEXT", text)
        })
    }

    private fun processOcrResult(rawText: String) {
        val cleanText = rawText.replace("\n", " ").replace(Regex("[^A-Za-z -]"), "")
        val words = cleanText.split(" ").filter { it.length > 3 }

        val foundNames = ArrayList<String>()
        val foundIds = ArrayList<Int>()
        val foundT1s = ArrayList<String>()
        val foundT2s = ArrayList<String>()

        var matchCount = 0

        for (word in words) {
            if (matchCount >= 2) break
            val match = findBestMatch(word)
            if (match != null) {
                if (!foundNames.contains(match.name)) {
                    foundNames.add(match.name)
                    foundIds.add(match.id)
                    foundT1s.add(match.type1.name)
                    foundT2s.add(match.type2?.name ?: "UNKNOWN")
                    matchCount++
                }
            }
        }

        val intent = Intent("com.enrpau.pokegeards.POKEMON_DETECTED")
        intent.setPackage(packageName) // ensures only this app receives the broadcast

        if (foundNames.isNotEmpty()) {
            intent.putExtra("FOUND", true)
            intent.putStringArrayListExtra("NAMES", foundNames)
            intent.putIntegerArrayListExtra("IDS", foundIds)
            intent.putStringArrayListExtra("TYPE1S", foundT1s)
            intent.putStringArrayListExtra("TYPE2S", foundT2s)
        } else {
            intent.putExtra("FOUND", false)
        }

        sendBroadcast(intent)
    }

    private fun findBestMatch(input: String): Pokemon? {
        val exact = pokemonList.find { it.name.equals(input, true) }
        if (exact != null) return exact

        if (input.isEmpty()) return null
        val firstChar = input.first().lowercaseChar()
        val candidates = pokemonList.filter { it.name.isNotEmpty() && it.name.startsWith(firstChar, true) }

        var bestPokemon: Pokemon? = null
        var bestDist = Int.MAX_VALUE

        for (p in candidates) {
            val dist = levenshtein(input.lowercase(), p.name.lowercase())
            val threshold = if (p.name.length < 6) 1 else 2
            if (dist <= threshold && dist < bestDist) {
                bestDist = dist
                bestPokemon = p
            }
        }
        return bestPokemon
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLen = lhs.length
        val rhsLen = rhs.length
        var costs = IntArray(lhsLen + 1) { it }
        var newCosts = IntArray(lhsLen + 1)
        for (i in 1..rhsLen) {
            newCosts[0] = i
            for (j in 1..lhsLen) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = costs[j - 1] + match
                val costInsert = costs[j] + 1
                val costDelete = newCosts[j - 1] + 1
                newCosts[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = costs
            costs = newCosts
            newCosts = swap
        }
        return costs[lhsLen]
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        // 3. STOP THE LOOP (Crucial to prevent battery drain/crashes)
        loopHandler.removeCallbacks(loopRunnable)
    }
}