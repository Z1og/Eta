#!/usr/bin/env python3
"""Eta encrypted WebDAV sync companion for Windows, macOS and Linux.

Requires: pip install cryptography
Configure: ETA_WEBDAV_URL, ETA_WEBDAV_USER, ETA_WEBDAV_PASSWORD,
           ETA_SYNC_PASSPHRASE (same passphrase as Android Eta).
Usage: python scripts/eta-webdav-sync.py sync --dir ./eta-knowledge
       python scripts/eta-webdav-sync.py add --dir ./eta-knowledge --title ... --file note.md
       python scripts/eta-webdav-sync.py list --dir ./eta-knowledge
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAGIC = b"ETASYNC1"
MAX_BYTES = 7 * 1024 * 1024
HEADER_BYTES = 8 + 16 + 12
MAX_NOTES = 512
MAX_EXPERIENCES = 256
MAX_RETAINED = 400


def stable_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=False)


def empty() -> dict:
    return {
        "schema": 3, "memory_md": "",
        "knowledge": {"schema": 1, "documents": []},
        "experience": {"schema": 1, "cleared_at": 0, "items": []},
        "retained": {"schema": 1, "cleared_at": 0, "items": []},
    }


def validate(payload: dict) -> dict:
    if payload.get("schema") not in (1, 2, 3):
        raise ValueError("Unsupported sync schema")
    payload = dict(payload)
    payload["schema"] = 3
    payload.setdefault("retained", {"schema": 1, "cleared_at": 0, "items": []})
    knowledge = payload.get("knowledge", {})
    experience = payload.get("experience", {})
    retained = payload["retained"]
    if knowledge.get("schema") != 1 or experience.get("schema") != 1 or retained.get("schema") != 1:
        raise ValueError("Unsupported knowledge/experience schema")
    if len(payload.get("memory_md", "").encode("utf-8")) > 1024 * 1024:
        raise ValueError("Memory exceeds 1 MiB")
    if len(knowledge.get("documents", [])) > MAX_NOTES:
        raise ValueError("Knowledge exceeds 512 documents")
    if len(experience.get("items", [])) > MAX_EXPERIENCES:
        raise ValueError("Experiences exceed 256 entries")
    if len(retained.get("items", [])) > MAX_RETAINED:
        raise ValueError("Retained memory exceeds 400 entries")
    if len(stable_json(payload).encode("utf-8")) > MAX_BYTES:
        raise ValueError("Sync payload exceeds 7 MiB")
    return payload


def encryption_key(secret: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), salt, 180_000, 32)


def encrypt(plain: bytes, secret: str) -> bytes:
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError as e:
        raise RuntimeError("Install dependency: python -m pip install cryptography") from e
    if len(secret) < 12:
        raise ValueError("Encryption passphrase must be at least 12 characters")
    salt, nonce = os.urandom(16), os.urandom(12)
    ciphertext = AESGCM(encryption_key(secret, salt)).encrypt(nonce, plain, MAGIC)
    return MAGIC + salt + nonce + ciphertext


def decrypt(blob: bytes, secret: str) -> bytes:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    if len(blob) < HEADER_BYTES + 16 or len(blob) > MAX_BYTES + 128 or not blob.startswith(MAGIC):
        raise ValueError("Invalid encrypted Eta sync file")
    try:
        return AESGCM(encryption_key(secret, blob[8:24])).decrypt(blob[24:36], blob[36:], MAGIC)
    except Exception as e:
        raise ValueError("Wrong sync passphrase or modified encrypted file") from e


class RejectRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("WebDAV redirect refused; verify your configured URL")


class WebDav:
    def __init__(self, url: str, username: str, password: str):
        parts = urllib.parse.urlsplit(url)
        if parts.scheme != "https" or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
            raise ValueError("Use a valid HTTPS WebDAV directory URL without embedded credentials")
        base = url if url.endswith("/") else url + "/"
        self.folder = urllib.parse.urljoin(base, "eta-sync-v1/")
        self.resource = urllib.parse.urljoin(self.folder, "state.bin")
        self.auth = None
        if username:
            token = base64.b64encode(f"{username}:{password}".encode()).decode("ascii")
            self.auth = "Basic " + token
        self.opener = urllib.request.build_opener(RejectRedirect())

    def request(self, method: str, url: str, data: bytes | None = None,
                condition: tuple[str, str] | None = None):
        headers = {"Cache-Control": "no-store"}
        if self.auth:
            headers["Authorization"] = self.auth
        if condition:
            headers[condition[0]] = condition[1]
        if data is not None:
            headers["Content-Type"] = "application/octet-stream"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            return self.opener.open(req, timeout=80)
        except urllib.error.HTTPError as error:
            return error

    def ensure_folder(self):
        with self.request("MKCOL", self.folder) as response:
            if response.code not in (201, 405):
                raise RuntimeError(f"WebDAV MKCOL failed: HTTP {response.code}")

    def read(self) -> tuple[bytes | None, str | None]:
        with self.request("GET", self.resource) as response:
            if response.code == 404:
                return None, None
            if response.code != 200:
                raise RuntimeError(f"WebDAV GET failed: HTTP {response.code}")
            etag = response.headers.get("ETag", "").strip()
            if not re.fullmatch(r'"[^\r\n]*"', etag):
                raise RuntimeError("WebDAV server must supply a strong ETag")
            blob = response.read(MAX_BYTES + 129)
            if len(blob) > MAX_BYTES + 128:
                raise ValueError("Encrypted WebDAV file is too large")
            return blob, etag

    def write(self, blob: bytes, etag: str | None) -> bool:
        condition = ("If-Match", etag) if etag else ("If-None-Match", "*")
        with self.request("PUT", self.resource, blob, condition) as response:
            if response.code in (200, 201, 204):
                return True
            if response.code in (409, 412):
                return False
            raise RuntimeError(f"WebDAV conditional PUT failed: HTTP {response.code}")


def atomic_write(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(dir=path.parent, prefix=path.name + ".tmp-")
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def read_json(path: Path, fallback: dict) -> dict:
    if not path.exists():
        return copy.deepcopy(fallback)
    return json.loads(path.read_text(encoding="utf-8"))


def read_local(directory: Path, base: dict | None) -> dict:
    default = validate(base) if base is not None else empty()
    memory_file = directory / "MEMORY.md"
    knowledge = read_json(directory / "knowledge.json", default["knowledge"])
    experience = read_json(directory / "task_experiences.json", default["experience"])
    retained = read_json(directory / "retained_memories.json", default["retained"])
    memory = memory_file.read_text(encoding="utf-8") if memory_file.exists() else default["memory_md"]
    result = validate({
        "schema": 2, "memory_md": memory, "knowledge": knowledge,
        "experience": experience, "retained": retained,
    })
    previous = {d["id"]: d for d in default["knowledge"]["documents"]}
    # Raw JSON editors can change the body without knowing Eta's revision protocol.
    for note in result["knowledge"]["documents"]:
        older = previous.get(note.get("id"))
        if older and note != older and note.get("revision") == older.get("revision"):
            # Deterministic until the file is saved: reading twice during one sync
            # must not fabricate a new edit and falsely trigger a local race.
            comparison = {k: v for k, v in note.items() if k not in ("revision", "updated_at")}
            note["updated_at"] = max(note.get("updated_at", 0), older.get("updated_at", 0) + 1)
            note["revision"] = hashlib.sha256(stable_json(comparison).encode()).hexdigest()
    return result


def revision_changed(value: dict | None, base: dict | None) -> bool:
    if value is None:
        return base is not None
    if base is None:
        return True
    return value != base


def java_name_uuid(raw: str) -> str:
    digest = bytearray(hashlib.md5(raw.encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def merge_knowledge(base: list[dict], local: list[dict], remote: list[dict]) -> list[dict]:
    old = {n["id"]: n for n in base}
    left = {n["id"]: n for n in local}
    right = {n["id"]: n for n in remote}
    ids = sorted(set(left) | set(right))
    merged = {}
    for id_ in ids:
        b, l, r = old.get(id_), left.get(id_), right.get(id_)
        if l is None:
            merged[id_] = r
        elif r is None:
            merged[id_] = l
        elif l == r or l.get("revision") == r.get("revision"):
            merged[id_] = l
        elif l == b or l.get("revision") == (b or {}).get("revision"):
            merged[id_] = r
        elif r == b or r.get("revision") == (b or {}).get("revision"):
            merged[id_] = l
        else:
            preferred = (l if l.get("deleted") else r) if (l.get("deleted") or r.get("deleted")) else l
            conflict = r if preferred is l else l
            merged[id_] = preferred
            if not conflict.get("deleted"):
                new_id = java_name_uuid("eta-knowledge-conflict:" + id_ + ":" + conflict["revision"])
                if new_id not in ids:
                    replica = copy.deepcopy(conflict)
                    replica["id"] = new_id
                    replica["title"] = replica["title"][:138] + " (sync conflict copy)"
                    merged[new_id] = replica
    if len(merged) > MAX_NOTES:
        raise RuntimeError("Knowledge capacity reached; no records discarded")
    return sorted(merged.values(), key=lambda n: (-n.get("updated_at", 0), n["id"]))


def merge_experience(local: dict, remote: dict) -> dict:
    cleared = max(local.get("cleared_at", 0), remote.get("cleared_at", 0))
    joined = {}
    for row in local.get("items", []) + remote.get("items", []):
        if row.get("last_seen", 0) <= cleared:
            continue
        prior = joined.get(row["id"])
        if prior is None or (row.get("last_seen", 0), row.get("occurrences", 0)) > (
                prior.get("last_seen", 0), prior.get("occurrences", 0)):
            joined[row["id"]] = row
    ordered = sorted(joined.values(), key=lambda r: r.get("last_seen", 0), reverse=True)
    return {"schema": 1, "cleared_at": cleared, "items": ordered[:MAX_EXPERIENCES]}


def merge_retained(local: dict, remote: dict) -> dict:
    cleared = max(local.get("cleared_at", 0), remote.get("cleared_at", 0))
    merged: dict[str, dict] = {}
    for node in local.get("items", []) + remote.get("items", []):
        if node.get("updated_at", 0) <= cleared:
            continue
        previous = merged.get(node["id"])
        if previous is None:
            merged[node["id"]] = copy.deepcopy(node)
            continue
        winner = max((previous, node),
                     key=lambda x: (x.get("updated_at", 0), x.get("deleted_at", 0)))
        item = copy.deepcopy(winner)
        item["created_at"] = min(previous["created_at"], node["created_at"])
        item["source_runs"] = list(dict.fromkeys(
            previous.get("source_runs", []) + node.get("source_runs", [])
        ))[-8:]
        item["entities"] = list(dict.fromkeys(
            previous.get("entities", []) + node.get("entities", [])
        ))[:8]
        links = previous.get("links", []) + node.get("links", [])
        selected = {}
        for relation in links:
            key = (relation["target_id"], relation["type"])
            status = relation.get("status", "proposed")
            if status not in ("proposed", "confirmed", "rejected"):
                raise ValueError("Unsupported memory graph status")
            previous_link = selected.get(key)
            rank = (
                0 if status == "proposed" else 1,
                relation.get("updated_at", 0),
                1 if status == "rejected" else 0,
            )
            old_rank = (-1, -1, -1) if previous_link is None else (
                0 if previous_link.get("status", "proposed") == "proposed" else 1,
                previous_link.get("updated_at", 0),
                1 if previous_link.get("status") == "rejected" else 0,
            )
            if rank > old_rank:
                selected[key] = relation
        item["links"] = sorted(selected.values(),
            key=lambda relation: (
                relation.get("status", "proposed") == "proposed",
                -relation.get("updated_at", 0), relation["target_id"],
            ))[:12]
        item["invalidated_at"] = max(previous.get("invalidated_at", 0), node.get("invalidated_at", 0))
        item["deleted_at"] = max(previous.get("deleted_at", 0), node.get("deleted_at", 0))
        item["occurrences"] = max(previous.get("occurrences", 1), node.get("occurrences", 1))
        merged[item["id"]] = item
    if len(merged) > MAX_RETAINED:
        raise ValueError("Retained memory capacity exceeded; refusing to discard records")
    return {"schema": 1, "cleared_at": cleared, "items": sorted(
        merged.values(), key=lambda row: (-row.get("updated_at", 0), row["id"]),
    )}


def merge(base: dict | None, local: dict, remote: dict | None) -> dict:
    b, r = base or empty(), remote or empty()
    lmem, rmem, bmem = local["memory_md"], r["memory_md"], b["memory_md"]
    if lmem == rmem:
        memory = lmem
    elif lmem == bmem:
        memory = rmem
    elif rmem == bmem:
        memory = lmem
    else:
        raise ValueError("MEMORY_CONFLICT")
    return validate({
        "schema": 3, "memory_md": memory,
        "knowledge": {"schema": 1, "documents": merge_knowledge(
            b["knowledge"]["documents"], local["knowledge"]["documents"], r["knowledge"]["documents"])},
        "experience": merge_experience(local["experience"], r["experience"]),
        "retained": merge_retained(local["retained"], r["retained"]),
    })


def write_local(directory: Path, payload: dict):
    atomic_write(directory / "MEMORY.md", payload["memory_md"].encode("utf-8"))
    atomic_write(directory / "knowledge.json", stable_json(payload["knowledge"]).encode("utf-8"))
    atomic_write(directory / "task_experiences.json", stable_json(payload["experience"]).encode("utf-8"))
    atomic_write(directory / "retained_memories.json", stable_json(payload["retained"]).encode("utf-8"))
    atomic_write(directory / ".eta-sync-base.json", stable_json(payload).encode("utf-8"))


def sync(directory: Path, resolve_memory_conflict: bool = False):
    endpoint = os.environ.get("ETA_WEBDAV_URL", "")
    username = os.environ.get("ETA_WEBDAV_USER", "")
    password = os.environ.get("ETA_WEBDAV_PASSWORD", "")
    passphrase = os.environ.get("ETA_SYNC_PASSPHRASE", "")
    if not passphrase:
        raise RuntimeError("ETA_SYNC_PASSPHRASE is required")
    remote = WebDav(endpoint, username, password)
    remote.ensure_folder()
    saved_base = read_json(directory / ".eta-sync-base.json", None)
    base = validate(saved_base) if saved_base is not None else None
    for attempt in range(3):
        local = read_local(directory, base)
        encrypted, etag = remote.read()
        if encrypted is None and base is not None:
            raise RuntimeError("Remote state disappeared; refusing to recreate without review")
        remote_data = validate(json.loads(decrypt(encrypted, passphrase))) if encrypted is not None else None
        try:
            combined = merge(base, local, remote_data)
        except ValueError as e:
            if str(e) == "MEMORY_CONFLICT" and remote_data is not None:
                conflict_file = directory / "remote-memory-conflict.md"
                remote_bytes = remote_data["memory_md"].encode("utf-8")
                previously_reviewed = (
                    conflict_file.exists() and conflict_file.read_bytes() == remote_bytes
                )
                if resolve_memory_conflict and previously_reviewed:
                    virtual_remote = copy.deepcopy(remote_data)
                    virtual_remote["memory_md"] = (base or empty())["memory_md"]
                    combined = merge(base, local, virtual_remote)
                else:
                    atomic_write(conflict_file, remote_bytes)
                    raise RuntimeError(
                        "MEMORY_CONFLICT: remote-memory-conflict.md saved. "
                        "Merge with local MEMORY.md and save it. Run sync with "
                        "--resolve-memory-conflict only after reviewing both versions."
                    ) from e
            else:
                raise
        if read_local(directory, base) != local:
            continue
        if encrypted is None or combined != remote_data:
            if not remote.write(encrypt(stable_json(combined).encode("utf-8"), passphrase), etag):
                continue
        if read_local(directory, base) != local:
            raise RuntimeError(
                "Remote sync was committed, but local files changed during sync; "
                "local edits are untouched. Please run sync again."
            )
        write_local(directory, combined)
        print(f"Sync complete: {len(combined['knowledge']['documents'])} knowledge records, "
              f"{len(combined['experience']['items'])} task experiences")
        return
    raise RuntimeError("Concurrent sync changes; please retry")


def add_document(directory: Path, args):
    base = read_json(directory / ".eta-sync-base.json", None)
    local = read_local(directory, base)
    content = Path(args.file).read_text(encoding="utf-8")
    if not content.strip():
        raise ValueError("Knowledge file is empty")
    documents = local["knowledge"]["documents"]
    if len(documents) >= MAX_NOTES:
        raise ValueError("Knowledge capacity reached")
    now = int(time.time() * 1000)
    uid = str(uuid.uuid4())
    item = {
        "id": uid, "title": args.title, "content": content, "kind": args.kind,
        "source": args.source, "tags": args.tag,
        "created_at": now, "updated_at": now, "deleted": False,
        "revision": hashlib.sha256((uid + content + str(now)).encode()).hexdigest(),
    }
    documents.append(item)
    atomic_write(directory / "knowledge.json", stable_json(local["knowledge"]).encode("utf-8"))
    print(f"Added knowledge ID: {uid}")


def main():
    parser = argparse.ArgumentParser(description="Eta encrypted WebDAV sync companion")
    commands = parser.add_subparsers(dest="command", required=True)
    sync_parser = commands.add_parser("sync", help="Safely reconcile with WebDAV")
    sync_parser.add_argument("--dir", type=Path, required=True)
    sync_parser.add_argument(
        "--resolve-memory-conflict",
        action="store_true",
        help="After reviewing the saved remote conflict file, use local MEMORY.md as the resolved version",
    )
    list_parser = commands.add_parser("list", help="Show local knowledge")
    list_parser.add_argument("--dir", type=Path, required=True)
    add_parser = commands.add_parser("add", help="Add a Markdown knowledge document")
    add_parser.add_argument("--dir", type=Path, required=True)
    add_parser.add_argument("--title", required=True)
    add_parser.add_argument("--file", required=True)
    add_parser.add_argument("--kind", choices=("fact", "procedure", "reference"), default="reference")
    add_parser.add_argument("--source", default="")
    add_parser.add_argument("--tag", action="append", default=[])
    args = parser.parse_args()
    args.dir.mkdir(parents=True, exist_ok=True)
    if args.command == "sync":
        sync(args.dir, resolve_memory_conflict=args.resolve_memory_conflict)
    elif args.command == "add":
        add_document(args.dir, args)
    elif args.command == "list":
        current = read_local(args.dir, read_json(args.dir / ".eta-sync-base.json", None))
        for note in current["knowledge"]["documents"]:
            if not note.get("deleted"):
                print(f"{note['id']}  {note['title']}  [{note['kind']}]")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, urllib.error.URLError) as exc:
        print(f"Eta sync error: {exc}", file=sys.stderr)
        sys.exit(1)
