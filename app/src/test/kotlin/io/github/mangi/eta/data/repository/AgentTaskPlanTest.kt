package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentTaskTool
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskPlanTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store() = AgentTaskPlanStore(temporaryFolder.root)

    @Test
    fun planRoundTripsAndAnEmptyPlanIsNeverConsideredComplete() {
        val store = store()
        val plan = AgentTaskPlan(
            listOf(
                AgentTaskItem("1", "读取注入链路", AgentTaskStatus.Done),
                AgentTaskItem("2", "改 seed 逻辑", AgentTaskStatus.InProgress, note = "改到一半"),
                AgentTaskItem("3", "补测试", AgentTaskStatus.Pending),
            ),
        )
        store.save("conv-a", plan)

        val loaded = store.load("conv-a")
        assertEquals(plan, loaded)
        assertEquals(2, loaded.openCount)
        assertEquals(1, loaded.doneCount)
        assertFalse(loaded.allDone)
        assertEquals("2", loaded.current?.id)

        // 空清单不能算完成：否则模型清空清单就能骗过续跑判定。
        assertFalse(AgentTaskPlan.EMPTY.allDone)
        assertTrue(loaded.summary().contains("[x] 1 读取注入链路"))
        assertTrue(loaded.summary().contains("[>] 2 改 seed 逻辑"))
    }

    @Test
    fun allDoneOnlyWhenEveryTaskIsSettled() {
        val plan = AgentTaskPlan(
            listOf(
                AgentTaskItem("1", "a", AgentTaskStatus.Done),
                AgentTaskItem("2", "b", AgentTaskStatus.Blocked),
            ),
        )
        assertEquals(0, plan.openCount)
        assertTrue(plan.allDone)
    }

    @Test
    fun toolReplacesTheWholePlanAndRejectsDuplicateIdsWithoutLosingTheOldOne() {
        val store = store()
        val tool = AgentTaskTool(store, "conv-a")
        tool.execute(
            call(
                """{"action":"replace","tasks":[{"id":"1","title":"旧任务","status":"pending"}]}""",
            ),
        )

        val duplicate = JSONObject(
            tool.execute(
                call(
                    """{"action":"replace","tasks":[
                        {"id":"1","title":"a","status":"pending"},
                        {"id":"1","title":"b","status":"pending"}]}""",
                ),
            ).content,
        )
        assertFalse(duplicate.getBoolean("ok"))
        assertEquals("TASK_DUPLICATE_ID", duplicate.getString("code"))
        // 参数错了不能顺手把旧清单清掉。
        assertEquals(1, store.load("conv-a").total)

        val replaced = JSONObject(
            tool.execute(
                call(
                    """{"action":"replace","tasks":[
                        {"id":"1","title":"a","status":"done"},
                        {"id":"2","title":"b","status":"in_progress"}]}""",
                ),
            ).content,
        )
        assertTrue(replaced.getBoolean("ok"))
        assertEquals(1, replaced.getInt("done"))
        assertEquals(1, replaced.getInt("open"))
        assertEquals("2", replaced.getString("next_task_id"))
    }

    @Test
    fun readReturnsTheStoredPlanAndUnknownActionsFail() {
        val store = store()
        val tool = AgentTaskTool(store, "conv-a")
        assertEquals(0, JSONObject(tool.execute(call("""{"action":"read"}""")).content).getInt("total"))

        store.save("conv-a", AgentTaskPlan(listOf(AgentTaskItem("1", "唯一一条", AgentTaskStatus.Pending))))
        val read = JSONObject(tool.execute(call("""{"action":"read"}""")).content)
        assertEquals(1, read.getInt("total"))

        val invalid = JSONObject(tool.execute(call("""{"action":"delete"}""")).content)
        assertFalse(invalid.getBoolean("ok"))
    }

    @Test
    fun clearingRemovesTheFileSoANewGoalStartsClean() {
        val store = store()
        store.save("conv-a", AgentTaskPlan(listOf(AgentTaskItem("1", "a", AgentTaskStatus.Pending))))
        store.clear("conv-a")

        assertEquals(0, store.load("conv-a").total)
        val goalDir = File(temporaryFolder.root, AgentGoalStore.DIRECTORY_NAME)
        assertFalse(goalDir.listFiles().orEmpty().any { it.name.endsWith(".tasks.json") })
    }

    private fun call(arguments: String) = AgentModelClient.ToolCall(
        id = "call-1",
        name = AgentTaskTool.TASK_WRITE,
        argumentsJson = arguments,
    )
}
