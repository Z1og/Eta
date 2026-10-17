package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.data.repository.AgentTaskItem
import io.github.mangi.eta.data.repository.AgentTaskPlan
import io.github.mangi.eta.data.repository.AgentTaskPlanStore
import io.github.mangi.eta.data.repository.AgentTaskStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * 子任务清单工具。
 *
 * 存在的理由只有一个：目标续跑会跨十几轮、中途还会压缩上下文，模型靠"记着"必然漏做或重做。
 * 清单是外部进度，压缩不掉，也让"是否完成"从模型的自我感觉变成可校验的状态。
 */
internal class AgentTaskTool(
    private val store: AgentTaskPlanStore,
    private val conversationId: String,
) {
    fun schema(): JSONObject = AgentToolSchema.function(
        name = TASK_WRITE,
        description = "维护当前目标的子任务清单。开始推进目标前，先把目标拆成可独立验证的子任务写进来；" +
            "每完成一条就把它改成 done 再继续下一条。清单会落盘并在每轮注入，跨轮次和上下文压缩都不会丢。" +
            "action=replace 用传入的 tasks 整体替换清单（省略 tasks 等于清空）；action=read 只读当前清单。" +
            "id 保持稳定，重复使用同一个 id 表示仍是同一条任务。全部子任务为 done 才算目标达成，" +
            "清空清单不代表完成。遇到需要用户授权或外部条件阻塞时标为 blocked 并写 note 说明原因。",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "action",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("replace").put("read")),
                    )
                    .put(
                        "tasks",
                        JSONObject()
                            .put("type", "array")
                            .put("maxItems", AgentTaskPlan.MAX_TASKS)
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("id", JSONObject().put("type", "string").put("maxLength", 64))
                                            .put(
                                                "title",
                                                JSONObject().put("type", "string")
                                                    .put("maxLength", AgentTaskPlan.MAX_TITLE_CHARS),
                                            )
                                            .put(
                                                "status",
                                                JSONObject()
                                                    .put("type", "string")
                                                    .put(
                                                        "enum",
                                                        JSONArray()
                                                            .put(AgentTaskStatus.Pending.wireValue)
                                                            .put(AgentTaskStatus.InProgress.wireValue)
                                                            .put(AgentTaskStatus.Done.wireValue)
                                                            .put(AgentTaskStatus.Blocked.wireValue),
                                                    ),
                                            )
                                            .put(
                                                "note",
                                                JSONObject().put("type", "string")
                                                    .put("maxLength", AgentTaskPlan.MAX_NOTE_CHARS),
                                            ),
                                    )
                                    .put("required", JSONArray().put("id").put("title").put("status")),
                            ),
                    ),
            )
            .put("required", JSONArray().put("action")),
    )

    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return failure("TASK_INVALID_ARGUMENTS", "参数不是合法 JSON")
        return when (args.optString("action").ifBlank { "replace" }) {
            "read" -> AgentModelClient.ToolResult(resultJson(store.load(conversationId)))
            "replace" -> replace(args)
            else -> failure("TASK_INVALID_ACTION", "action 只能是 replace 或 read")
        }
    }

    private fun replace(args: JSONObject): AgentModelClient.ToolResult {
        val array = args.optJSONArray("tasks") ?: JSONArray()
        if (array.length() > AgentTaskPlan.MAX_TASKS) {
            return failure("TASK_TOO_MANY", "子任务最多 ${AgentTaskPlan.MAX_TASKS} 条，请先合并同类项")
        }
        val tasks = mutableListOf<AgentTaskItem>()
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: return failure("TASK_INVALID_ITEM", "第 ${index + 1} 条子任务不是对象")
            val id = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (id.isEmpty() || title.isEmpty()) {
                return failure("TASK_INVALID_ITEM", "第 ${index + 1} 条子任务缺少 id 或 title")
            }
            if (!seen.add(id)) {
                return failure("TASK_DUPLICATE_ID", "子任务 id 重复：$id")
            }
            tasks += AgentTaskItem(
                id = id,
                title = title,
                status = AgentTaskStatus.fromWire(item.optString("status"))
                    ?: return failure("TASK_INVALID_STATUS", "第 ${index + 1} 条状态不合法"),
                note = item.optString("note").trim(),
            )
        }
        val saved = runCatching { store.save(conversationId, AgentTaskPlan(tasks)) }
            .getOrElse { throwable ->
                return failure("TASK_WRITE_FAILED", throwable.message ?: "任务清单保存失败")
            }
        return AgentModelClient.ToolResult(resultJson(saved))
    }

    private fun resultJson(plan: AgentTaskPlan): String = JSONObject()
        .put("ok", true)
        .put("total", plan.total)
        .put("done", plan.doneCount)
        .put("open", plan.openCount)
        .put("blocked", plan.blockedCount)
        .put("all_done", plan.allDone)
        .put("next_task_id", plan.current?.id.orEmpty())
        .put("summary", plan.summary())
        .toString()

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    companion object {
        const val TASK_WRITE = "task_write"
    }
}
