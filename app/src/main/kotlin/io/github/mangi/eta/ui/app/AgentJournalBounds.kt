package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient

/**
 * journal 是会话的"完整记录"，只在末尾追加、从不上界，长会话可以涨到几十 MB。
 *
 * 它每一轮都要整段编码进 `context_checkpoint.journal_json`，
 * 而编码会物化出一个等长的 JSON 字符串——日志里直接看到的就是
 * `Agent conversation persistence failed: phase=encode_journal type=OutOfMemoryError`。
 * 落盘一失败，write-ahead 就把本轮判成"没提交"，失败分支随后在主线程重建会话状态，
 * 用户看到的就是"发消息卡死"。
 *
 * 这里按体积保留尾部：早期记录让位给"能继续对话"。至少保留 [MIN_ENTRIES] 条，
 * 避免单条超长消息把 journal 清空。
 */
internal fun boundedJournal(
    journal: List<AgentModelClient.ConversationMessage>,
    maxChars: Int = JOURNAL_MAX_CHARS,
): List<AgentModelClient.ConversationMessage> {
    if (journal.isEmpty()) return journal
    var total = 0
    var start = journal.size
    while (start > 0) {
        val entry = journal[start - 1]
        val cost = entry.content.length +
            entry.contentJson.length +
            entry.reasoningContent.length +
            entry.toolCallsJson.length +
            FIELD_OVERHEAD_CHARS
        if (total + cost > maxChars && journal.size - start >= MIN_ENTRIES) break
        total += cost
        start--
    }
    if (start == 0) return journal
    return journal.subList(start, journal.size).toList()
}

/** 约 4 MB：编码成 JSON 后峰值在 8~12 MB，手机堆能稳定吃下。 */
internal const val JOURNAL_MAX_CHARS = 4 * 1024 * 1024

/** OOM 兜底时退到的体积，只求这次能存下去。 */
internal const val JOURNAL_EMERGENCY_CHARS = 512 * 1024

private const val MIN_ENTRIES = 40
private const val FIELD_OVERHEAD_CHARS = 32
