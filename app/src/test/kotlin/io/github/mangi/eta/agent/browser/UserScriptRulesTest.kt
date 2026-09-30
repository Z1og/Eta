package io.github.mangi.eta.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptRulesTest {

    @Test
    fun parsesFullMetadataBlock() {
        val source = """
            // ==UserScript==
            // @name         链接高亮
            // @namespace    https://example.org/scripts
            // @version      1.2.3
            // @description  高亮页面里的外部链接
            // @match        https://example.com/*
            // @match        https://*.example.org/forum/*
            // @include      /^https:\/\/plain\.(net|cn)\//
            // @grant        GM_setValue
            // @grant        GM_xmlhttpRequest
            // @run-at       document-end
            // ==/UserScript==
            (function() { console.log('hi'); })();
        """.trimIndent()

        val meta = UserScriptRules.parse(source)
        assertTrue(meta.hasMetaBlock)
        assertEquals("链接高亮", meta.name)
        assertEquals("https://example.org/scripts", meta.namespace)
        assertEquals("1.2.3", meta.version)
        assertEquals("高亮页面里的外部链接", meta.description)
        assertEquals(listOf("https://example.com/*", "https://*.example.org/forum/*"), meta.matches)
        assertEquals(listOf("/^https:\\/\\/plain\\.(net|cn)\\//"), meta.includes)
        assertEquals(listOf("GM_setValue", "GM_xmlhttpRequest"), meta.grants)
        assertEquals(UserScriptRules.RUN_AT_END, meta.runAt)
    }

    @Test
    fun missingMetaBlockFallsBackToDefaults() {
        val meta = UserScriptRules.parse("alert('no meta');")
        assertFalse(meta.hasMetaBlock)
        assertNull(meta.name)
        assertTrue(meta.matches.isEmpty() && meta.includes.isEmpty())
        assertEquals(UserScriptRules.RUN_AT_IDLE, meta.runAt)
    }

    @Test
    fun runAtSynonymsNormalize() {
        assertEquals(UserScriptRules.RUN_AT_START, UserScriptRules.normalizeRunAt("document-start"))
        assertEquals(UserScriptRules.RUN_AT_START, UserScriptRules.normalizeRunAt("start"))
        assertEquals(UserScriptRules.RUN_AT_END, UserScriptRules.normalizeRunAt("document_end"))
        assertEquals(UserScriptRules.RUN_AT_IDLE, UserScriptRules.normalizeRunAt("idle"))
        assertEquals(UserScriptRules.RUN_AT_IDLE, UserScriptRules.normalizeRunAt(null))
        assertEquals(UserScriptRules.RUN_AT_IDLE, UserScriptRules.normalizeRunAt("unknown-value"))
    }

    @Test
    fun matchPatternSchemeAndHost() {
        val pattern = "https://example.com/*"
        assertTrue(UserScriptRules.matchPattern(pattern, "https://example.com/index.html"))
        assertTrue(UserScriptRules.matchPattern(pattern, "https://example.com/"))
        assertFalse(UserScriptRules.matchPattern(pattern, "http://example.com/"))
        assertFalse(UserScriptRules.matchPattern(pattern, "https://sub.example.com/"))
        assertFalse(UserScriptRules.matchPattern(pattern, "https://example.com.evil.io/"))
    }

    @Test
    fun matchPatternWildcardScheme() {
        assertTrue(UserScriptRules.matchPattern("*://example.com/*", "https://example.com/a"))
        assertTrue(UserScriptRules.matchPattern("*://example.com/*", "http://example.com/a"))
        assertFalse(UserScriptRules.matchPattern("*://example.com/*", "ftp://example.com/a"))
    }

    @Test
    fun matchPatternSubdomainWildcard() {
        val pattern = "https://*.example.org/*"
        assertTrue(UserScriptRules.matchPattern(pattern, "https://api.example.org/v1"))
        assertTrue(UserScriptRules.matchPattern(pattern, "https://example.org/v1"))
        assertFalse(UserScriptRules.matchPattern(pattern, "https://example.org.evil.io/v1"))
    }

    @Test
    fun matchPatternAnyHostAndPort() {
        val pattern = "https://*/api/*"
        assertTrue(UserScriptRules.matchPattern(pattern, "https://anything.io/api/v2/list"))
        assertFalse(UserScriptRules.matchPattern(pattern, "https://anything.io/other"))
        // 端口不参与匹配
        assertTrue(UserScriptRules.matchPattern("https://example.com/*", "https://example.com:8443/x"))
    }

    @Test
    fun matchPatternPathGlobAndQuery() {
        assertTrue(UserScriptRules.matchPattern("https://example.com/forum/*", "https://example.com/forum/t/1"))
        assertFalse(UserScriptRules.matchPattern("https://example.com/forum/*", "https://example.com/forums/t/1"))
        assertTrue(UserScriptRules.matchPattern("https://example.com/search*", "https://example.com/search?q=eta"))
    }

    @Test
    fun matchIncludeGlobAndRegex() {
        assertTrue(UserScriptRules.matchInclude("*.example.com/*", "https://www.example.com/x"))
        assertTrue(UserScriptRules.matchInclude("/^https:\\/\\/plain\\.(net|cn)\\//", "https://plain.net/a"))
        assertFalse(UserScriptRules.matchInclude("/^https:\\/\\/plain\\.(net|cn)\\//", "https://plain.com/a"))
        assertFalse(UserScriptRules.matchInclude("*.example.com/*", "https://example.org/x"))
    }

    @Test
    fun malformedPatternsNeverMatch() {
        assertFalse(UserScriptRules.matchPattern("example.com/*", "https://example.com/"))
        assertFalse(UserScriptRules.matchPattern("", "https://example.com/"))
        assertFalse(UserScriptRules.matchInclude("[unclosed", "https://example.com/"))
    }
}
