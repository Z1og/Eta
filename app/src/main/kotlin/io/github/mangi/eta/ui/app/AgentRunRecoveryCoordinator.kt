package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

/** 用持久 checkpoint、终态 outbox 与 Runtime 活跃状态共同判定恢复动作。 */
internal object AgentRunRecoveryCoordinator {
    data class Completed(
        val result: AgentRuntimeWire.CompletedRun,
        val checkpoint: AgentRunCheckpointStore.Checkpoint?,
    )

    data class Plan(
        val completed: List<Completed>,
        val reattach: AgentRunCheckpointStore.Checkpoint?,
        val interrupted: List<AgentRunCheckpointStore.Checkpoint>,
    )

    fun plan(
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
        completedRuns: List<AgentRuntimeWire.CompletedRun>,
        activeStateKnown: Boolean,
        terminalStateKnown: Boolean,
        activeRunId: String?,
        locallyObservedRunId: String?,
        /**
         * Runtime 状态查不到（冷启动首轮 binder 未就绪）时，把遗留 checkpoint 视为中断。
         * Runtime 与 UI 同进程：进程还活着就一定能在本地看到在跑的 run，
         * 所以调用方只会在确认本地没有活动 run 时才打开这个开关。
         */
        treatUnknownAsInterrupted: Boolean = false,
    ): Plan {
        val uiCheckpoints = checkpoints
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .associateBy { it.runId }
        val completed = completedRuns
            .asSequence()
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .filterNot { it.stableRunId == locallyObservedRunId }
            .sortedBy { it.createdAt }
            .map { run -> Completed(run, uiCheckpoints[run.stableRunId]) }
            .toList()
        val completedRunIds = completed.mapTo(mutableSetOf()) { it.result.stableRunId }
        val unresolved = uiCheckpoints.values
            .filterNot { it.runId == locallyObservedRunId || it.runId in completedRunIds }
            .sortedBy { it.createdAt }
        val active = unresolved
            .takeIf { activeStateKnown }
            ?.singleOrNull { it.runId == activeRunId }

        return Plan(
            completed = completed,
            reattach = active,
            interrupted = if (activeStateKnown && terminalStateKnown) {
                unresolved.filterNot { it.runId == active?.runId }
            } else if (treatUnknownAsInterrupted) {
                unresolved.filterNot { it.runId == active?.runId }
            } else {
                emptyList()
            },
        )
    }

    internal val AgentRuntimeWire.CompletedRun.stableRunId: String
        get() = result.runId.ifBlank { handoff.id }
}
