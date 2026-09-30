package io.github.mangi.eta.agent.skill

import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.min

/**
 * 技能使用反馈统计——记录模型实际经 skills_read 读过的技能次数，
 * 在后续触发打分中给"确实被用过"的技能一个温和加成，形成自学习闭环。
 *
 * 持久化到 `<filesDir>/skill-usage.json`，纯本地统计，不改任何 SKILL.md；
 * 加成有上限（最高 2.0），避免自我强化跑偏。
 */
internal object SkillUsageStats {

    private const val FILE_NAME = "skill-usage.json"
    private const val MAX_ENTRIES = 400
    private val lock = Any()

    fun load(dir: File?): Map<String, Int> {
        val file = fileOf(dir) ?: return emptyMap()
        return synchronized(lock) {
            runCatching {
                if (!file.isFile) return emptyMap()
                val json = JSONObject(file.readText())
                val out = HashMap<String, Int>()
                json.keys().forEach { key -> out[key] = json.optInt(key, 0) }
                out
            }.getOrDefault(emptyMap())
        }
    }

    fun recordRead(dir: File?, id: String) {
        if (id.isBlank()) return
        val file = fileOf(dir) ?: return
        synchronized(lock) {
            runCatching {
                val current = if (file.isFile) JSONObject(file.readText()) else JSONObject()
                current.put(id, current.optInt(id, 0) + 1)
                val trimmed = if (current.length() > MAX_ENTRIES) {
                    val top = current.keys().asSequence()
                        .map { it to current.optInt(it, 0) }
                        .sortedByDescending { it.second }
                        .take(MAX_ENTRIES)
                    JSONObject().also { out -> top.forEach { (k, v) -> out.put(k, v) } }
                } else {
                    current
                }
                writeAtomically(file, trimmed)
            }
        }
    }

    /** 读取次数 → 触发权重乘数 [1.0, 2.0]，温和、有上限。 */
    fun boost(count: Int): Double =
        if (count <= 0) 1.0 else 1.0 + min(1.0, ln(1.0 + count) / 2.5)

    private fun fileOf(dir: File?): File? = dir?.let { File(it, FILE_NAME) }

    private fun writeAtomically(file: File, json: JSONObject) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString())
            tmp.delete()
        }
    }
}
