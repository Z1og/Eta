package io.github.mangi.eta.ui.app

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMetadata
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray

internal object AgentConversationStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class Snapshot(
        val selectedConversationId: String?,
        val conversationsById: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val updatedAt: Map<String, Long>,
        /**
         * 加载后列表下标与库中 sort_index 不一致的会话。流式占位等未入库消息会在排序中留下空洞，
         * 重启后列表被压紧；增量保存按下标截断后缀，这些会话必须先整段重写一次。
         */
        val unalignedConversationIds: Set<String> = emptySet(),
    )

    private val saveMutex = Mutex()

    fun load(context: Context): Snapshot =
        runBlocking(Dispatchers.IO) {
            loadSnapshot(context.applicationContext)
        }

    suspend fun save(
        context: Context,
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
    ) = AgentConversationPersistence().save(
        context, Snapshot(selectedConversationId, conversationsById, titles, updatedAt),
    )

    internal suspend fun saveChanges(
        context: Context,
        selectedConversationId: String?,
        current: Map<String, AgentConversationPersistence.Content>,
        previous: Map<String, AgentConversationPersistence.Content>,
    ) {
        val appContext = context.applicationContext
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                val startedAt = SystemClock.elapsedRealtime()
                var phase = "read_metadata"
                try {
                    val database = EtaDatabase.get(appContext)
                    val dao = database.conversationDao()
                    val stored = dao.conversationMetadataRows().associateBy { it.id }
                    val staleIds = stored.keys - current.keys

                    // 重活全部放在事务外：历史/消息的 JSON 编码可能到 MB 级，
                    // 放进事务会把 Room 写锁一直占着，runtime 侧的 checkpoint 落盘
                    // （runBlocking 写库）会被它卡住——表现就是"继续对话卡死"。
                    val plans = current.map { (id, state) ->
                        val old = previous[id].takeIf { id in stored }
                        phase = "encode_metadata"
                        val metadata = if (old == null || !state.sameMetadata(old)) {
                            ConversationEntity(
                                id = id, title = state.title,
                                thinkingEnabled = state.reasoningEffort.enablesReasoning,
                                reasoningEffort = state.reasoningEffort.wireValue,
                                appliedRuntimeRunIdsJson = json.encodeToString(state.appliedRuntimeRunIds),
                                roleplayJson = state.roleplay?.let { json.encodeToString(it) }.orEmpty(),
                                revisionsJson = if (state.roleplay == null) "" else json.encodeToString(state.roleplayMessages),
                                createdAt = stored[id]?.createdAt ?: state.updatedAt.takeIf { it != 0L } ?: System.currentTimeMillis(),
                                updatedAt = state.updatedAt.takeIf { it != 0L } ?: System.currentTimeMillis(),
                                modelId = state.modelId,
                            )
                        } else null
                        val historyChanged = old == null || state.history != old.history
                        val journalChanged = old == null || state.journal != old.journal
                        var history: String? = null
                        var journal: String? = null
                        if (historyChanged || journalChanged) {
                            val missing = dao.contextCheckpointRow(id) == null
                            phase = "encode_history"
                            if (missing || historyChanged) {
                                history = AgentConversationCodec.encodeConversationCheckpoint(state.history)
                            }
                            phase = "encode_journal"
                            if (missing || journalChanged) {
                                journal = encodeJournal(state.journal)
                            }
                        }
                        val oldMessages = old?.messages.orEmpty()
                        var prefix = 0
                        while (prefix < minOf(oldMessages.size, state.messages.size)) {
                            val before = oldMessages[prefix]
                            val after = state.messages[prefix]
                            if (before != after && before.toEntityOrNull(id, prefix) != after.toEntityOrNull(id, prefix)) break
                            prefix++
                        }
                        val rewriteMessages = old == null || prefix != oldMessages.size || prefix != state.messages.size
                        phase = "encode_message"
                        val messageRows = if (rewriteMessages) {
                            (prefix until state.messages.size).mapNotNull { index ->
                                state.messages[index].toEntityOrNull(id, index)
                            }
                        } else {
                            emptyList()
                        }
                        PersistPlan(
                            id = id,
                            metadata = metadata,
                            history = history,
                            journal = journal,
                            deleteMessagesFrom = if (rewriteMessages) prefix else null,
                            messageRows = messageRows,
                        )
                    }

                    phase = "write"
                    val encodeMs = SystemClock.elapsedRealtime() - startedAt
                    if (encodeMs >= SLOW_PERSIST_MS) {
                        AndroidAgentLogger.warn(
                            "conversation persist slow: encode=${encodeMs}ms conversations=${current.size}",
                        )
                    }
                    database.withTransaction {
                        staleIds.forEach { dao.deleteConversation(it) }
                        plans.forEach { plan ->
                            plan.metadata?.let { dao.insertConversations(listOf(it)) }
                            if (plan.history != null || plan.journal != null) {
                                phase = "write_checkpoint"
                                dao.updateContextCheckpoint(plan.id, plan.history, plan.journal)
                            }
                            plan.deleteMessagesFrom?.let { from ->
                                phase = "delete_message_suffix"
                                dao.deleteMessagesFrom(plan.id, from)
                                if (plan.messageRows.isNotEmpty()) {
                                    phase = "write_message"
                                    dao.insertMessages(plan.messageRows)
                                }
                            }
                        }
                        phase = "write_selection"
                        val selected = selectedConversationId?.takeIf { it in current }
                            ?: current.maxByOrNull { it.value.updatedAt }?.key
                        if (selected == null) dao.deleteState()
                        else dao.insertState(ConversationStateEntity(selectedConversationId = selected))
                    }
                    val totalMs = SystemClock.elapsedRealtime() - startedAt
                    if (totalMs >= SLOW_PERSIST_MS) {
                        AndroidAgentLogger.warn(
                            "conversation persist slow: total=${totalMs}ms (encode=${encodeMs}ms) conversations=${current.size}",
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: phase=$phase type=${failure.safeLogType()}\n${failure.safeStackTrace()}",
                    )
                    throw failure
                }
            }
        }
    }

    /**
     * journal 编码撞 OOM 时退化到"只存尾部"，而不是让整条落盘失败。
     *
     * 失败会让 write-ahead 判定本轮未提交，失败分支会回到主线程重建会话状态——
     * 在长会话上那就是用户看到的"发消息卡死"。宁可这次少存早期记录。
     */
    private fun encodeJournal(journal: List<AgentModelClient.ConversationMessage>): String {
        encodeJournalOrNull(journal)?.let { return it }
        val bounded = boundedJournal(journal, maxChars = JOURNAL_MAX_CHARS)
        encodeJournalOrNull(bounded)?.let {
            AndroidAgentLogger.warn(
                "journal encode out of memory, degrade to ${bounded.size}/${journal.size} entries",
            )
            return it
        }
        val tail = boundedJournal(journal, maxChars = JOURNAL_EMERGENCY_CHARS)
        AndroidAgentLogger.warn(
            "journal encode out of memory again, emergency tail ${tail.size}/${journal.size} entries",
        )
        return AgentConversationCodec.encodeTranscriptForStorage(tail)
    }

    private fun encodeJournalOrNull(
        journal: List<AgentModelClient.ConversationMessage>,
    ): String? = try {
        AgentConversationCodec.encodeTranscriptForStorage(journal)
    } catch (_: OutOfMemoryError) {
        null
    }

    private data class PersistPlan(
        val id: String,
        val metadata: ConversationEntity?,
        val history: String?,
        val journal: String?,
        val deleteMessagesFrom: Int?,
        val messageRows: List<ConversationMessageEntity>,
    )

    private suspend fun loadSnapshot(context: Context): Snapshot {
        val startedAt = SystemClock.elapsedRealtime()
        val snapshot = loadSnapshotInternal(context)
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed >= SLOW_PERSIST_MS) {
            AndroidAgentLogger.warn(
                "conversation load slow: ${elapsed}ms conversations=${snapshot.conversationsById.size}",
            )
        }
        return snapshot
    }

    private suspend fun loadSnapshotInternal(context: Context): Snapshot {
        val dao = EtaDatabase.get(context).conversationDao()
        val conversations = dao.conversations()
        if (conversations.isEmpty()) {
            return Snapshot(
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
            )
        }

        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()
        val unaligned = mutableSetOf<String>()

        conversations.forEach { conversation ->
            val messages = buildList {
                var offset = 0
                while (true) {
                    val page = dao.messagesPage(conversation.id, MESSAGE_LOAD_PAGE_SIZE, offset)
                    addAll(page)
                    if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                    offset += page.size
                }
            }
            val restored = messages.mapNotNull { it.toMessageOrNull() }
            if (restored.size != messages.size || messages.withIndex().any { (index, row) -> row.sortIndex != index }) {
                unaligned += conversation.id
            }
            val checkpoint = dao.contextCheckpoint(conversation.id)
            val decodedHistory = AgentConversationCodec.decodeTranscript(checkpoint?.historyJson)
            if (decodedHistory.isEmpty() && restored.isNotEmpty()) {
                // 上下文检查点缺失/为空时会退化成"全量消息当历史"，压缩成果就此丢失，
                // 下一轮直接爆上下文。这里留证据，便于判断是没写入还是被覆盖。
                AndroidAgentLogger.warn(
                    "context checkpoint empty; history falls back to all messages " +
                        "(conv=${conversation.id}, messages=${restored.size})",
                )
            }
            states[conversation.id] = AgentChatHomeUiState(
                roleplay = conversation.roleplayJson.takeIf(String::isNotBlank)?.let { json.decodeFromString<RoleplayBinding>(it) },
                roleplayMessages = conversation.revisionsJson.takeIf(String::isNotBlank)?.let {
                    json.decodeFromString<RoleplayMessageState>(it)
                } ?: RoleplayMessageState(),
                // 历史库里可能有修复前写下的超大 journal（数十 MB）。载入时就按上限截尾，
                // 否则它会一直占着堆，下一次整段编码（journal_json）必然 OOM。
                journal = boundedJournal(
                    AgentConversationCodec.decodeTranscript(checkpoint?.journalJson),
                ),
                messages = restored,
                history = decodedHistory.ifEmpty {
                    messages.toLegacyHistory()
                },
                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = conversation.reasoningEffortValue.enablesReasoning,
                reasoningEffort = conversation.reasoningEffortValue,
                modelId = conversation.modelId,
            ).let(RoleplayConversationReducer::decorate)
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty()
            updatedAt[conversation.id] = conversation.updatedAt
        }

        val selected = dao.state()?.selectedConversationId
            ?.takeIf { it in states }
            ?: states.keys.first()

        return Snapshot(
            selectedConversationId = selected,
            conversationsById = states,
            titles = titles,
            updatedAt = updatedAt,
            unalignedConversationIds = unaligned,
        )
    }

    private val ConversationMetadata.reasoningEffortValue: ReasoningEffort
        get() = ReasoningEffort.fromWireValue(reasoningEffort) ?: ReasoningEffort.DEFAULT

    private fun AgentChatMessageUi.toEntityOrNull(
        conversationId: String,
        sortIndex: Int,
    ): ConversationMessageEntity? =
        when (this) {
            is UserMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_USER,
                content = content,
                imagesJson = images.toJsonArrayString(),
                isEdited = isEdited,
            )

            is AgentMessageUi -> {
                if (content.isBlank() && isStreaming) {
                    null
                } else {
                    ConversationMessageEntity(
                        id = id,
                        conversationId = conversationId,
                        sortIndex = sortIndex,
                        type = TYPE_ASSISTANT,
                        content = content,
                        renderMarkdown = renderMarkdown,
                        contextTokens = usage?.contextTokens,
                        inputTokens = usage?.inputTokens,
                        outputTokens = usage?.outputTokens,
                        reasoningTokens = usage?.reasoningTokens,
                        cachedTokens = usage?.cachedTokens,
                    )
                }
            }

            is SystemNoticeMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_SYSTEM_NOTICE,
                content = code.wireValue,
                resultSummary = detail,
                contextTokens = contextTokens,
                renderMarkdown = false,
            )

            is ThinkingMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_THINKING,
                content = content,
                elapsedSeconds = elapsedSeconds,
            )

            is ToolActivityMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL,
                content = command.orEmpty(),
                toolName = toolName,
                toolStatus = status.name,
                argumentsSummary = argumentsSummary,
                resultSummary = resultSummary,
                imageCount = imageCount,
            )

            is ToolSummaryMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL_SUMMARY,
                content = "",
                toolsJson = tools.toJsonArrayString(),
            )

            else -> null
        }

    private fun ConversationMessageEntity.toMessageOrNull(): AgentChatMessageUi? =
        when (type) {
            TYPE_USER -> UserMessageUi(
                id = id,
                content = content,
                images = imagesJson.toStringList(),
                isEdited = isEdited,
            )

            TYPE_ASSISTANT -> AgentMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                renderMarkdown = renderMarkdown ?: true,
                usage = TokenUsageUi(
                    contextTokens = contextTokens,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                ).takeUnless { it.isEmpty },
            )

            TYPE_SYSTEM_NOTICE -> SystemNoticeCode.fromWireValue(content)?.let { code ->
                SystemNoticeMessageUi(
                    id = id,
                    code = code,
                    detail = resultSummary,
                    contextTokens = contextTokens,
                )
            }

            TYPE_THINKING -> ThinkingMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds,
                collapsed = true,
            )

            TYPE_TOOL -> ToolActivityMessageUi(
                id = id,
                toolName = toolName.orEmpty(),
                status = toolStatus.orEmpty().toToolStatus(),
                argumentsSummary = argumentsSummary.orEmpty(),
                command = content.takeIf(String::isNotBlank),
                resultSummary = resultSummary,
                imageCount = imageCount,
            )

            TYPE_TOOL_SUMMARY -> ToolSummaryMessageUi(
                id = id,
                tools = toolsJson.toStringList(),
            )

            else -> null
        }

    private fun String.toToolStatus(): ToolActivityStatusUi =
        runCatching { ToolActivityStatusUi.valueOf(this) }.getOrNull()
            ?.let { status ->
                if (status == ToolActivityStatusUi.Running) ToolActivityStatusUi.Unknown else status
            }
            ?: ToolActivityStatusUi.Unknown

    private fun List<String>.toJsonArrayString(): String =
        JSONArray().also { array ->
            forEach { array.put(it) }
        }.toString()

    private fun String.toStringList(): List<String> =
        runCatching {
            val array = JSONArray(this)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun List<ConversationMessageEntity>.toLegacyHistory(): List<AgentModelClient.ConversationMessage> =
        mapNotNull { message ->
            when (message.type) {
                TYPE_USER -> AgentModelClient.ConversationMessage(
                    role = "user",
                    content = message.content,
                )
                TYPE_ASSISTANT -> message.content
                    .takeIf { it.isNotBlank() }
                    ?.let { content ->
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = content,
                        )
                    }
                else -> null
            }
        }

    private const val SLOW_PERSIST_MS = 500L
    private const val TYPE_USER = "user"
    private const val TYPE_ASSISTANT = "assistant"
    private const val TYPE_SYSTEM_NOTICE = "system_notice"
    private const val TYPE_THINKING = "thinking"
    private const val TYPE_TOOL = "tool"
    private const val TYPE_TOOL_SUMMARY = "tool_summary"
    private const val MESSAGE_LOAD_PAGE_SIZE = 128
    private const val LEGACY_UNNAMED_TITLE = "新对话"
}
