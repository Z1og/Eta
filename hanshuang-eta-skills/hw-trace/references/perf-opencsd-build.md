# perf + OpenCSD 构建与排错（CoreSight 支持）

目标：在 Eta 的 Alpine 环境里得到一个能解码 ETE/ETM 流的 `perf`。
发行版自带 perf 一般**未链接 OpenCSD**，`perf list` 里没有 `cs_etm`。

## 依赖

```bash
apk add build-base cmake git python3 zlib-dev zstd-dev openssl-dev \
  flex bison elfutils-dev binutils-dev slang-dev glib-dev
```

## OpenCSD

```bash
git clone --depth 1 https://github.com/Linaro/OpenCSD.git
cd OpenCSD/decoder/build/sw
./make_bins.sh
make DESTDIR=/ install
ldconfig || true
```

常见问题：
- 找不到 `libopencsd`：确认 `make install` 后 `/usr/lib` 有 `libopencsd*`，必要时
  `export LD_LIBRARY_PATH=/usr/lib`
- 只想验证解码库：用仓库自带 `simple-pkt-tracer` 对一段 raw trace 跑一遍

## perf

```bash
git clone --depth 1 --branch v6.6 https://git.kernel.org/pub/scm/linux/kernel/git/stable/linux.git
cd linux/tools/perf
make CORESIGHT=1 VF=1 -j$(nproc)   # VF=1 打印缺失依赖
make install
```

常见问题：
- `CORESIGHT=1` 是硬开关：不加不会编 cs_etm 支持，`perf list` 不出现 `cs_etm`
- 报缺 `libtraceevent`/`libapi`：内核源码 `tools/lib` 下已自带，先
  `make -C ../lib/api`、`make -C ../lib/traceevent` 再重试
- 版本选择：perf 版本无需与手机内核完全一致，v6.x 均可解码 ETE/ETMv4

## 采集与解码速查

```bash
perf list | grep -E 'cs_etm|arm_spe'          # 部署成功判定
perf record -e cs_etm/@tmc_etr0/u -p $PID -- sleep 10
perf script --itrace=i1000 --ns -F pid,tid,cpu,addr,ipc,symbol,dso > trace.txt
```

- sink 名以 `ls /sys/bus/coresight/devices/` 为准（`tmc_etr0` / `etb0`）
- `--itrace=i1` 为全量合成（数据量巨大），分析用先从 `i1000` 起
- 解码报 "no sink"：内核未注册 sink 或权限不足，回 SKILL.md 第一节重新探测

## 输出字段分析

| 字段 | 用途 |
|---|---|
| `addr` | 指令地址（对回 ELF 偏移做静态联编） |
| `ipc` | 高频低 IPC = 内存瓶颈段（配合 SPE 交叉验证） |
| `dso` / `symbol` | 定位所属模块/符号（PIE/SO 动态分析关键） |
