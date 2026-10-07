package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentFileToolCatalogTest {
    @Test fun textAndSearchArgumentsRejectAmbiguousPagingAndUnknownFields() {
        val validator = AgentToolCallValidator(AgentToolCatalog.build(true, false))
        assertNull(validator.validate(call("read_file", """{"path":"config.json","start_line":5}""")))
        assertNotNull(validator.validate(call("read_file", """{"path":"config.json","start_line":5,"offset_bytes":10}""")))
        assertNotNull(validator.validate(call("edit_file", """{"path":"config.json","old_text":"","new_text":"x"}""")))
        assertNotNull(validator.validate(call("grep_files", """{"query":"name","unexpected":true}""")))
        assertNull(validator.validate(call("glob_files", """{"pattern":"**/*.json","environment":"linux"}""")))
    }

    @Test fun allFileToolsAreNarrowedToAppIdentityWithoutRoot() {
        val validator = AgentToolCallValidator(AgentToolCatalog.build(true, false, capabilities = AgentToolCapabilities(rootAvailable = false)))
        val arguments = mapOf(
            "read_file" to """{"path":"config.json"}""",
            "write_file" to """{"path":"config.json","content":"{}"}""",
            "edit_file" to """{"path":"config.json","old_text":"a","new_text":"b"}""",
            "stat_file" to """{"path":"config.json"}""",
            "list_directory" to "{}",
            "glob_files" to """{"pattern":"**/*"}""",
            "grep_files" to """{"query":"test"}""",
        )
        arguments.forEach { (name, raw) ->
            assertNotNull(name, validator.validate(call(name, JSONObject(raw).put("identity", "root").toString())))
            assertNull(name, validator.validate(call(name, JSONObject(raw).put("identity", "user").toString())))
        }
    }

    private fun call(name: String, arguments: String) = AgentModelClient.ToolCall("file-test", name, arguments)
}
