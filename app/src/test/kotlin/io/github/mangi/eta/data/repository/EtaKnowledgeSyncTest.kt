package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaKnowledgeSyncTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun knowledgeSurvivesReloadAndRequiresRevisionForEdits() {
        val root = folder.newFolder()
        val store = AgentKnowledgeStore(root)
        val first = store.save(
            title = "微信操作", content = "启动微信后观察界面再点击。",
            kind = "procedure", source = "用户提供", tags = listOf("微信", "UI"),
        )
        val reopened = AgentKnowledgeStore(root)
        assertEquals(first, reopened.get(first.id))
        assertEquals(first.id, reopened.search("微信", 4).first().id)
        assertThrows(IllegalArgumentException::class.java) {
            reopened.save("变更", "内容", id = first.id, expectedRevision = "wrong")
        }
        val updated = reopened.save("微信操作", "先观察节点并处理过期 ID。",
            kind = "procedure", source = "实测", id = first.id, expectedRevision = first.revision)
        assertNotEquals(first.revision, updated.revision)
        assertEquals(1, reopened.count())
    }

    @Test fun twoDevicesEditingSameNoteRetainBothRevisions() {
        val store = AgentKnowledgeStore(folder.newFolder())
        val before = store.save("步骤", "最初的操作")
        val left = store.save("步骤", "本地新操作", id = before.id, expectedRevision = before.revision)
        val right = before.copy(
            content = "远端新操作", updatedAt = left.updatedAt + 1,
            revision = AgentKnowledgeStore.sha256("remote-change"))
        val merged = KnowledgeMerge.merge(listOf(before), listOf(left), listOf(right))
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.id == left.id && it.content == "本地新操作" })
        assertTrue(merged.any { it.content == "远端新操作" && it.title.contains("conflict copy") })
        assertEquals(merged, KnowledgeMerge.merge(listOf(before), merged, listOf(right)))
    }

    @Test fun deletionTombstoneCannotReappearFromStalePeer() {
        val store = AgentKnowledgeStore(folder.newFolder())
        val note = store.save("过期文档", "不再使用")
        assertTrue(store.delete(note.id, note.revision))
        val deleted = store.snapshot()
        assertTrue(deleted.single().deleted)
        val merged = KnowledgeMerge.merge(listOf(note), deleted, listOf(note))
        assertTrue(merged.single().deleted)
    }

    @Test fun simultaneousEditAndDeleteRetainTombstoneAndReviewableCopy() {
        val store = AgentKnowledgeStore(folder.newFolder())
        val original = store.save("Shared note", "previous")
        assertTrue(store.delete(original.id, original.revision))
        val tombstone = store.snapshot().single()
        val edited = original.copy(
            content = "important remote changes",
            updatedAt = original.updatedAt + 1,
            revision = AgentKnowledgeStore.sha256("remote-document-edited"),
        )

        listOf(
            KnowledgeMerge.merge(listOf(original), listOf(tombstone), listOf(edited)),
            KnowledgeMerge.merge(listOf(original), listOf(edited), listOf(tombstone)),
        ).forEach { merged ->
            assertEquals(2, merged.size)
            assertTrue(merged.single { it.id == original.id }.deleted)
            assertTrue(merged.any {
                !it.deleted && it.content == "important remote changes" &&
                    it.title.contains("conflict copy")
            })
        }
    }

    @Test fun manuallyReviewedMemoryCanBeSelectedWithoutDiscardingRemoteKnowledge() {
        val baseline = EtaSyncPayload("before", emptyList(), ExperienceSyncSnapshot.EMPTY)
        val local = baseline.copy(memoryMarkdown = "reviewed combined memory")
        val remoteDoc = AgentKnowledgeStore(folder.newFolder()).save("Remote", "new")
        val remote = baseline.copy(memoryMarkdown = "separate remote edit", knowledge = listOf(remoteDoc))
        assertTrue(EtaSyncMerge.compute(baseline, local, remote) is EtaSyncMerge.MemoryConflict)
        val selected = EtaSyncMerge.compute(
            baseline, local, remote.copy(memoryMarkdown = baseline.memoryMarkdown),
        ) as EtaSyncMerge.Merged
        assertEquals("reviewed combined memory", selected.payload.memoryMarkdown)
        assertEquals(listOf(remoteDoc), selected.payload.knowledge)
    }

    @Test fun memoryThreeWayMergeNeverOverwritesDivergentEdits() {
        val base = EtaSyncPayload("old", emptyList(), ExperienceSyncSnapshot.EMPTY)
        val local = base.copy(memoryMarkdown = "phone edit")
        val remote = base.copy(memoryMarkdown = "desktop edit")
        assertTrue(EtaSyncMerge.compute(base, local, remote) is EtaSyncMerge.MemoryConflict)
        val merged = EtaSyncMerge.compute(base, local, base) as EtaSyncMerge.Merged
        assertEquals("phone edit", merged.payload.memoryMarkdown)
        assertEquals("desktop edit",
            (EtaSyncMerge.compute(base, base, remote) as EtaSyncMerge.Merged).payload.memoryMarkdown)
    }

    @Test fun clearedExperienceDoesNotReappearWhenRemoteIsOld() {
        val prior = AgentTaskExperience(
            "f92d561f-6f23-4aca-a8dc-6b36d3eab18b",
            listOf("微信"), listOf("tap_element"), emptyList(),
            true, 10, 100, 1)
        val old = ExperienceSyncSnapshot(listOf(prior), 0L)
        val cleared = ExperienceSyncSnapshot(emptyList(), 101L)
        val merged = ExperienceSyncSnapshot.merge(cleared, old)
        assertTrue(merged.items.isEmpty())
        assertEquals(101L, merged.clearedAt)
    }

    @Test fun webdavEncryptionRequiresTheCorrectPassphraseAndIntegrity() {
        val passphrase = "a very strong shared phrase".toCharArray()
        val clear = EtaSyncPayload(
            "# 核心记忆\\n测试", emptyList(), ExperienceSyncSnapshot.EMPTY,
        ).json()
        val encrypted = EtaWebDavCrypto.encrypt(clear, passphrase)
        assertEquals(clear, EtaWebDavCrypto.decrypt(encrypted, passphrase))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("核心记忆"))
        assertThrows(IllegalArgumentException::class.java) {
            EtaWebDavCrypto.decrypt(encrypted, "the wrong passphrase".toCharArray())
        }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            EtaWebDavCrypto.decrypt(encrypted, passphrase)
        }
    }

    @Test fun pythonAndAndroidEncryptionFormatsAreInteroperable() {
        // Generated with scripts/eta-webdav-sync.py encrypt() and cryptography AESGCM.
        val pythonBlob = java.util.Base64.getDecoder().decode(
            "RVRBU1lOQzEaUqCewQaJCpr6WVOubVLs6Tk6NsP9/wASCKeNFrW06coEgFpwkUlfLJqPMpy5GgAj/JSACpgUvEXoWUiq1NHH/axYoAkc4zCVfnE=",
        )
        assertEquals(
            "Eta Python Android interop 2026",
            EtaWebDavCrypto.decrypt(pythonBlob, "cross-platform passphrase 12345".toCharArray()),
        )
    }

    @Test fun webdavEndpointRejectsPlainHttpAndCredentialsInUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            EtaWebDavTransport.validateEndpoint("http://example.org/dav/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EtaWebDavTransport.validateEndpoint("https://user:password@example.org/dav/")
        }
        assertEquals("https", EtaWebDavTransport.validateEndpoint("https://example.org/dav/").scheme)
    }
}
