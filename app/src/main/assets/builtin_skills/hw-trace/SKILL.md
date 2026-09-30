---
name: hw-trace
description: |
  硬件级追踪与硬件断点：基于 Arm CoreSight（ETE/TRBE 指令追踪、SPE 统计剖析、BRBE 分支记录）
  与硬件断点/观察点（DBGBCR/DBGWCR）做执行流采集、反调试绕过与性能剖析。
  覆盖 VMP 壳还原、OLLVM 控制流平坦化恢复、PIE/共享库 ELF 动态分析、崩溃定位、
  游戏帧率/启动热点。当任务提到 ETE、TRBE、SPE、BRBE、CoreSight、ETM、硬件断点、
  硬件观察点、指令追踪、VMP 还原、脱 VMP、OLLVM 还原、平坦化、反调试绕过
  （软件断点被检测场景）时使用。
triggers: ETE, TRBE, SPE, BRBE, CoreSight, ETM, 硬件断点, 硬件观察点, 指令追踪, 执行流追踪, VMP还原, 脱VMP, OLLVM还原, 控制流平坦化, 平坦化还原, 反调试绕过, 硬件追踪
---

# 硬件追踪与硬件断点（ETE / SPE / BRBE）

定位：补齐纯软件层（Frida inline hook、ptrace 软件断点）之外的**硬件执行侧观测**能力。
硬件特性不注入代码、不改指令——VMP / OLLVM / 反调试扫描代码段时检测不到。

优先级口诀：**先探测能力 → 有 ETE 走指令追踪，只有 SPE 走延迟剖析 → 都没有再降级软件层**。

## 一、能力探测（每台设备先跑这一节）

```bash
# 1) CoreSight 设备树（ete0/tmc_etr0/etm0/funnel/replicator…）
ls /sys/bus/coresight/devices/ 2>/dev/null

# 2) perf 事件源（cs_etm=ETE/ETM 追踪，arm_spe_*=SPE）
ls /sys/bus/event_source/devices/ 2>/dev/null | grep -E 'cs_etm|ete|arm_spe'

# 3) 内核是否启用 CoreSight（CONFIG_IKCONFIG 需开启才有 /proc/config.gz）
zcat /proc/config.gz 2>/dev/null | grep -E 'CONFIG_CORESIGHT|CONFIG_ETM|CONFIG_TRBE|CONFIG_ARM64_SPE|CONFIG_BRBE'
dmesg | grep -iE 'coresight|ete[0-9]|trbe|tmc_etr|arm_spe'

# 4) SoC 代际判断：ETE 需 Armv9（骁龙 8Gen2/8Gen3、天玑 9200/9300 等）；
#    SPE 需 Armv8.2+（骁龙 855 起）；老旗舰是 ETMv4（走 etm4x + tmc_etr 同一流程）
grep -m1 'CPU part' /proc/cpuinfo
```

判定表：
- `ete0` + `cs_etm` 事件源 → **走第三节 ETE 追踪**
- 只有 `arm_spe_*` → **走第四节 SPE 剖析**
- 都没有 → **第九节降级**（ETMv4 / 软件层），并在交付物里注明设备限制

## 二、工具部署（Alpine）

perf 需链接 OpenCSD 才能解码 ETE/ETM 流；发行版自带的 perf 通常没编 CoreSight。

```bash
# Alpine 内（Eta root shell / chroot）
apk add build-base cmake git python3 zlib-dev zstd-dev openssl-dev flex bison \
  elfutils-dev binutils-dev slang-dev

# 1) OpenCSD（ETE/ETM 解码库）
git clone --depth 1 https://github.com/Linaro/OpenCSD.git
cd OpenCSD/decoder/build/sw && ./make_bins.sh && make DESTDIR=/ install && ldconfig

# 2) perf（带 CoreSight）
git clone --depth 1 --branch v6.6 https://git.kernel.org/pub/scm/linux/kernel/git/stable/linux.git
cd linux/tools/perf && make CORESIGHT=1 VF=1 -j$(nproc) && make install
perf list | grep -E 'cs_etm|arm_spe'   # 出现即部署成功
```

详细构建排错见 `references/perf-opencsd-build.md`。

## 三、ETE / TRBE 指令追踪（主力：VMP / OLLVM / PIE-SO）

```bash
PID=$(pidof com.target)

# 采集：sink 用第一节看到的 tmc_etr0（老机 etb0）；只跟目标进程用户态
perf record -e cs_etm/@tmc_etr0/u -p $PID -- sleep 10

# 解码为指令流（i1000 = 每 1000 条指令合成一条，全量用 i1，数据量巨大）
perf script --itrace=i1000 --ns -F pid,tid,cpu,addr,ipc,symbol,dso > trace.txt

# 只看某函数片段：先 addr2line / nm 定位函数范围再 awk 过滤 addr
```

