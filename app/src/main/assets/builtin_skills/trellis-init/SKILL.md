---

name: trellis-init
description: 为项目初始化 Trellis 规范目录（.trellis/spec、tasks、workspace）并扫描代码起草规范初稿。当用户要为项目建立编码规范或项目记忆时使用。
triggers: 初始化trellis, 初始化 trellis, trellis初始化, 建立项目规范, 项目规范初始化
---

# Trellis 项目初始化

目标：在项目根目录建立 Trellis 约定结构，让后续每次编码自动遵守项目规范。

## 步骤

1. 确定项目根目录：用户未指明时，从当前对话提到的路径向上找包含构建文件的目录
   （build.gradle.kts、package.json、pyproject.toml、go.mod、Cargo.toml、pom.xml 等）；
   仍不确定就简短问用户要根目录绝对路径，不要猜。
2. 检查 `<root>/.trellis/` 是否已存在：已存在则读取现有 spec 概况并告知用户，
   询问是否重新起草，不要直接覆盖。
3. 创建目录结构：
   - `.trellis/spec/` — 编码规范
   - `.trellis/tasks/` — 任务记录
   - `.trellis/workspace/` — 会话日志
4. 扫描项目（README、构建脚本、依赖清单、目录树、主要源码抽样）起草以下 spec 文件。
   每个文件以 `# 标题` 开头，正文用短句列约定；没有代码依据的项写 `（待定）`，
   并在文件结尾列出待用户确认的问题：
   - `project.md` — 项目是什么、技术栈、构建/运行/测试命令
   - `architecture.md` — 模块划分、关键目录、数据流
   - `coding-style.md` — 命名、格式、错误处理、日志等代码中实际可见的风格
   - `workflow.md` — 分支/提交/验证习惯（可从 git log 归纳）
5. 完成后汇报：创建了哪些文件、每个文件的核心结论、哪些项需要用户确认。
   提醒用户：之后说「更新规范」可沉淀新约定，说「按规范检查」可对照检查代码。

## 约束

- 只读扫描，不修改业务代码；除 `.trellis/` 目录外不创建任何文件。
- spec 内容必须有代码依据，禁止臆测；每个文件控制在 60 行以内。
