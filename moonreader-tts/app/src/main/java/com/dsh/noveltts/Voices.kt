package com.dsh.noveltts

import android.speech.tts.Voice
import java.util.Locale

/**
 * Every Chinese voice this engine can speak - one list, shared by the pickers,
 * the engine and (through the server) both Kokoro checkpoints.
 *
 * WHICH BACKEND SPEAKS FOLLOWS THE VOICE NAME, on the phone and on the server:
 *
 *   local Kokoro speakers   zm_yunxi, zf_xiaobei, zf_001, zm_010 ...  (default)
 *   Edge online voices      zh-CN-YunxiNeural ...                     (on purpose)
 *
 * The local speakers come from the TTS server's own models (Kokoro-82M and
 * Kokoro-82M-v1.1-zh, 108 Mandarin speakers in total) and need no internet, so
 * they are the default. An Edge voice is used only when one is picked here by
 * name, and engine= on the server can force either one per request.
 */
object Voices {

    const val GROUP_KOKORO = "本地 Kokoro v1.0"
    const val GROUP_KOKORO_V11 = "本地 Kokoro v1.1-zh"
    const val GROUP_EDGE = "Edge 在线"

    /** Default voice: a local speaker, never Edge. */
    const val DEFAULT = "zm_yunxi"

    /**
     * @param name  what the engine (and the server) is asked for
     * @param label what the user sees
     * @param group which section of the picker it belongs to
     */
    data class Entry(val name: String, val label: String, val group: String) {
        val local: Boolean get() = group != GROUP_EDGE
    }

    /** The eight well-trained Mandarin speakers of Kokoro-82M. */
    private val V1: List<Pair<String, String>> = listOf(
            "zm_yunxi" to "Yunxi 男声·年轻",
            "zm_yunjian" to "Yunjian 男声·沉稳",
            "zm_yunxia" to "Yunxia 男声·少年",
            "zm_yunyang" to "Yunyang 男声·播音",
            "zf_xiaobei" to "Xiaobei 女声·成熟",
            "zf_xiaoxiao" to "Xiaoxiao 女声·温暖",
            "zf_xiaoyi" to "Xiaoyi 女声·活泼",
            "zf_xiaoni" to "Xiaoni 女声·少女",
    )

    /**
     * The 100 Mandarin speakers added by Kokoro-82M-v1.1-zh. They are numbered
     * (zf_ = female, zm_ = male) and each voice pack is ~500 KB, so they live in
     * their own collapsible section of the picker.
     */
    private val V11: List<Pair<String, String>> = listOf(
            "zf_001" to "女声 001",
            "zf_002" to "女声 002",
            "zf_003" to "女声 003",
            "zf_004" to "女声 004",
            "zf_005" to "女声 005",
            "zf_006" to "女声 006",
            "zf_007" to "女声 007",
            "zf_008" to "女声 008",
            "zf_017" to "女声 017",
            "zf_018" to "女声 018",
            "zf_019" to "女声 019",
            "zf_021" to "女声 021",
            "zf_022" to "女声 022",
            "zf_023" to "女声 023",
            "zf_024" to "女声 024",
            "zf_026" to "女声 026",
            "zf_027" to "女声 027",
            "zf_028" to "女声 028",
            "zf_032" to "女声 032",
            "zf_036" to "女声 036",
            "zf_038" to "女声 038",
            "zf_039" to "女声 039",
            "zf_040" to "女声 040",
            "zf_042" to "女声 042",
            "zf_043" to "女声 043",
            "zf_044" to "女声 044",
            "zf_046" to "女声 046",
            "zf_047" to "女声 047",
            "zf_048" to "女声 048",
            "zf_049" to "女声 049",
            "zf_051" to "女声 051",
            "zf_059" to "女声 059",
            "zf_060" to "女声 060",
            "zf_067" to "女声 067",
            "zf_070" to "女声 070",
            "zf_071" to "女声 071",
            "zf_072" to "女声 072",
            "zf_073" to "女声 073",
            "zf_074" to "女声 074",
            "zf_075" to "女声 075",
            "zf_076" to "女声 076",
            "zf_077" to "女声 077",
            "zf_078" to "女声 078",
            "zf_079" to "女声 079",
            "zf_083" to "女声 083",
            "zf_084" to "女声 084",
            "zf_085" to "女声 085",
            "zf_086" to "女声 086",
            "zf_087" to "女声 087",
            "zf_088" to "女声 088",
            "zf_090" to "女声 090",
            "zf_092" to "女声 092",
            "zf_093" to "女声 093",
            "zf_094" to "女声 094",
            "zf_099" to "女声 099",
            "zm_009" to "男声 009",
            "zm_010" to "男声 010",
            "zm_011" to "男声 011",
            "zm_012" to "男声 012",
            "zm_013" to "男声 013",
            "zm_014" to "男声 014",
            "zm_015" to "男声 015",
            "zm_016" to "男声 016",
            "zm_020" to "男声 020",
            "zm_025" to "男声 025",
            "zm_029" to "男声 029",
            "zm_030" to "男声 030",
            "zm_031" to "男声 031",
            "zm_033" to "男声 033",
            "zm_034" to "男声 034",
            "zm_035" to "男声 035",
            "zm_037" to "男声 037",
            "zm_041" to "男声 041",
            "zm_045" to "男声 045",
            "zm_050" to "男声 050",
            "zm_052" to "男声 052",
            "zm_053" to "男声 053",
            "zm_054" to "男声 054",
            "zm_055" to "男声 055",
            "zm_056" to "男声 056",
            "zm_057" to "男声 057",
            "zm_058" to "男声 058",
            "zm_061" to "男声 061",
            "zm_062" to "男声 062",
            "zm_063" to "男声 063",
            "zm_064" to "男声 064",
            "zm_065" to "男声 065",
            "zm_066" to "男声 066",
            "zm_068" to "男声 068",
            "zm_069" to "男声 069",
            "zm_080" to "男声 080",
            "zm_081" to "男声 081",
            "zm_082" to "男声 082",
            "zm_089" to "男声 089",
            "zm_091" to "男声 091",
            "zm_095" to "男声 095",
            "zm_096" to "男声 096",
            "zm_097" to "男声 097",
            "zm_098" to "男声 098",
            "zm_100" to "男声 100",
    )