要点：
- `-u` 限用户态，内核态 trace 需去掉并处理权限
- trace 秒级可达 GB：先 `sleep` 短窗口 + 触发目标动作，再离线分析
- `ipc` 字段可快速区分热循环与内存瓶颈段

## 四、SPE 统计剖析（延迟热点，游戏/启动）

```bash
perf record -e arm_spe_0// -p $(pidof com.target) -- sleep 5
perf report --itrace=il        # 按指令/延迟聚合
```

适合：帧率瓶颈（内存延迟 vs 分支 miss）、启动耗时热点、il2cpp 帧逻辑开销。
SPE 不需要解码链路，是 ETE 之外性价比最高的一项。

## 五、BRBE 分支记录（前瞻，依赖厂商内核）

内核（6.x 起，`CONFIG_ARM64_BRBE`）把 BRBE 暴露成 perf branch stack 后：

```bash
perf record -j any -p $(pidof com.target) -- sleep 5
perf report --branch-history
```

类 x86 LBR：直接拿最近 N 层分支历史，用于 ROP/控制流分析与绕过检测取证。
当前多数商用机内核未开，探测不到就跳过本节。

## 六、硬件断点 / 观察点（反调试绕过主力）

软件断点要改指令（`BRK` 补丁），反调试扫代码段即穿帮；硬件断点在 DBGBCR/DBGWCR，代码零修改。

```bash
# GDB（hbreak=硬件执行断点；watch=硬件数据观察点）
gdb -p $(pidof com.target)
(gdb) hbreak *0x7abc1234        # 执行断点，不碰指令
(gdb) watch *(int*)0x7fff5678   # 数据写观察点（监控解密后缓冲区/密钥落地）

# perf 事件式观察点（可脚本化，不改代码）
perf record -e mem:0x7abc1234:rw -p $(pidof com.target) -- sleep 5
perf script
```

自研注入器/loader 用 `ptrace(PTRACE_SETREGSET, ..., NT_HWBREAKPOINT/NT_HWWATCHPOINT, ...)` 设置，见 `references/hw-breakpoint-ptrace.md`。

## 七、应用方法论

### VMP 壳还原（ETE 主战场）
1. ETE trace 抓受保护函数完整执行流（壳检测不到，无注入）
2. 离线统计 PC 回落频率 → 定位 **dispatch 循环**与 handler 入口
3. 对每个 handler 反汇编 → 建 **opcode → 语义映射表**
4. 用 trace 的真实路径做 ground truth 验证语义，重建 IR/伪码
5. 交付：语义表 + 去虚拟化伪码 + 关键 trace 片段

### OLLVM 平坦化恢复
1. trace 拿到**真实执行路径**（真实分支才会经过的边）
2. 用 trace 边集重建真实 CFG（比纯静态 deflat/d810 可靠：分发器假边自然消失）
3. 交给 ida-reverse / ghidra-reverse 技能做标注与重命名

### PIE / 共享库 ELF
PIE 与 dlopen 的 SO 地址运行时才定；先 ETE trace 拿到加载后真实执行地址，
再 `perf script` 的 dso/addr 对回 ELF 偏移做静态联编。

### 崩溃定位
`/data/tombstones/` 拿 backtrace → 结合崩溃前 ETE 窗口（TRBE 环形缓冲，
内核 panic handler 可 dump）回放最后 N 条指令；无 ETE 时降级 simpleperf + logcat。

### 游戏开发
SPE 延迟热点定位帧率瓶颈；ETE 追踪 il2cpp 关键帧逻辑；反作弊对抗场景优先
硬件断点（代码段零修改，检测面最小）。

## 八、设备与内核要求

| 特性 | 需求 | 代表机型 |
|---|---|---|
| ETE + TRBE | Armv9 + 内核 CONFIG_CORESIGHT(_TRBE) | 骁龙 8Gen2/8Gen3、天玑 9200/9300 |
| ETMv4（降级） | Armv8 + CONFIG_CORESIGHT | 骁龙 865/888 等（走同一 perf 流程） |
| SPE | Armv8.2+ | 骁龙 855 起 |
| BRBE | Armv9.2 + 新内核 | 多数商用机未开 |

## 九、局限与降级

- **厂商内核常关 CoreSight**：探测不到即走降级，交付物注明"设备限制"
- 降级顺序：ETMv4 → simpleperf（无解码）→ Frida Stalker（会被检测，最后手段）
- trace 数据量大：短窗口采集 + `--itrace` 采样，避免直接 i1 全量
- Eta 运行环境为 root + Alpine：perf/OpenCSD 源码构建可用，但**内核侧支持取决于设备**，无法软件弥补
