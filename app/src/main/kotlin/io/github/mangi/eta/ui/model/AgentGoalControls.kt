package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/**
 * 目标模式在输入区的全部交互面。
 *
 * 打包成一个对象而不是七个独立形参：目标行与输入栏是同一条渲染链，
 * 逐层透传七个 lambda 只会让漏传变成默认静默失效。
 */
@Immutable
internal data class AgentGoalControls(
    /** 当前会话的目标正文；null 表示没有目标。 */
    val goal: String? = null,
    /** 已暂停：run 挂起在下一个检查点，等用户继续。 */
    val paused: Boolean = false,
    /** 有 run 正在跑（暂停/继续只在这时有意义）。 */
    val running: Boolean = false,
    /** 目标拆解进度；null 表示还没拆过子任务。 */
    val taskProgress: TaskProgressUi? = null,
    /** 完整清单，点开目标时展开。 */
    val tasks: List<TaskItemUi> = emptyList(),
    val onSave: (String) -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onClear: () -> Unit = {},
) {
    val hasGoal: Boolean get() = !goal.isNullOrBlank()

    /** 目标行只展示首行，避免长正文把输入框挤走。 */
    val preview: String
        get() = goal.orEmpty().lineSequence().firstOrNull()?.trim()?.take(120).orEmpty()

    companion object {
        val NONE = AgentGoalControls()
    }
}
