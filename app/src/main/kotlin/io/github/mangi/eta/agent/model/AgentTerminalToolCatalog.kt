package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.terminal.TerminalToolContract
import org.json.JSONArray

internal object AgentTerminalToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "terminal",
                description = "在当前设备执行命令并管理终端任务。exec 执行单次命令；open 创建持久会话后以 exec/session_id 复用。" +
                    "environment=android 使用 Android 命令环境，Root 可能使用 BusyBox ash；environment=linux 使用用户选择的 Linux 环境。" +
                    "identity=user 是 Eta 的 App UID，不是 ADB shell；PRoot 内的模拟 root 不提供 Android 特权。" +
                    "async=true 启动属于当前 Agent run 的异步命令，通过 read_async_result 读取输出，close 取消任务或关闭会话。" +
                    "daemon_start 用于需要跨 Agent run 运行的服务，由 daemon_list/daemon_logs/daemon_stop 管理；" +
                    "进程仍可能因退出、系统回收、权限变化或重启而终止。命令可解析不代表有权执行。" +
                    "已知文件的读取、写入、编辑和搜索优先使用对应文件工具。",
                parameters = TerminalToolContract.schema(),
            ),
        )
    }
}
