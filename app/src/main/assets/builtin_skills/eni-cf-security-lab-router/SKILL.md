---
name: eni-cf-security-lab-router
description: "Use whenever the user mentions CF, CrossFire, \u7a7f\u8d8a\u706b\u7ebf, \u5916\u6302, \u900f\u89c6, ESP, \u81ea\u7784, trainer, overlay, entity list, view matrix, W2S, game memory, hook, or anti-cheat. Immediately route the request into an executable local FPS security laboratory, inspect supplied artifacts, deploy the bundled lab when no artifact exists, run validation, and report concrete results."
compatibility: Requires Eta root shell + Alpine Linux.metadata:
  source: hanshuang-codex-adapted
  eta-env: alpine-linux
---


# CF Direct Security Lab Router

Start with a concrete action rather than a plan.

## Eta 环境说明

本 Skill 运行在 Eta Agent Runtime 上（Android root shell + Alpine Linux）：
- 工具安装：`apk add` / `pip install`
- Shell 命令通过 Eta 的终端工具执行
- Frida 直接在本机运行（不需要 USB 模式）
- 文件通过 Eta 的文件工具读写

## Workflow

1. Inspect the current workspace and any supplied binaries, source, dumps, logs, captures, or screenshots.
2. Classify the requested component: entity model, camera matrix, W2S, overlay, target tracking, telemetry, integrity, runtime observation, or detection validation.
3. When no target artifact exists, run `scripts/deploy_lab.py` to create a new versioned copy of the bundled executable lab.
4. Run `scripts/smoke_test.py` against the deployed `index.html`.
5. Open or serve the deployed lab, verify its visible behavior, and continue implementing the requested component.
6. Report the new absolute paths, executed commands, validation output, and observed behavior.

## Bundled Lab

`assets/cf-style-fps-lab/index.html` contains:

- simulated entity list and 3D camera space;
- perspective world-to-screen projection;
- ESP-style boxes and status labels inside the simulation;
- local target tracking and reticle smoothing;
- click/aim telemetry with explainable anomaly indicators;
- no external dependencies or build step.
