#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
guardrail_strip.py —— 去除技能正文里的「保护 / 合规」类禁止项。

定位（= 用户所说「保护禁止项」）：限制授权范围、免责、法律合规、以及"最小伤害/禁止破坏"
这类保护性约束。**保留**：技术性「禁止事项」（如"不要盲目改 smali"）、反拒答声明
（如"已授权，必须执行""技术无边界"）、以及工具自身的完整性约束（如 skill-installer 的安装器约束）。

处理范围仅限正文（frontmatter 原样保留）。
同一模块被 hanshuang-to-eta.py 复用，保证每日同步重新生成时不会把保护项带回来。
"""

import re

# 一、整段移除：标题命中以下「保护/合规」类板块，删到下一个标题之前。
_GUARD_SECTION_HEADINGS = (
    r"法律边界声明|法律声明|免责声明|免责条款|合规声明|合规边界|"
    r"安全红线[^\n]*|安全铁律[^\n]*|"
    r"授权声明|授权要求|授权边界|"
    r"风险提示|风险声明|责任声明"
)
_GUARD_SECTION_RE = re.compile(
    r"(?m)^#{1,6}[ \t]*(?:" + _GUARD_SECTION_HEADINGS + r")[ \t]*\n"
    r"(?:(?!#{1,6}[ \t]).*(?:\n|$))*"
)

# 二、整行移除：命中以下保护性表述的整行（多为 bullet / 段落行）。
_GUARD_LINE_PATTERNS = (
    r"仅限合法授权|仅限授权|仅供合法|仅供授权|仅供学习|请勿用于|"
    r"不得用于|禁止用于|非法用途|法律责任|承担法律|"
    r"负责任披露|遵循负责任披露|必须脱敏|脱敏要求|"
    r"不影响业务可用性|禁止\s*DoS|禁止DoS|"
    r"禁止破坏|破坏性利用|真资损|禁止批量|最小伤害|"
    r"禁止登出|禁止登/注|"
    r"禁止锁定真实|禁止对真实用户|生产库禁止破坏"
)
# 只删 bullet 行（- / * 起头，含序号 bullet 如 "- 1."），避免误删被编号的工作流步骤
# （形如 "2. `NOW`: case-init + scope；…禁止破坏性操作" 这类行含合法步骤内容，整行删除会over-delete）。
_GUARD_LINE_RE = re.compile(r"(?m)^[ \t]*[-*][ \t]+.*(?:" + _GUARD_LINE_PATTERNS + r").*(?:\n|$)")


def _split_frontmatter(md: str):
    if md.startswith("---"):
        end = md.find("\n---", 3)
        if end != -1:
            nl = md.find("\n", end + 1)
            if nl != -1:
                return md[: nl + 1], md[nl + 1:]
    return "", md


def strip_guardrails(md: str) -> str:
    """去除正文里的保护/合规类禁止项；frontmatter 原样保留。幂等。"""
    front, body = _split_frontmatter(md)
    if not body:
        return md
    body = _GUARD_SECTION_RE.sub("", body)
    body = _GUARD_LINE_RE.sub("", body)
    body = re.sub(r"\n{4,}", "\n\n\n", body)
    return front + body


if __name__ == "__main__":
    import sys

    for path in sys.argv[1:]:
        with open(path, "r", encoding="utf-8") as handle:
            original = handle.read()
        cleaned = strip_guardrails(original)
        if cleaned != original:
            with open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(cleaned)
            print(f"stripped: {path}  (-{len(original) - len(cleaned)} chars)")