    /** Microsoft Edge's online voices: the same eight personas, online. */
    private val EDGE: List<Pair<String, String>> = listOf(
            "zh-CN-YunxiNeural" to "Yunxi 男声·年轻",
            "zh-CN-YunjianNeural" to "Yunjian 男声·沉稳",
            "zh-CN-YunxiaNeural" to "Yunxia 男声·少年",
            "zh-CN-YunyangNeural" to "Yunyang 男声·播音",
            "zh-CN-XiaobeiNeural" to "Xiaobei 女声·成熟",
            "zh-CN-XiaoxiaoNeural" to "Xiaoxiao 女声·温暖",
            "zh-CN-XiaoyiNeural" to "Xiaoyi 女声·活泼",
            "zh-CN-XiaoniNeural" to "Xiaoni 女声·少女",
    )

    val all: List<Entry> =
        V1.map { Entry(it.first, "${it.second} · 本地", GROUP_KOKORO) } +
        V11.map { Entry(it.first, "${it.second} · 本地 v1.1", GROUP_KOKORO_V11) } +
        EDGE.map { Entry(it.first, "${it.second} · Edge", GROUP_EDGE) }

    val names: List<String> = all.map { it.name }

    val localNames: List<String> = all.filter { it.local }.map { it.name }

    private val byName: Map<String, Entry> = all.associateBy { it.name }

    fun entry(name: String): Entry? = byName[name]

    fun label(name: String): String = byName[name]?.label ?: name

    fun isLocal(name: String): Boolean = byName[name]?.local ?: false

    /** Edge name -> the local speaker with the same persona, and back. */
    val EDGE_TO_LOCAL: Map<String, String> = listOf(
        "zh-CN-YunxiNeural" to "zm_yunxi",
        "zh-CN-YunjianNeural" to "zm_yunjian",
        "zh-CN-YunxiaNeural" to "zm_yunxia",
        "zh-CN-YunyangNeural" to "zm_yunyang",
        "zh-CN-XiaobeiNeural" to "zf_xiaobei",
        "zh-CN-XiaoxiaoNeural" to "zf_xiaoxiao",
        "zh-CN-XiaoyiNeural" to "zf_xiaoyi",
        "zh-CN-XiaoniNeural" to "zf_xiaoni",
    ).toMap()
    val LOCAL_TO_EDGE: Map<String, String> =
        EDGE_TO_LOCAL.entries.associate { (k, v) -> v to k }

    /** The framework's view of the same list. */
    fun frameworkVoices(): List<Voice> = all.map { e ->
        Voice(
            e.name, Locale.SIMPLIFIED_CHINESE,
            Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL,
            // An Edge voice needs the network by definition; a local speaker is
            // served by the server's own model (with the phone's tiers behind it).
            !e.local, emptySet()
        )
    }
}
