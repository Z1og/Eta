#!/usr/bin/env python3
"""Regression tests for the standalone Eta WebDAV companion (no server required)."""
import importlib.util
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("eta_webdav", Path(__file__).with_name("eta-webdav-sync.py"))
eta = importlib.util.module_from_spec(spec)
spec.loader.exec_module(eta)


class EtaWebDavCompanionTest(unittest.TestCase):
    @staticmethod
    def retained_node(
        identifier="ea41f121-989a-3f07-abcd-09a12b034d28",
        updated=1000,
        **changes,
    ):
        node = {
            "id": identifier, "text": "The user prefers Chinese answers in Eta",
            "layer": "semantic", "category": "preference", "scope": "global",
            "subject": "user.language", "entities": ["Eta"],
            "source_kind": "user_statement", "evidence": "请用中文回答",
            "source_runs": ["a" * 64], "confidence": 0.94,
            "links": [], "created_at": 1000, "updated_at": updated,
            "valid_from": 1000, "invalidated_at": 0,
            "deleted_at": 0, "occurrences": 1,
        }
        node.update(changes)
        return node
    class FakeWebDav:
        state = None
        version = 0

        def __init__(self, url, username, password):
            pass

        def ensure_folder(self):
            pass

        def read(self):
            if self.state is None:
                return None, None
            return self.state, f'"version-{self.version}"'

        def write(self, blob, etag):
            actual = None if self.state is None else f'"version-{self.version}"'
            if actual != etag:
                return False
            type(self).state = blob
            type(self).version += 1
            return True

    def test_encrypted_roundtrip_and_tampering(self):
        data = b'{"schema":1,"memory_md":"hello"}'
        encrypted = eta.encrypt(data, "cross-platform passphrase 12345")
        self.assertEqual(data, eta.decrypt(encrypted, "cross-platform passphrase 12345"))
        with self.assertRaises(ValueError):
            eta.decrypt(encrypted, "different long passphrase")
        modified = bytearray(encrypted)
        modified[-1] ^= 1
        with self.assertRaises(ValueError):
            eta.decrypt(bytes(modified), "cross-platform passphrase 12345")

    def test_memory_conflicts_fail_closed(self):
        base = eta.empty()
        base["memory_md"] = "base"
        local, remote = eta.empty(), eta.empty()
        local["memory_md"] = "local"
        remote["memory_md"] = "remote"
        with self.assertRaisesRegex(ValueError, "MEMORY_CONFLICT"):
            eta.merge(base, local, remote)

    def test_remote_knowledge_merge_retains_both_edits(self):
        doc = {
            "id": "fbce1e64-292f-4070-bd29-70b8e5ae1339",
            "title": "Guide", "content": "base", "revision": "a" * 64,
            "kind": "procedure", "source": "", "tags": [],
            "created_at": 1, "updated_at": 1, "deleted": False,
        }
        local = dict(doc, content="local", revision="b" * 64)
        remote = dict(doc, content="remote", revision="c" * 64)
        combined = eta.merge_knowledge([doc], [local], [remote])
        self.assertEqual(2, len(combined))
        self.assertEqual({"local", "remote"}, {n["content"] for n in combined})

    def test_concurrent_knowledge_delete_and_edit_retains_tombstone(self):
        doc = {
            "id": "fbce1e64-292f-4070-bd29-70b8e5ae1339",
            "title": "Guide", "content": "base", "revision": "a" * 64,
            "kind": "procedure", "source": "", "tags": [],
            "created_at": 1, "updated_at": 1, "deleted": False,
        }
        tombstone = dict(doc, content="", deleted=True, updated_at=2, revision="b" * 64)
        edited = dict(doc, content="updated independently", updated_at=3, revision="c" * 64)
        for left, right in ((tombstone, edited), (edited, tombstone)):
            merged = eta.merge_knowledge([doc], [left], [right])
            self.assertEqual(2, len(merged))
            self.assertTrue(next(n for n in merged if n["id"] == doc["id"])["deleted"])
            self.assertTrue(any(n["content"] == "updated independently" and not n["deleted"]
                                for n in merged))

    def test_manual_document_change_has_stable_revision_between_reads(self):
        import tempfile
        import json
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            prior = {
                "id": "fbce1e64-292f-4070-bd29-70b8e5ae1339",
                "title": "Guide", "content": "base", "revision": "a" * 64,
                "kind": "reference", "source": "", "tags": [],
                "created_at": 1, "updated_at": 1, "deleted": False,
            }
            baseline = eta.empty()
            baseline["knowledge"]["documents"] = [prior]
            edited = dict(prior, content="new manual value")
            (folder / "knowledge.json").write_text(
                json.dumps({"schema": 1, "documents": [edited]}), encoding="utf-8"
            )
            first = eta.read_local(folder, baseline)
            second = eta.read_local(folder, baseline)
            self.assertEqual(first, second)
            self.assertNotEqual(
                prior["revision"], first["knowledge"]["documents"][0]["revision"]
            )

    def test_experience_clear_prevents_resurrection(self):
        old = {"schema": 1, "cleared_at": 0,
               "items": [{"id": "a", "last_seen": 1, "tools": ["tap_element"]}]}
        cleared = {"schema": 1, "cleared_at": 2, "items": []}
        merged = eta.merge_experience(old, cleared)
        self.assertEqual([], merged["items"])
        self.assertEqual(2, merged["cleared_at"])

    def test_schema_one_upgrade_retains_existing_memory_and_adds_empty_nodes(self):
        old = {"schema": 1, "memory_md": "legacy",
               "knowledge": {"schema": 1, "documents": []},
               "experience": {"schema": 1, "cleared_at": 0, "items": []}}
        upgraded = eta.validate(old)
        self.assertEqual(3, upgraded["schema"])
        self.assertEqual("legacy", upgraded["memory_md"])
        self.assertEqual({"schema": 1, "cleared_at": 0, "items": []},
                         upgraded["retained"])

    def test_retained_nodes_merge_and_clear_cutoff(self):
        original = self.retained_node()
        left = {"schema": 1, "cleared_at": 0, "items": [original]}
        right = {"schema": 1, "cleared_at": 0, "items": [
            self.retained_node(updated=2000, source_runs=["b" * 64],
                               links=[{"target_id": "another", "type": "SAME_TOPIC", "confidence": 0.85}])
        ]}
        result = eta.merge_retained(left, right)
        self.assertEqual(1, len(result["items"]))
        self.assertEqual(2, len(result["items"][0]["source_runs"]))
        self.assertEqual(1, len(result["items"][0]["links"]))
        tombstoned = eta.merge_retained(result, {
            "schema": 1, "cleared_at": 0, "items": [
                self.retained_node(updated=3000, deleted_at=3000)
            ],
        })
        self.assertEqual(3000, tombstoned["items"][0]["deleted_at"])
        cleared = eta.merge_retained(tombstoned, {
            "schema": 1, "cleared_at": 4000, "items": []
        })
        self.assertEqual([], cleared["items"])

    def test_reviewed_graph_edges_survive_newer_model_proposals(self):
        confirmed = {
            "target_id": "b" * 36, "type": "SAME_TOPIC",
            "confidence": 0.91, "status": "confirmed", "updated_at": 2000,
            "reason": "Reviewed by user",
        }
        suggested = dict(confirmed, status="proposed", updated_at=9000,
                         reason="Model tried again")
        original = self.retained_node(links=[confirmed], updated=3000)
        stale = self.retained_node(links=[suggested], updated=10000)
        merged = eta.merge_retained(
            {"schema": 1, "cleared_at": 0, "items": [original]},
            {"schema": 1, "cleared_at": 0, "items": [stale]},
        )
        edge = merged["items"][0]["links"][0]
        self.assertEqual("confirmed", edge["status"])
        rejected = dict(confirmed, status="rejected", updated_at=4000)
        merged_again = eta.merge_retained(
            merged, {"schema": 1, "cleared_at": 0, "items": [
                self.retained_node(links=[rejected], updated=4100)
            ]},
        )
        self.assertEqual("rejected", merged_again["items"][0]["links"][0]["status"])

    def test_previous_schema_two_read_and_upgraded_to_three(self):
        old = eta.empty()
        old["schema"] = 2
        old["retained"]["items"] = [self.retained_node()]
        parsed = eta.validate(old)
        self.assertEqual(3, parsed["schema"])
        self.assertEqual(1, len(parsed["retained"]["items"]))

    def test_two_devices_roundtrip_via_conditional_encrypted_webdav(self):
        self.FakeWebDav.state = None
        self.FakeWebDav.version = 0
        values = {
            "ETA_WEBDAV_URL": "https://dav.example.net/Eta/",
            "ETA_WEBDAV_USER": "alice",
            "ETA_WEBDAV_PASSWORD": "placeholder",
            "ETA_SYNC_PASSPHRASE": "cross-platform test passphrase",
        }
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, values), patch.object(
            eta, "WebDav", self.FakeWebDav
        ):
            root = Path(temp)
            mobile = root / "phone"
            desktop = root / "desktop"
            mobile.mkdir()
            desktop.mkdir()
            (mobile / "MEMORY.md").write_text("# Core memory\nphone preference", encoding="utf-8")
            eta.sync(mobile)
            eta.sync(desktop)
            self.assertEqual(
                (desktop / "MEMORY.md").read_text(encoding="utf-8"),
                "# Core memory\nphone preference",
            )

            note_a = root / "note-a.md"
            note_b = root / "note-b.md"
            note_a.write_text("Android procedure", encoding="utf-8")
            note_b.write_text("PC reference", encoding="utf-8")
            eta.add_document(mobile, SimpleNamespace(
                file=str(note_a), title="Phone guide", kind="procedure", source="user", tag=["android"],
            ))
            eta.add_document(desktop, SimpleNamespace(
                file=str(note_b), title="PC guide", kind="reference", source="manual", tag=["pc"],
            ))
            eta.sync(mobile)
            eta.sync(desktop)
            eta.sync(mobile)
            docs = eta.read_json(mobile / "knowledge.json", {})["documents"]
            self.assertEqual({"Phone guide", "PC guide"}, {d["title"] for d in docs})
            self.assertTrue(self.FakeWebDav.state.startswith(eta.MAGIC))
            local_retain = eta.read_json(mobile / "retained_memories.json", {})
            local_retain["items"] = [self.retained_node(updated=2000)]
            eta.atomic_write(
                mobile / "retained_memories.json",
                eta.stable_json(local_retain).encode("utf-8"),
            )
            eta.sync(mobile)
            eta.sync(desktop)
            after = eta.read_json(desktop / "retained_memories.json", {})
            self.assertEqual(1, len(after["items"]))
            self.assertEqual("semantic", after["items"][0]["layer"])

    def test_explicit_conflict_resolution_requires_same_remote_revision(self):
        self.FakeWebDav.state = None
        self.FakeWebDav.version = 0
        values = {
            "ETA_WEBDAV_URL": "https://dav.example.net/Eta/",
            "ETA_WEBDAV_USER": "",
            "ETA_WEBDAV_PASSWORD": "",
            "ETA_SYNC_PASSPHRASE": "cross-platform test passphrase",
        }
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, values), patch.object(
            eta, "WebDav", self.FakeWebDav
        ):
            root = Path(temp)
            first, second = root / "first", root / "second"
            first.mkdir()
            second.mkdir()
            (first / "MEMORY.md").write_text("Initial memory", encoding="utf-8")
            eta.sync(first)
            eta.sync(second)

            (first / "MEMORY.md").write_text("Device A edit", encoding="utf-8")
            (second / "MEMORY.md").write_text("Device B edit", encoding="utf-8")
            eta.sync(first)
            with self.assertRaisesRegex(RuntimeError, "MEMORY_CONFLICT"):
                eta.sync(second)
            self.assertEqual("Device A edit", (second / "remote-memory-conflict.md").read_text())
            (second / "MEMORY.md").write_text("Reviewed combined memory", encoding="utf-8")

            # Another remote edit after review invalidates the explicit resolution authorization.
            payload = eta.validate(
                __import__("json").loads(
                    eta.decrypt(self.FakeWebDav.state, values["ETA_SYNC_PASSPHRASE"])
                )
            )
            payload["memory_md"] = "A newer remote edit"
            self.FakeWebDav.state = eta.encrypt(
                eta.stable_json(payload).encode("utf-8"), values["ETA_SYNC_PASSPHRASE"]
            )
            self.FakeWebDav.version += 1
            with self.assertRaisesRegex(RuntimeError, "MEMORY_CONFLICT"):
                eta.sync(second, resolve_memory_conflict=True)
            self.assertEqual("A newer remote edit",
                             (second / "remote-memory-conflict.md").read_text())

            # Confirmation is valid only after seeing the newest remote conflict.
            eta.sync(second, resolve_memory_conflict=True)
            eta.sync(first)
            self.assertEqual("Reviewed combined memory", (first / "MEMORY.md").read_text())


if __name__ == "__main__":
    unittest.main()
