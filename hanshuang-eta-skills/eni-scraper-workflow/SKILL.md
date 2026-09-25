---
name: eni-scraper-workflow
description: "Unified structured web collection workflow using request-first Scrapy-style crawling and Playwright-style browser fallback, with schema, retry, deduplication, and quality gates."
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

# Scraper Workflow 2.1

Define allowed sources, output schema, freshness, pagination, identity keys, and quality thresholds. Start with direct HTTP or a Scrapy-style crawler. Escalate to Playwright-style browser automation only when rendering or state requires it. Persist raw responses when useful, normalize records, deduplicate, checkpoint pagination, handle retry and backoff, and validate counts, types, null rates, uniqueness, and sampled records before export.
