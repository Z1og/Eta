# 硬件断点 / 观察点：ptrace 与自研工具路径

原理：Arm64 调试架构提供断点寄存器（DBGBCR/DBGBVR，执行断点）与观察点寄存器
（DBGWCR/DBGWVR，数据访问）。两者都在**硬件层**生效，目标进程代码段零修改——
反调试扫 `BRK`/`0xCC` 补丁、CRC 自校验均检不出来。

## 快速路径：GDB

```bash
gdb -p $(pidof com.target)
(gdb) hbreak *0x7abc1234         # 硬件执行断点（最多 4~6 个，受芯片限制）
(gdb) watch *(int*)0x7fff5678    # 硬件数据观察点
(gdb) rwatch *(char*)0x7fff5678  # 读观察点
```

## 快速路径：perf 事件式观察点（可脚本化）

```bash
perf record -e mem:0x7abc1234:rw -p $(pidof com.target) -- sleep 5
perf script    # 每次命中给一条记录：addr/符号/时间戳
```

## 自研路径：ptrace NT_HWBREAKPOINT / NT_HWWATCHPOINT

注入器 / loader / 崩溃抓取器里直接设置，绕开 GDB：

```c
struct user_hwdebug_state {
    unsigned int dbg_flags;      /* MTE/标签配置，一般 0 */
    struct { unsigned long addr; unsigned int ctrl; } dbg_regs[16];
};
// 执行断点：PTRACE_SETREGSET(pid, NT_HWBREAKPOINT, iov)
// 数据观察点：PTRACE_SETREGSET(pid, NT_HWWATCHPOINT, iov)
// ctrl 编码（参考 ARM ARM）：len=字段[4:5]、type=字段[3:2]（10=加载、01=存储、11=读写）、
//                          enable=bit0、bt 字段选地址/上下文匹配
```

注意事项：
- 数量上限读 `DBGDIDR`（一般 4 断点 + 4~2 观察点；大核小核可能不同）
- PTRACE_ATTACH 本身可触发反 ptrace 检测（`TracerPid`）；更强的组合是
  **内核模块/kprobes 关闭检测 + 硬件断点**，或复用既有 root 会话不重 attach
- 命中后单步恢复现场：命中即触发异常，需处理单步越过（设置 `ctrl` 的单步位或临时禁用再恢复）

## 与 ETE 的配合

观察点定位到敏感数据落地（如解密后 key / dump 后明文），ETE 同窗口追踪
消费该数据的代码路径，两者交叉能快速收敛到关键算法。
