package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.provider.BuiltinProviders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenCodeModelImportTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
    }

    @Test fun freeSubsetImportPreservesPaidAndManuallyAddedModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val id = BuiltinProviders.OPENCODE_ID
        ProviderRepository.replaceModels(id, listOf(
            Model(id = "existing-remote", modelId = "paid-model", displayName = "付费模型",
                source = ModelSource.REMOTE),
            Model(id = "existing-manual", modelId = "custom", displayName = "用户添加",
                source = ModelSource.MANUAL),
        ))
        val freeModels = listOf(
            Model(id = "new-free-one", modelId = "beam-free", displayName = "免费 · beam-free",
                source = ModelSource.REMOTE),
            Model(id = "new-free-two", modelId = "glm-5.3-flash-free",
                displayName = "免费 · glm-5.3-flash-free", source = ModelSource.REMOTE),
        )
        val result = ModelRepository.importRemoteSubset(id, freeModels, setOf("beam-free"))
        assertTrue(result.applied)
        assertEquals(1, result.addedCount)
        assertEquals(
            listOf("paid-model", "custom", "beam-free"),
            ProviderRepository.providerById(id)!!.models.map { it.modelId },
        )
        assertEquals(
            listOf(ModelSource.REMOTE, ModelSource.MANUAL, ModelSource.REMOTE),
            ProviderRepository.providerById(id)!!.models.map { it.source },
        )
        val repeated = ModelRepository.importRemoteSubset(id, freeModels, setOf("beam-free"))
        assertFalse(repeated.applied)
        assertEquals(3, ProviderRepository.providerById(id)!!.models.size)
    }

    @Test fun builtInIsInsertedOnUpgradeWithoutChangingCurrentOtherProvider() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val original = ProviderRepository.providerById(BuiltinProviders.DEEPSEEK_ID)!!
        ProviderRepository.ensureBuiltInsMerged()
        val installed = ProviderRepository.providerById(BuiltinProviders.OPENCODE_ID)!!
        assertTrue(installed.isBuiltIn)
        assertEquals("https://opencode.ai/zen/v1", installed.baseUrl)
        assertEquals(original, ProviderRepository.providerById(BuiltinProviders.DEEPSEEK_ID))
    }
}
