package com.sawt.ai.audio

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import java.io.File

data class BenchmarkMemorySnapshot(
    val availableSystemRamBytes: Long,
    val processPssKb: Int
)

data class BenchmarkMeasurement(
    val requestedSteps: Int,
    val effectiveSteps: Int,
    val elapsedMillis: Long,
    val memoryBefore: BenchmarkMemorySnapshot,
    val memoryAfter: BenchmarkMemorySnapshot,
    val outputBytes: Long
)

data class PerformanceBenchmarkReport(
    val coldFirstConversion: BenchmarkMeasurement,
    val warmConversion: BenchmarkMeasurement,
    val qualityComparisons: List<BenchmarkMeasurement>
)

/**
 * Runs real local synthesis on the current phone. It never uses network inference
 * and deletes all temporary benchmark WAV files without touching audio history.
 */
class PerformanceBenchmark(
    private val context: Context,
    private val engine: SupertonicTtsEngine
) {
    suspend fun run(
        onStage: (String) -> Unit = {}
    ): PerformanceBenchmarkReport {
        if (!engine.isModelInstalled()) {
            throw IllegalStateException("نزّل حزمة Supertonic الأساسية قبل اختبار الأداء.")
        }
        val sampleText = (
            "هذا اختبار أداء محرك ساوت للقراءة العربية الفصحى. " +
                "يقيس الاختبار وقت بدء محرك الصوت، وسرعة التحويل بعد تهيئته، " +
                "ويجرب إعدادات الجودة المختلفة مع مراقبة ذاكرة الهاتف. "
            ).repeat(2).take(420)

        onStage("تهيئة اختبار البدء الأول…")
        engine.releaseNativeModel()
        val cold = measure(sampleText, 6, onStage, "التحويل الأول")

        onStage("قياس التحويل بعد تهيئة المحرك…")
        val warm = measure(sampleText, 6, onStage, "التحويل المتكرر")

        onStage("مقارنة إعداد 4 خطوات…")
        val fast = measure(sampleText, 4, onStage, "سريع · 4 خطوات")

        onStage("مقارنة إعداد 8 خطوات…")
        val high = measure(sampleText, 8, onStage, "أعلى جودة · 8 خطوات")

        onStage("اكتمل اختبار الأداء.")
        return PerformanceBenchmarkReport(
            coldFirstConversion = cold,
            warmConversion = warm,
            qualityComparisons = listOf(fast, warm, high)
        )
    }

    private suspend fun measure(
        text: String,
        requestedSteps: Int,
        onStage: (String) -> Unit,
        label: String
    ): BenchmarkMeasurement {
        val before = memorySnapshot()
        val start = SystemClock.elapsedRealtime()
        var output: File? = null
        try {
            output = engine.synthesizeToFile(
                text = text,
                language = "ar",
                speakerId = 5,
                speed = 1.0f,
                requestedNumSteps = requestedSteps,
                onStage = { stage -> onStage("$label: $stage") }
            )
            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
            val size = output.length()
            val after = memorySnapshot()
            return BenchmarkMeasurement(
                requestedSteps = requestedSteps,
                effectiveSteps = engine.effectiveNumSteps(requestedSteps),
                elapsedMillis = elapsed,
                memoryBefore = before,
                memoryAfter = after,
                outputBytes = size
            )
        } finally {
            output?.delete()
        }
    }

    private fun memorySnapshot(): BenchmarkMemorySnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val processInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(processInfo)
        return BenchmarkMemorySnapshot(
            availableSystemRamBytes = memoryInfo.availMem.coerceAtLeast(0L),
            processPssKb = processInfo.totalPss.coerceAtLeast(0)
        )
    }
}
