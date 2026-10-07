package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentWebToolCatalogTest {
    private val tools = JSONArray().also(AgentWebToolCatalog::appendTo)
    private val validator = AgentToolCallValidator(tools)

    @Test
    fun searchRequiresAQueryAndBoundsResultCount() {
        assertNull(validate("web_search", """{"query":"Android 文档","max_results":5}"""))
        assertNotNull(validate("web_search", "{}"))
        assertNotNull(validate("web_search", """{"query":""}"""))
        assertNotNull(validate("web_search", """{"query":"Android","max_results":11}"""))
        assertNotNull(validate("web_search", """{"query":"Android","max_results":0}"""))
        assertNotNull(validate("web_search", """{"query":"Android","api_key":"unexpected"}"""))
    }

    @Test
    fun fetchRequiresExactlyOneSource() {
        assertNull(validate("fetch_url", """{"url":"https://example.com/article"}"""))
        assertNull(validate("fetch_url", """{"document_id":"doc-1","offset_chars":12000}"""))
        assertNotNull(validate("fetch_url", "{}"))
        assertNotNull(validate("fetch_url", """{"url":"https://example.com","document_id":"doc-1"}"""))
        assertNotNull(validate("fetch_url", """{"document_id":""}"""))
    }

    @Test
    fun fetchPaginationRejectsInvalidRangesAndUnknownFields() {
        assertNotNull(validate("fetch_url", """{"document_id":"doc-1","offset_chars":-1}"""))
        assertNotNull(validate("fetch_url", """{"document_id":"doc-1","max_chars":999}"""))
        assertNotNull(validate("fetch_url", """{"document_id":"doc-1","max_chars":16001}"""))
        assertNotNull(validate("fetch_url", """{"url":"https://example.com","cookie":"unexpected"}"""))
        assertNull(validate("fetch_url", """{"document_id":"doc-1","max_chars":1000}"""))
    }

    @Test
    fun publicWebToolsNeedNeitherRootNorAccessibility() {
        listOf("web_search", "fetch_url").forEach { name ->
            val requirement = requireNotNull(AgentToolRequirements.find(name))
            assertEquals(RootRequirement.NONE, requirement.rootRequirement)
            assertEquals(false, requirement.accessibility)
        }
    }

    private fun validate(name: String, arguments: String): String? =
        validator.validate(AgentModelClient.ToolCall("schema-test", name, arguments))
}
