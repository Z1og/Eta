package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderSourceTypes
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class OpenCodeGatewayHeadersTest {
    @Test fun gatewayReceivesStableSessionAffinityWithoutUsingTheUpstreamAccountKey() {
        val session = "test-conversation-one"
        val one = Headers.Builder()
        ProviderRequestHeaders.mergeInto(
            one, "https://gateway.example.org/v1", emptyList(),
            session, ProviderSourceTypes.OPENCODE,
        )
        val first = one.build()
        assertNotNull(first["x-opencode-session"])
        assertEquals("Eta", first["User-Agent"])
        val two = Headers.Builder()
        ProviderRequestHeaders.mergeInto(
            two, "https://gateway.example.org/v1", emptyList(),
            session, ProviderSourceTypes.OPENCODE,
        )
        assertEquals(first["x-opencode-session"], two.build()["x-opencode-session"])
        assertFalse(first["x-opencode-session"] == session)
    }

    @Test fun otherCompatibleProvidersDoNotReceiveOpenCodeAffinityHeader() {
        val builder = Headers.Builder()
        ProviderRequestHeaders.mergeInto(
            builder, "https://other-provider.example/v1", emptyList(),
            "conversation", ProviderSourceTypes.DEEPSEEK,
        )
        assertEquals(null, builder.build()["x-opencode-session"])
    }
}
