package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** 一条解析后的子任务；[complexity] 是主代理显式声明的档位，null 表示交给启发式估。 */
private data class ParsedSubTask(
    val role: String,
    val prompt: String,
    val complexity: SubAgentComplexity?,
)

private data class SubAgentOutcome(
    val task: ParsedSubTask,
    val complexity: SubAgentComplexity,
    val result: Result<AgentModelClient.ToolResult>,
)

/**
 * 并行子代理。
 *
 * 一次调用可以带任意多个子任务，它们在进程内**同时**跑各自的 AgentLoop（独立上下文、
 * 独立取消控制器），全部结束后把结论汇总回主代理。
 *
 * 为什么是"一次调用带一批"而不是"多次调用各起一个"：`AgentLoop` 对同一轮返回的多个
 * tool call 是顺序执行的（`toolCalls.map { executeTool(...) }`），分多次调用根本并行不起来。
 *
 * 子代理刻意不允许操作屏幕：屏幕观察状态（observation_id）挂在每个工具执行器实例上，
 * 共享同一个实例会让两个 agent 的观察互相覆盖，现象是"点错位置"——比直接报错难查得多。
 * 需要看屏幕的步骤留给主代理，子代理只做读写文件、跑终端、查网络这类活。
 */
internal class AgentSubAgentTool(
    private val runOne: (
        role: String,
        prompt: String,
        complexity: SubAgentComplexity,
    ) -> AgentModelClient.ToolResult,
) {
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return failure("SUBAGENT_INVALID_ARGUMENTS", "参数不是合法 JSON")
        val array = args.optJSONArray("tasks") ?: JSONArray()
        if (array.length() == 0) return failure("SUBAGENT_EMPTY", "tasks 不能为空")
        if (array.length() > MAX_SUB_AGENTS) {
            return failure("SUBAGENT_TOO_MANY", "一次最多派 $MAX_SUB_AGENTS 个子代理")
        }
        val tasks = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val role = item.optString("role").trim().ifBlank { "子任务 ${index + 1}" }
            val prompt = item.optString("prompt").trim()
            if (prompt.isEmpty()) {
                null
            } else {
                ParsedSubTask(
                    role = role,
                    prompt = prompt,
                    complexity = SubAgentComplexity.fromWire(item.optString("complexity")),
                )
            }
        }
        if (tasks.isEmpty()) return failure("SUBAGENT_EMPTY", "每个子代理都需要 prompt")

        val results = runBlocking {
            coroutineScope {
                tasks.map { task ->
                    val complexity = SubAgentBudget.complexityOf(task.prompt, task.complexity)
                    async(Dispatchers.IO) {
                        SubAgentOutcome(
                            task = task,
                            complexity = complexity,
                            result = runCatching { runOne(task.role, task.prompt, complexity) },
                        )
                    }
                }.awaitAll()
            }
        }

        val payload = JSONArray()
        val summary = StringBuilder()
        var failures = 0
        results.forEach { outcome ->
            val result = outcome.result.getOrNull()
            // 子代理回的不是 JSON 时按成功处理：它是自然语言结论，不是错误。
            val ok = result != null && runCatching {
                JSONObject(result.content).optBoolean("ok", true)
            }.getOrDefault(true)
            if (!ok) failures++
            payload.put(
                JSONObject()
                    .put("role", outcome.task.role)
                    .put("ok", ok)
                    .put("complexity", outcome.complexity.wireValue)
                    .put("max_rounds", outcome.complexity.rounds)
                    .put(
                        "content",
                        (result?.content ?: outcome.result.exceptionOrNull()?.message.orEmpty())
                            .take(MAX_RESULT_CHARS),
                    ),
            )
            summary.append(if (ok) "### " else "### [失败] ").append(outcome.task.role)
                .append("（").append(outcome.complexity.rounds).append(" 轮上限）").append('\n')
            summary.append(
                (result?.content ?: outcome.result.exceptionOrNull()?.message ?: "无结果")
                    .take(MAX_RESULT_CHARS),
            )
            summary.append("\n\n")
        }

        return AgentModelClient.ToolResult(
            JSONObject()
                .put("ok", failures == 0)
                .put("count", tasks.size)
                .put("failed", failures)
                .put("agents", payload)
                .put("summary", summary.toString().trim())
                .toString(),
        )
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            JSONObject().put("ok", false).put("code", code).put("message", message).toString(),
        )

    companion object {
        const val SPAWN_AGENTS = "spawn_agents"

        /** 一次最多派几个：再多就别扭了，主代理自己串行做更快也更省。 */
        const val MAX_SUB_AGENTS = 6

        private const val MAX_RESULT_CHARS = 4_000

        fun schema(): JSONObject = AgentToolSchema.function(
            name = SPAWN_AGENTS,
            description = "并行派出多个子代理，让它们各自独立完成一件事，最后把结论汇总回来。" +
                "**只要任务里有两个以上互不依赖的步骤，就必须用它一次派出去，而不是自己一条条串行做**" +
                "（例如分别读几个模块、分别查几个来源、分别验证几个假设）。" +
                "每个子代理有独立上下文，看不到当前对话，所以 prompt 必须自带完整背景与交付标准。" +
                "子代理不能操作屏幕（不能观察界面、点击、输入），需要看屏幕的步骤必须由你自己做。" +
                "子代理不会反问，一次调用内完成；返回结果会标注每一条做没做成。",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "tasks",
                        JSONObject()
                            .put("type", "array")
                            .put("maxItems", MAX_SUB_AGENTS)
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put(
                                                "role",
                                                JSONObject().put("type", "string").put("maxLength", 40)
                                                    .put("description", "这个子代理的角色，用于汇总时区分，例如“读取配置模块”"),
                                            )
                                            .put(
                                                "prompt",
                                                JSONObject().put("type", "string").put("maxLength", 4_000)
                                                    .put("description", "完整任务说明，含背景、要做什么、交付什么"),
                                            )
                                            .put(
                                                "complexity",
                                                JSONObject()
                                                    .put("type", "string")
                                                    .put(
                                                        "enum",
                                                        JSONArray()
                                                            .put(SubAgentComplexity.Simple.wireValue)
                                                            .put(SubAgentComplexity.Normal.wireValue)
                                                            .put(SubAgentComplexity.Complex.wireValue),
                                                    )
                                                    .put(
                                                        "description",
                                                        "这条子任务的复杂度，决定它能跑多少轮：" +
                                                            "simple=6 轮（一两步就能做完）、normal=12 轮、" +
                                                            "complex=24 轮（要通读多份材料或反复验证）。" +
                                                            "省略时按 prompt 的长度与信号词自动估。不要一律填 complex。",
                                                    ),
                                            ),
                                    )
                                    .put("required", JSONArray().put("role").put("prompt")),
                            ),
                    ),
                )
                .put("required", JSONArray().put("tasks")),
        )
    }
}
