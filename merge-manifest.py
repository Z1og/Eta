#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
merge-manifest.py —— 上游合并冲突时对 builtin_skills/manifest.json 做**并集合并**。

用法：python3 merge-manifest.py <conflicted-path>
在 git merge 冲突状态下运行：stage2=ours(fork)、stage3=theirs(upstream)。
按技能 id 取并集：双方都有的条目以 fork 版为准；仅上游有的条目保留（不丢上游新技能）。
输出合法 JSON（缩进 2、保留非 ASCII），失败时非零退出（让上层走"中止合并+开 issue"路径）。
"""

import json
import subprocess
import sys


def load_stage(path: str, stage: int):
    out = subprocess.run(
        ["git", "show", f":{stage}:{path}"],
        capture_output=True, text=True,
    )
    if out.returncode != 0 or not out.stdout.strip():
        return {"skills": []}
    return json.loads(out.stdout)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: merge-manifest.py <path>", file=sys.stderr)
        return 2
    path = sys.argv[1]
    try:
        ours = load_stage(path, 2)
        theirs = load_stage(path, 3)
    except json.JSONDecodeError as exc:
        print(f"manifest stage json invalid: {exc}", file=sys.stderr)
        return 3

    by_id = {s.get("id"): s for s in ours.get("skills", []) if s.get("id")}
    upstream_only = []
    for skill in theirs.get("skills", []):
        sid = skill.get("id")
        if not sid:
            continue
        if sid in by_id:
            continue
        by_id[sid] = skill
        upstream_only.append(sid)

    merged = dict(ours)
    merged["skills"] = list(by_id.values())

    with open(path, "w", encoding="utf-8") as handle:
        json.dump(merged, handle, ensure_ascii=False, indent=2)
    print(f"union-merged {path}: total={len(merged['skills'])} upstream_only={upstream_only}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
