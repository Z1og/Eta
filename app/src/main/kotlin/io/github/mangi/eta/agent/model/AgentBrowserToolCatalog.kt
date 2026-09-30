package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "操作 Eta 共享的离屏 Agent 浏览器，不会切换到外部浏览器。一次调用只执行一个 action；网页浏览通常先 navigate，再用 get_readable 提取正文，或用 find_elements 查找可交互元素。支持 run_js 在当前页面执行任意 JavaScript，以及油猴（用户脚本）管理：add_script 安装（source 内联或 url 下载 .user.js）、list_scripts 列出、toggle_script 启停、remove_script 删除、debug_scripts 诊断注入链路；安装过的脚本按 @match/@include 在页面加载时自动注入并提供 GM_* API，注入结果可用 get_page_info 的 userscripts 字段核对。需要把 URI 显式交给外部应用时使用 open_uri。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "本次唯一执行的浏览器动作。")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("navigate")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                            .put("run_js")
                                            .put("list_scripts")
                                            .put("add_script")
                                            .put("toggle_script")
                                            .put("remove_script")
                                            .put("debug_scripts")
                                    )
                            )
                            .put(
                                "url",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "navigate 要访问的 URL。")
                            )
                            .put(
                                "selector",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "click、type、get_text、find_elements 或 wait_for_selector 使用的 CSS selector。")
                            )
                            .put(
                                "text",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "type 要输入的文本。只会发送给工具，不会显示在运行摘要中。")
                            )
                            .put(
                                "submit",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "type 输入后是否提交所在表单，默认 false。")
                            )
                            .put(
                                "coordinate_x",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 或 type 的视口 X 坐标，和 coordinate_y 一起使用。")
                            )
                            .put(
                                "coordinate_y",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 或 type 的视口 Y 坐标，和 coordinate_x 一起使用。")
                            )
                            .put(
                                "amount",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "scroll 的滚动像素量。")
                            )
                            .put(
                                "direction",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("up").put("down"))
                                    .put("description", "scroll 的滚动方向。")
                            )
                            .put(
                                "offset",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable 或 get_text 的文本起始偏移，默认 0。")
                            )
                            .put(
                                "max_chars",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable 或 get_text 最多返回的文本字符数。")
                            )
                            .put(
                                "read_image",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "screenshot 时是否把截图附给模型直接查看，默认 true。")
                            )
                            .put(
                                "timeout_ms",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "navigate 或 wait_for_selector 的超时毫秒数。")
                            )
                            .put(
                                "code",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "run_js 要在当前页面执行的 JavaScript 源码，最后一条表达式的值作为结果返回。")
                            )
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "add_script 内联提供的完整用户脚本源码（含 ==UserScript== 元数据块）。")
                            )
                            .put(
                                "script_id",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "toggle_script 或 remove_script 的目标脚本 id，来自 list_scripts。")
                            )
                            .put(
                                "enabled",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "toggle_script 的目标状态；缺省时在启用与停用之间切换。")
                            )
                    )
                    .put("required", JSONArray().put("action"))
            )
        )
    }

}
