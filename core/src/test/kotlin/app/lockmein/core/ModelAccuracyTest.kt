package app.lockmein.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class ModelAccuracyTest {

    data class ClassMetrics(
        val label: String,
        val totalImages: Int,
        val detectedHits: Int,
        val predictions: IntArray, // counts for [drawings, hentai, neutral, porn, sexy]
    )

    companion object {
        val CLASSES = listOf("drawings", "hentai", "neutral", "porn", "sexy")
        private const val MODEL_URL = "https://raw.githubusercontent.com/Kazuhito00/nsfw_model_onnx_sample/main/model/nsfw_mobilenet2_224x224.onnx"
        private val URL_LISTS = mapOf(
            "drawings" to "https://raw.githubusercontent.com/alexkimxyz/nsfw_data_scraper/main/raw_data/drawings/urls_drawings.txt",
            "hentai" to "https://raw.githubusercontent.com/alexkimxyz/nsfw_data_scraper/main/raw_data/hentai/urls_hentai.txt",
            "neutral" to "https://raw.githubusercontent.com/alexkimxyz/nsfw_data_scraper/main/raw_data/neutral/urls_neutral.txt",
            "porn" to "https://raw.githubusercontent.com/alexkimxyz/nsfw_data_scraper/main/raw_data/porn/urls_porn.txt",
            "sexy" to "https://raw.githubusercontent.com/alexkimxyz/nsfw_data_scraper/main/raw_data/sexy/urls_sexy.txt",
        )
        private const val TARGET_PER_CLASS = 60
    }

    @Test
    fun testModelAccuracyOnLabelledDataset() {
        val runInCi = System.getenv("CI") != null
        val runExplicit = System.getProperty("test.modelAccuracy") == "true"
        assumeTrue(runInCi || runExplicit, "Skipping model accuracy test; run with -Dtest.modelAccuracy=true or in CI.")

        val cacheBase = File(System.getProperty("user.home"), ".cache/nsfw_model_eval")
        cacheBase.mkdirs()

        // 1. Download / Load ONNX model
        val modelFile = File(cacheBase, "nsfw_mobilenet2_224x224.onnx")
        if (!modelFile.exists() || modelFile.length() == 0L) {
            println("Downloading ONNX model from $MODEL_URL...")
            val stream = URI.create(MODEL_URL).toURL().openStream()
            Files.copy(stream, modelFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            println("ONNX model downloaded: ${modelFile.length()} bytes.")
        }

        // 2. Download / Fetch dataset
        println("Fetching labelled dataset ($TARGET_PER_CLASS images per class)...")
        val dataset = fetchDataset(cacheBase, TARGET_PER_CLASS)

        // 3. Initialize ONNX runtime session
        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setInterOpNumThreads(2)
            setIntraOpNumThreads(2)
        }
        val session = env.createSession(modelFile.absolutePath, sessionOptions)
        val inputName = session.inputNames.iterator().next()

        // 4. Run inference
        println("\nRunning inference over ${dataset.values.sumOf { it.size }} images...")
        val confusionMatrix = Array(5) { IntArray(5) }
        val classResults = mutableMapOf<String, ClassMetrics>()

        val thresholdPornHentai = 0.50f
        val thresholdSexy = 0.50f

        var truePositives = 0
        var actualPositives = 0
        var falsePositives = 0
        var actualNegatives = 0

        val startInfer = System.currentTimeMillis()

        for ((classIdx, label) in CLASSES.withIndex()) {
            val images = dataset[label] ?: emptyList()
            var hits = 0
            val predCounts = IntArray(5)

            for (img in images) {
                val scores = runModel(env, session, inputName, img)

                // Argmax
                var maxIdx = 0
                var maxScore = -1.0f
                for (i in scores.indices) {
                    if (scores[i] > maxScore) {
                        maxScore = scores[i]
                        maxIdx = i
                    }
                }
                confusionMatrix[classIdx][maxIdx]++
                predCounts[maxIdx]++

                // Decision rule: hit if porn + hentai >= threshold or sexy >= threshold
                val pornHentai = scores[1] + scores[3]
                val sexy = scores[4]
                val isHit = pornHentai >= thresholdPornHentai || sexy >= thresholdSexy

                if (label in listOf("porn", "hentai", "sexy")) {
                    actualPositives++
                    if (isHit) {
                        truePositives++
                        hits++
                    }
                } else if (label == "neutral") {
                    actualNegatives++
                    if (isHit) {
                        falsePositives++
                        hits++
                    }
                } else {
                    if (isHit) {
                        hits++
                    }
                }
            }

            classResults[label] = ClassMetrics(label, images.size, hits, predCounts)
        }

        val totalTime = System.currentTimeMillis() - startInfer
        val avgLatency = totalTime.toDouble() / dataset.values.sumOf { it.size }

        // 5. Print Confusion Table and Performance Metrics
        printConfusionTable(confusionMatrix, classResults, thresholdPornHentai, thresholdSexy, avgLatency)

        val recall = truePositives.toDouble() / actualPositives.coerceAtLeast(1)
        val fpr = falsePositives.toDouble() / actualNegatives.coerceAtLeast(1)

        println(
            String.format(
                "SUMMARY: Recall: %.2f%% (%d/%d), FPR: %.2f%% (%d/%d), Avg Latency: %.1f ms",
                recall * 100,
                truePositives,
                actualPositives,
                fpr * 100,
                falsePositives,
                actualNegatives,
                avgLatency,
            ),
        )

        session.close()
        sessionOptions.close()
        env.close()

        // 6. Assert Requirements: recall >= 90%, FPR < 10%
        assertTrue(
            recall >= 0.90,
            "Recall on sexual+revealing+hentai must be >= 90% (was ${String.format("%.2f%%", recall * 100)})",
        )
        assertTrue(
            fpr < 0.10,
            "False-positive rate on neutral must be < 10% (was ${String.format("%.2f%%", fpr * 100)})",
        )
    }

    private fun runModel(
        env: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        resized: BufferedImage,
    ): FloatArray {
        val floatBuffer = FloatBuffer.allocate(1 * 224 * 224 * 3)
        for (y in 0 until 224) {
            for (x in 0 until 224) {
                val rgb = resized.getRGB(x, y)
                val r = ((rgb shr 16) and 0xFF) / 255.0f
                val gVal = ((rgb shr 8) and 0xFF) / 255.0f
                val b = (rgb and 0xFF) / 255.0f
                floatBuffer.put(r)
                floatBuffer.put(gVal)
                floatBuffer.put(b)
            }
        }
        floatBuffer.flip()

        val tensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 224, 224, 3))
        val output = session.run(mapOf(inputName to tensor))
        @Suppress("UNCHECKED_CAST")
        val raw = output[0].value as Array<FloatArray>
        tensor.close()
        output.close()
        return raw[0]
    }

    private fun fetchDataset(cacheBase: File, targetPerClass: Int): Map<String, List<BufferedImage>> {
        val result = ConcurrentHashMap<String, List<BufferedImage>>()
        val executor = Executors.newFixedThreadPool(5)

        val futures = CLASSES.map { label ->
            executor.submit {
                try {
                    val classDir = File(cacheBase, label)
                    classDir.mkdirs()

                    val cachedImages = mutableListOf<BufferedImage>()
                    val existingFiles = classDir.listFiles { _, name -> name.endsWith(".jpg") } ?: emptyArray()
                    for (f in existingFiles.take(targetPerClass)) {
                        try {
                            val img = ImageIO.read(f)
                            if (img != null) {
                                val resized = if (img.width == 224 && img.height == 224) img else resizeTo224(img)
                                cachedImages.add(resized)
                            }
                        } catch (e: Exception) {
                            println("[$label] Error reading cached $f: $e")
                        }
                    }

                    if (cachedImages.size >= targetPerClass) {
                        println("  [$label] Loaded ${cachedImages.size} from cache.")
                        result[label] = cachedImages.take(targetPerClass)
                        return@submit
                    }

                    println("  [$label] Downloading from URL list...")
                    val urlListUri = URI.create(URL_LISTS[label]!!).toURL()
                    val allUrls = urlListUri.openStream().bufferedReader().readLines().filter { it.isNotBlank() }
                    val urls = allUrls.filter { "i.redd.it" in it }.ifEmpty { allUrls.filter { !it.contains("imgur") } }

                    for (url in urls) {
                        if (cachedImages.size >= targetPerClass) break
                        try {
                            val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                            if (conn.responseCode == 200) {
                                conn.inputStream.use { stream ->
                                    val rawImg = ImageIO.read(stream)
                                    if (rawImg != null) {
                                        val resized = resizeTo224(rawImg)
                                        rawImg.flush()
                                        val saveFile = File(classDir, "img_${cachedImages.size}.jpg")
                                        ImageIO.write(resized, "JPEG", saveFile)
                                        cachedImages.add(resized)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Skip broken/timeout link
                        }
                    }
                    println("  [$label] Ready with ${cachedImages.size} images.")
                    result[label] = cachedImages
                } catch (e: Throwable) {
                    println("[$label] FATAL in thread: $e")
                    e.printStackTrace()
                }
            }
        }

        executor.shutdown()
        for (f in futures) {
            try {
                f.get()
            } catch (e: Exception) {
                println("Future error: $e")
                e.printStackTrace()
            }
        }
        return result
    }

    private fun resizeTo224(src: BufferedImage): BufferedImage {
        val dest = BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB)
        val g = dest.createGraphics()
        g.drawImage(src, 0, 0, 224, 224, null)
        g.dispose()
        return dest
    }

    private infix fun String.notIn(other: String): Boolean = !other.contains(this)

    private fun printConfusionTable(
        matrix: Array<IntArray>,
        results: Map<String, ClassMetrics>,
        thresholdPornHentai: Float,
        thresholdSexy: Float,
        avgLatency: Double,
    ) {
        println("\n=========================================================================================")
        println("                           NSFW MOBILENET V2 CONFUSION TABLE                             ")
        println("=========================================================================================")
        println(String.format("%-12s | %-8s %-8s %-8s %-8s %-8s | %-8s | %-12s", "Actual \\ Pred", "Drawings", "Hentai", "Neutral", "Porn", "Sexy", "Total", "Filter Hit %"))
        println("-------------+-------------------------------------------------+----------+--------------")
        for (i in 0 until 5) {
            val label = CLASSES[i]
            val total = matrix[i].sum()
            val hits = results[label]?.detectedHits ?: 0
            val hitRate = if (total > 0) (hits * 100.0 / total) else 0.0
            println(
                String.format(
                    "%-12s | %-8d %-8d %-8d %-8d %-8d | %-8d | %6.1f%% (%d/%d)",
                    label,
                    matrix[i][0],
                    matrix[i][1],
                    matrix[i][2],
                    matrix[i][3],
                    matrix[i][4],
                    total,
                    hitRate,
                    hits,
                    total,
                ),
            )
        }
        println("=========================================================================================")
        println("Thresholds: (porn + hentai >= $thresholdPornHentai) OR (sexy >= $thresholdSexy)")
        println(String.format("Average inference latency per frame: %.2f ms", avgLatency))
        println("=========================================================================================\n")
    }
}
