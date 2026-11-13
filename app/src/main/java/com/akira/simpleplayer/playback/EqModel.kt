package com.akira.simpleplayer.playback

import com.akira.simpleplayer.core.Core
import com.akira.simpleplayer.ui.tr
import org.json.JSONArray
import org.json.JSONObject

/*
 * The equalizer as the settings screen sees it. Everything real (filters, presets, file
 * formats, saving) is in the Rust core, the same code as the desktop app; this file only
 * converts between Kotlin objects and the core's JSON.
 */

enum class EqType(private val zh: String, private val en: String) {
    LOW_SHELF("低頻擱架", "Low shelf"),
    PEAK("峰值", "Peak"),
    HIGH_SHELF("高頻擱架", "High shelf");

    val label get() = tr(zh, en)
}

data class EqBand(val type: EqType, val freq: Float, val gain: Float, val q: Float)

data class EqPreset(val name: String, val preamp: Float, val bands: List<EqBand>) {
    val isFlat get() = preamp == 0f && bands.all { it.gain == 0f }

    fun toJson(): String = JSONObject()
        .put("name", name)
        .put("preamp", preamp.toDouble())
        .put("bands", JSONArray().apply {
            bands.forEach {
                put(JSONObject().put("type", it.type.name).put("freq", it.freq.toDouble())
                    .put("gain", it.gain.toDouble()).put("q", it.q.toDouble()))
            }
        })
        .toString()

    companion object {
        fun fromJson(o: JSONObject): EqPreset {
            val arr = o.optJSONArray("bands") ?: JSONArray()
            val bands = (0 until arr.length()).mapNotNull { i ->
                val b = arr.optJSONObject(i) ?: return@mapNotNull null
                EqBand(
                    runCatching { EqType.valueOf(b.optString("type")) }.getOrDefault(EqType.PEAK),
                    b.optDouble("freq", 1000.0).toFloat(),
                    b.optDouble("gain", 0.0).toFloat(),
                    b.optDouble("q", 1.0).toFloat()
                )
            }
            return EqPreset(o.optString("name"), o.optDouble("preamp", 0.0).toFloat(), bands)
        }
    }
}

object EqLimits {
    const val MAX_BANDS = 10
    const val MIN_FREQ = 20f
    const val MAX_FREQ = 20_000f
    const val MIN_GAIN = -15f
    const val MAX_GAIN = 15f
    const val MIN_Q = 0.1f
    const val MAX_Q = 10f
    const val MIN_PREAMP = -20f
    const val MAX_PREAMP = 6f
}

object EqStore {
    private fun state() = runCatching { JSONObject(Core.eqJson()) }.getOrDefault(JSONObject())

    val enabled: Boolean get() = state().optBoolean("enabled", false)

    fun presets(): List<EqPreset> {
        val arr = state().optJSONArray("presets") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(EqPreset::fromJson) }
    }

    fun current(): EqPreset {
        val s = state()
        val name = s.optString("current")
        val list = presets()
        return list.firstOrNull { it.name == name } ?: list.firstOrNull() ?: EqFormat.defaultPreset()
    }

    fun setEnabled(on: Boolean) = Core.eqSetEnabled(on)
    fun select(name: String) = Core.eqSelect(name)
    fun preview(p: EqPreset) = Core.eqPreview(p.toJson())
    fun save(p: EqPreset, replacing: String? = null): String = Core.eqSave(p.toJson(), replacing ?: "")
    fun delete(name: String) = Core.eqDelete(name)
}

object EqFormat {
    /** Adds the presets of a file (Poweramp JSON or AutoEq text); returns how many. */
    fun importInto(text: String, fallbackName: String): Int {
        val n = Core.eqImport(text, fallbackName)
        if (n < 0) error(Core.eqError())
        return n
    }

    fun exportAll(): String = Core.eqExport()

    fun defaultPreset(name: String = tr("預設", "Default")) = EqPreset(
        name, 0f,
        listOf(
            EqBand(EqType.LOW_SHELF, 105f, 0f, 0.7f),
            EqBand(EqType.PEAK, 1000f, 0f, 1f),
            EqBand(EqType.HIGH_SHELF, 10_000f, 0f, 0.7f)
        )
    )
}

object EqMath {
    fun autoPreamp(p: EqPreset): Float = Core.eqAutoPreamp(p.toJson())

    /** Gain in dB (preamp included) at each frequency. */
    fun response(p: EqPreset, freqs: FloatArray): FloatArray = Core.eqResponse(p.toJson(), freqs)
}
