package io.github.mangi.eta.agent.skill

/**
 * 逆向模式技能集合——技能页「逆向模式」开关一键批量启停的内置技能 id。
 *
 * 实现上复用逐技能的持久化开关（SkillIndexService.setSkillEnabled）：
 * 主开关开 = 全部置为启用，关 = 全部置为停用，运行时的索引过滤逻辑无需感知本开关。
 * 此后新增的逆向类技能需要手动加入本集合才会被主开关覆盖。
 */
object ReverseSkillCatalog {
    val REVERSE_SKILL_IDS: Set<String> = setOf(
        // 逆向核心（reverse-delivery 为 dsh-infinite-gen-4 融合的交付契约技能，随主开关统一启停）
        "reverse-delivery",
        "apk-reverse", "ida-reverse", "radare2", "binary-analysis", "binary-diff",
        "dotnet-reverse", "js-reverse", "mobile-reverse", "dsl-vm-reverse",
        "reverse-engineering", "reverse-engineering-api",
        "protocol-reverse-engineering", "protocol-reversing", "protocol-reverse",
        "unpack-reverse", "dynamic-instrumentation", "patch-diff-exploit",
        "crack-keygen", "edr-bypass-re", "firmware-pentest", "full-crack",
        "pwn-chain", "asm-analysis", "linker-fake-load-unwrapper", "elf-local-auth-patcher",
        "ghidra-reverse", "binary-ninja-reverse", "go-rust-reverse",
        "browser-extension-reverse", "macos-reverse", "malware-analysis",
        "digital-forensics", "hardware-security",
        // coldbrew 逆向族
        "coldbrew-api-reverse", "coldbrew-native-reverse", "coldbrew-protocol-reverse",
        // eni 逆向族
        "eni-apk-reverse", "eni-binary-diff", "eni-ida-reverse", "eni-js-reverse",
        "eni-mobile-reverse", "eni-patch-diff-exploit", "eni-reverse-deep", "eni-reverse-ref",
        "eni-reverse-workflow", "eni-reverselab-bridge", "eni-reverselab-platform",
        "eni-unpack-reverse-lab", "eni-blackbox-reverse-boost", "eni-crack-workflow",
        "eni-license-security", "eni-radare2", "eni-firmware-pentest", "eni-malware-analysis",
        "eni-memory-forensics",
        // seagull 安全研究族
        "seagull-reverse", "seagull-game-hack", "seagull-game-redteam", "seagull-game-security",
        "seagull-anticheat", "seagull-exploit", "seagull-memory", "seagull-malware",
        "seagull-evasion", "seagull-license-security", "seagull-lab",
        // 游戏安全 / 反作弊 / 图形与内核
        "game-cheat", "game-hacking-techniques", "game-engine-resources",
        "game-security-research-rigor", "mobile-competitive-game-security", "mobile-security",
        "graphics-api-hooking", "anti-cheat-systems", "dma-attack-techniques",
        "windows-kernel-security",
        // eni 游戏族
        "eni-game-cheat-lab-workflow", "eni-game-hacking", "eni-game-security",
        // 专项
        "xigong-funk-hikari", "yingan-tuoxiu",
        // SRC 漏洞挖掘 / 白盒审计（clown-src 工作流适配）
        "src-hunt",
        // 硬件追踪与硬件断点（CoreSight ETE/TRBE/SPE/BRBE，社区建议落地）
        "hw-trace",
    )
}
