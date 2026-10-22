package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.provider.BuiltinProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenCodeEmbeddedGatewayTest {
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("eta_embedded_opencode2api", Context.MODE_PRIVATE)
            .edit().clear().commit()
        OpenCodeEmbeddedGateway.init(context)
    }

    @Test fun localGatewayConfigCannotBindLanOrOpenManagementInterface() {
        val json = OpenCodeEmbeddedGateway.configuration(
            48123, "a".repeat(64), "legitimate_zen", "legitimate_go",
        )
        assertEquals("127.0.0.1:48123", json.getString("listen"))
        assertEquals(false, json.getJSONObject("webui").getBoolean("enabled"))
        assertEquals(true, json.getBoolean("anonymous"))
        assertTrue(json.getJSONObject("models").getBoolean("free_only"))
        assertEquals("direct", json.getJSONArray("proxies").getString(0))
        assertEquals("a".repeat(64), json.getJSONArray("server_keys").getString(0))
        assertEquals("legitimate_zen", json.getJSONArray("zen_keys").getString(0))
        assertEquals("legitimate_go", json.getJSONArray("go_keys").getString(0))
        assertFalse(json.toString().contains("0.0.0.0"))
    }

    @Test fun proxySettingSupportsDirectSystemFallbackAndExplicitHttpOrSocks() {
        assertEquals("", OpenCodeEmbeddedGateway.upstreamProxy())
        assertEquals("direct", OpenCodeEmbeddedGateway.configuration(48123, "a".repeat(64))
            .getJSONArray("proxies").getString(0))
        for (proxy in listOf("http://127.0.0.1:7890", "socks5://127.0.0.1:10808", "https://proxy.example.org:443")) {
            OpenCodeEmbeddedGateway.setUpstreamProxy(proxy)
            assertEquals(proxy, OpenCodeEmbeddedGateway.upstreamProxy())
            assertEquals(proxy, OpenCodeEmbeddedGateway.configuration(48123, "a".repeat(64), proxy = proxy)
                .getJSONArray("proxies").getString(0))
        }
        OpenCodeEmbeddedGateway.setUpstreamProxy("direct")
        assertEquals("direct", OpenCodeEmbeddedGateway.upstreamProxy())
        OpenCodeEmbeddedGateway.setUpstreamProxy("")
        assertEquals("", OpenCodeEmbeddedGateway.upstreamProxy())
    }

    @Test fun proxySettingRejectsCredentialsAndMalformedEndpoints() {
        for (bad in listOf("http://user:secret@127.0.0.1:8888", "ftp://127.0.0.1:90", "http://127.0.0.1", "socks5://127.0.0.1:99999", "http://x:8080/secret", "http://x:8080?key=secret")) {
            try {
                OpenCodeEmbeddedGateway.setUpstreamProxy(bad)
                throw AssertionError("Expected rejected proxy: $bad")
            } catch (_: IllegalArgumentException) {
                assertEquals("", OpenCodeEmbeddedGateway.upstreamProxy())
            }
        }
    }

    @Test fun upstreamLogClassificationRedactsDetails() {
        val dns = "time=2026-10-22 level=WARN event=request_failed error=\"dial tcp: lookup opencode.ai: no such host; token=SECRET\""
        val hint = OpenCodeEmbeddedGateway.classifyGatewayLog(dns)
        assertTrue(hint.contains("DNS"))
        assertFalse(hint.contains("SECRET"))
        val reset = OpenCodeEmbeddedGateway.classifyGatewayLog("event=request_failed error=\"read tcp: connection reset by peer\"")
        assertTrue(reset.contains("断开"))
        val empty = OpenCodeEmbeddedGateway.classifyGatewayLog("normal startup, no error")
        assertFalse(empty.contains("SECRET"))
    }

    @Test fun useLocalByDefaultButNeverOverwriteUserExternalApiValues() {
        val source = BuiltinProviders.providerById(BuiltinProviders.OPENCODE_ID)!!
        val userConfigured = (source as io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting)
            .copy(baseUrl = "https://example.org/v1", apiKey = "my-external-server-key")
        val exampleModel = Model("id", "beam-free", "Beam Free")

        assertTrue(OpenCodeEmbeddedGateway.useLocal(userConfigured))
        val local = RuntimeConfigRepository.buildRuntimeConfig(userConfigured, exampleModel)
        assertTrue(local.baseUrl.startsWith("http://127.0.0.1:"))
        assertTrue(local.baseUrl.endsWith("/v1"))
        assertEquals(64, local.apiKey.length)
        assertNotEquals(userConfigured.apiKey, local.apiKey)
        assertEquals("https://example.org/v1", userConfigured.baseUrl)
        assertEquals("my-external-server-key", userConfigured.apiKey)

        OpenCodeEmbeddedGateway.setEnabled(false)
        assertFalse(OpenCodeEmbeddedGateway.useLocal(userConfigured))
        val external = RuntimeConfigRepository.buildRuntimeConfig(userConfigured, exampleModel)
        assertEquals(userConfigured.baseUrl, external.baseUrl)
        assertEquals(userConfigured.apiKey, external.apiKey)
        assertEquals("my-external-server-key", userConfigured.apiKey)

        OpenCodeEmbeddedGateway.setEnabled(true)
        assertEquals(local.baseUrl, RuntimeConfigRepository.buildRuntimeConfig(userConfigured, exampleModel).baseUrl)
    }

    @Test fun upstreamKeysAreStoredOnlyForTheLocalGatewayAndAreOptional() {
        assertEquals("", OpenCodeEmbeddedGateway.zenKey())
        assertEquals("", OpenCodeEmbeddedGateway.goKey())
        OpenCodeEmbeddedGateway.setUpstreamKeys("zen-user-key", "go-user-key")
        assertEquals("zen-user-key", OpenCodeEmbeddedGateway.zenKey())
        assertEquals("go-user-key", OpenCodeEmbeddedGateway.goKey())
        val other = BuiltinProviders.providerById(BuiltinProviders.DEEPSEEK_ID)!!
        assertFalse(OpenCodeEmbeddedGateway.useLocal(other))
    }

    @Test fun rejectControlCharactersInUpstreamCredentials() {
        try {
            OpenCodeEmbeddedGateway.setUpstreamKeys("zen-user-key\nInjected: x", "")
            throw AssertionError("Expected invalid key rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals("", OpenCodeEmbeddedGateway.zenKey())
        }
    }

    @Test fun bundledPublicCatalogBootstrapsColdStartWithoutOverwritingLaterRefresh() {
        val app = RuntimeEnvironment.getApplication()
        val directory = File(app.cacheDir, "opencode-catalog-test-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        try {
            val config = File(directory, "config.json")
            val file = File(config.path + ".models.catalog.json")
            assertFalse(file.exists())
            assertTrue(OpenCodeEmbeddedGateway.seedModelCatalog(config) {
                app.assets.open("opencode2api-bootstrap-catalog-v3.json")
            })
            val json = org.json.JSONObject(file.readText())
            assertEquals(3, json.getInt("schema_version"))
            assertTrue(json.getJSONArray("zen").toString().contains("deepseek-v4.1-flash-free"))
            assertTrue(json.getJSONObject("native_protocols").getJSONObject("zen")
                .has("deepseek-v4.1-flash-free"))
            // Real gateway updates this file after successful online discovery;
            // an app restart must preserve newer data.
            file.writeText("refreshed-catalog")
            assertFalse(OpenCodeEmbeddedGateway.seedModelCatalog(config) {
                error("Existing cache must not reopen bootstrap asset")
            })
            assertEquals("refreshed-catalog", file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun malformedBootstrapNeverLeavesPartialCatalog() {
        val app = RuntimeEnvironment.getApplication()
        val directory = File(app.cacheDir, "opencode-invalid-test-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        try {
            val config = File(directory, "config.json")
            try {
                OpenCodeEmbeddedGateway.seedModelCatalog(config) {
                    ByteArrayInputStream("{}".toByteArray())
                }
                throw AssertionError("Invalid bootstrap should fail")
            } catch (_: IllegalArgumentException) {
                assertFalse(File(config.path + ".models.catalog.json").exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun inferenceReadinessRejectsProcessOnlyAndPendingCatalog() {
        assertFalse(OpenCodeEmbeddedGateway.isCatalogueReady(null))
        val listening = org.json.JSONObject()
            .put("status", "pending")
            .put("models", org.json.JSONObject().put("status", "pending"))
        assertFalse(OpenCodeEmbeddedGateway.isCatalogueReady(listening))
        assertFalse(OpenCodeEmbeddedGateway.isCatalogueReady(
            org.json.JSONObject().put("models", org.json.JSONObject().put("status", "empty"))
        ))
        for (status in listOf("ready", "stale")) {
            assertTrue(OpenCodeEmbeddedGateway.isCatalogueReady(
                org.json.JSONObject().put("models", org.json.JSONObject().put("status", status))
            ))
        }
    }

    @Test fun gatewayDiagnosticSeparatesProcessFromModelReadiness() {
        val pending = org.json.JSONObject()
            .put("status", "pending")
            .put("models", org.json.JSONObject().put("status", "loading"))
            .put("keys", org.json.JSONObject().put("anonymous", true).put("total", 0))
        val text = OpenCodeEmbeddedGateway.describeCatalogueHealth(pending)
        assertTrue(text.contains("目录尚未就绪"))
        assertTrue(text.contains("models.status=loading"))
        assertTrue(text.contains("匿名线路=已启用"))
        assertTrue(text.contains("上游 Key 数=0"))
        assertFalse(text.contains("keys.status=unknown"))
        val ready = org.json.JSONObject()
            .put("status", "ok")
            .put("models", org.json.JSONObject().put("status", "ready"))
            .put("keys", org.json.JSONObject().put("anonymous", true).put("total", 0))
        assertTrue(OpenCodeEmbeddedGateway.describeCatalogueHealth(ready).contains("目录已就绪"))
        assertTrue(OpenCodeEmbeddedGateway.describeCatalogueHealth(null).contains("无响应"))
    }
}
