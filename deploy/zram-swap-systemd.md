# zram 压缩 swap 的**成对持久化**（2026-09-26 第二轮：已实施并验证）

> **状态：已落地、已启用、已验证（含重启验证）。**
> 上一版本文档写于「未实施、需单独批准」阶段；本轮按方案 B（自建 systemd 单元，不装包、不联网、
> 不写额外软件包到只剩几 GB 的根盘）实施，并按任务要求做了 **zram 与 `vm.swappiness` 成对持久化**。

---

## 0. 一句话结论

| 项 | 结果 |
|---|---|
| 持久化机制 | **方案 B：自建 systemd 单元** `/etc/systemd/system/zram-swap.service`（`systemctl is-enabled` = enabled） |
| 成对性 | zram 设备与 `vm.swappiness=150` **在同一个执行序列里、顺序固定为「先 zram 再 swappiness」**，任一步失败 ⇒ 单元判失败 |
| 兜底 | `/etc/sysctl.d/99-zram-swap.conf`（开机早期就把 swappiness 设成 150）+ `/etc/modules-load.d/zram.conf`（开机加载 zram 模块） |
| 断言 | `ExecStartPost=/usr/local/sbin/zram-swap-verify.sh` 事后逐项断言（算法/mem_limit/容量/优先级/swappiness），不符即失败 |
| 参数 | `2G` / `lz4` / `mem_limit=1G` / `prio=100` / `swappiness=150` —— 全部维持上一轮实测选定值 |
| 回滚 | `systemctl disable --now zram-swap.service && rm -f /etc/sysctl.d/99-zram-swap.conf /etc/modules-load.d/zram.conf && sysctl -w vm.swappiness=60` |

---

## 1. 落地的四个文件（全部是本轮新增，不改动任何既有系统文件）

| 安装路径 | 来源（仓库留档） | 作用 |
|---|---|---|
| `/etc/systemd/system/zram-swap.service` | `deploy/zram-swap.service` | 单元本体：`ExecStart` 建 zram、`ExecStartPost` 断言、`ExecStop` 带安全检查地拆除 |
| `/usr/local/sbin/zram-swap-up.sh` | `deploy/zram-swap-up.sh` | 建 zram → `mkswap`/`swapon` → **再**设 swappiness（幂等分支也会补设 swappiness） |
| `/usr/local/sbin/zram-swap-down.sh` | `deploy/zram-swap-down.sh` | 带 **swapoff 安全规则**的拆除/回滚 |
| `/usr/local/sbin/zram-swap-verify.sh` | `deploy/zram-swap-verify.sh` | 只读断言（可随时人工巡检） |
| `/etc/sysctl.d/99-zram-swap.conf` | `deploy/99-zram-swap.conf` | `vm.swappiness = 150`（开机早期兜底） |
| `/etc/modules-load.d/zram.conf` | `deploy/modules-load-zram.conf` | 开机加载 `zram` 模块 |

> 安装前已逐项核实：`/etc/fstab`、`/etc/systemd/system/`、`/etc/sysctl.d/`、`/etc/rc.local`、cron
> **均无任何 zram 痕迹**（`NO_ZRAM_TRACES`），即本轮的四个文件**全是新增**，没有覆盖任何既有配置；
> 唯一的「覆盖」是把上一轮留在 `/usr/local/sbin/` 的两个脚本更新为新版（旧版已备份到
> `/root/mem-round2-20260926/zram-swap-*.sh.prev-20260926`）。

---

## 2. 为什么是「成对」而不是两处各写一半

**只有 zram 而 `vm.swappiness=60` ⇒ 内核几乎不使用它，等于没上 zram。** 本轮的设计让两者不可能半套生效：

1. **同一执行序列、顺序固定**：`zram-swap-up.sh` 内部顺序是
   `modprobe zram → comp_algorithm → mem_limit → disksize → mkswap/swapon → sysctl vm.swappiness`。
   内核要求 `comp_algorithm`/`mem_limit` 必须在写 `disksize` **之前**设置（写 `disksize` 会初始化设备），
   而 swappiness 放在 `swapon` **之后** —— 这是"先 zram 再 swappiness"的字面落实。
2. **幂等分支也补设 swappiness**：旧版脚本「已是活动 swap 就直接 exit 0」，会在"zram 在、swappiness 回落 60"
   的半套状态下**静默跳过**。新版改为：幂等分支**仍会**设置并**回读确认** swappiness，不一致就非 0 退出。
3. **事后断言兜底**：`ExecStartPost` 的 `zram-swap-verify.sh` 逐项断言
   `swapon 含 /dev/zram0` / `comp_algorithm=lz4` / `mem_limit=1073741824` / `disksize=2147483648` /
   `优先级=100` / `swappiness=150`，任一不符 ⇒ 单元 `failed`（**绝不静默通过**）。
4. **sysctl.d 作为开机早期兜底**：即使单元因故没跑到最后一步，`vm.swappiness` 也不会回落到 60。
   （两处的分工是「兜底的值」+「带顺序与依赖的权威设置」，不是各写一半。）

### 2.1 开机顺序与依赖

```
systemd-modules-load.service   (按 /etc/modules-load.d/zram.conf 加载 zram)
        │  After=
        ▼
zram-swap.service              (ExecStart 建 zram → 设 swappiness；ExecStartPost 断言)
        │  Before=
        ▼
swap.target                    (依赖 swap 的单元，含 docker 容器，此后才看到 zram 就绪)

systemd-sysctl.service         (更早，按 /etc/sysctl.d/99-zram-swap.conf 先设 swappiness=150)
```

单元用 `DefaultDependencies=no` + `After=systemd-modules-load.service local-fs.target` + `Before=swap.target`，
与发行版 `systemd-zram-generator` 的官方单元同构。

**刻意不加 `Conflicts=/Before=shutdown.target`**：关机/重启**不需要**把 zram 里的页解压换回物理内存
（内核重启时直接丢弃 zram 设备）。不把单元纳入 shutdown 事务 ⇒ 关机路径更短，也不会因为
「内存贴水位 → swapoff 安全检查拒绝」把关机标记为失败。人工回滚用 `systemctl disable --now`，
那是一次普通 stop，不受此影响。

---

## 3. `swapoff` 安全规则（本轮写成**硬性守卫**，不只是注释）

`swapoff` 会把 zram 里的页**全部解压换回物理内存**，需要一次性容纳

```
orig_data_size = /sys/block/zram0/mm_stat 第 1 列（未压缩总字节数）
```

的匿名内存；zram 自身占用的 `mem_used_total`（第 3 列）虽会释放，但**按最坏情况不计入收益**。因此：

```
        允许 swapoff  ⇔      MemAvailable  ≥  orig_data_size  +  余量
```

- 余量默认 **256 MiB**，可用 `ZRAM_SWAPOFF_MARGIN`（支持 `K/M/G`）覆盖。
- `zram-swap-down.sh` 的三种模式：
  - **默认**：判定不通过 ⇒ **拒绝执行、退出码 3**（人工回滚用，安全优先）；
  - `--force`：跳过判定（自行承担卡死/OOM 风险）；
  - `--check`：只判定并打印，不改任何状态（退出码同默认模式）。
- 实测证据（本轮）：`ZRAM_SWAPOFF_MARGIN=100G zram-swap-down.sh --check` ⇒ 打印
  `MemAvailable=904MiB  orig_data_size=8MiB  余量=102400MiB  ⇒ 需要 ≥ 102408MiB` 并**退出码 3**；
  默认余量下同一命令判定通过（退出码 0）。
- `--shutdown` 模式保留在脚本里（判定不过 ⇒ 只告警、退出 0），供"不希望关机被标失败"的场景手工使用；
  **本轮的单元刻意没有使用它**（见 §2.1 不加 shutdown 依赖）。

---

## 4. 验证证据（本轮实跑）

### 4.1 单元生命周期（`start` → `stop` → `start`，含完整创建路径）

```
$ systemctl start zram-swap.service     # zram 已在用 ⇒ 走幂等分支
start exit=0 ; is-active=active ; is-enabled=enabled

$ systemctl stop zram-swap.service      # ExecStop = 带安全检查的 swapoff
stop exit=0 ; swapon 为空（已卸载）

$ systemctl start zram-swap.service     # 完整创建路径 + ExecStartPost 断言
start exit=0
     Process: ExecStart=/usr/local/sbin/zram-swap-up.sh (code=exited, status=0/SUCCESS)
     Process: ExecStartPost=/usr/local/sbin/zram-swap-verify.sh (code=exited, status=0/SUCCESS)
     Active: active (exited) ; Loaded: enabled
```

journal 里能看到**顺序**与**回读断言**：

```
[zram-swap-up] modprobe zram
[zram-swap-up] comp_algorithm <- lz4
[zram-swap-up] mem_limit <- 1G (限制 zram 占用的物理内存)
[zram-swap-up] mem_limit 回读(mm_stat 第4列) = 1073741824
[zram-swap-up] disksize <- 2G
[zram-swap-up] mkswap + swapon -p 100
[zram-swap-up] vm.swappiness <- 150
[zram-swap-up] vm.swappiness 回读 = 150 ✓
[zram-swap-verify] 判定：PASS（zram 设备 + vm.swappiness 成对生效）
```

### 4.2 三项要求的输出

```
$ zramctl
NAME       ALGORITHM DISKSIZE DATA COMPR TOTAL STREAMS MOUNTPOINT
/dev/zram0 lz4             2G   4K   64B   20K       8 [SWAP]

$ swapon -s
Filename                                Type            Size            Used            Priority
/dev/zram0                              partition       2097148         0               100

$ cat /proc/sys/vm/swappiness
150
```

### 4.3 一个被断言抓出来的真实事实（所以断言不是装饰）

`/sys/block/zram0/mem_limit` 的权限是 **`0200`（只写）**，root 也**读不了**（实测 `cat` ⇒ `Permission denied`）。
因此第一版 `zram-swap-verify.sh` 用 `cat mem_limit` 校验 **判 FAIL**（单元真的失败了）。
根因定位与修法：改从 `/sys/block/zram0/mm_stat` 的**第 4 列**读取，并用**变异验证**钉死这个对应关系：

```
before mm_stat:  8392704  377556  1200128 1073741824 172654592 ...
echo 512M > /sys/block/zram0/mem_limit
after  512M   :  8392704  377556  1200128  536870912 172654592 ...   ← 只有第 4 列变了
echo 1G   > /sys/block/zram0/mem_limit
restored 1G   :  8392704  377556  1200128 1073741824 172654592 ...
```

（`mm_stat` 列序见内核 `admin-guide/blockdev/zram.rst`：
`orig_data_size compr_data_size mem_used_total **mem_limit** mem_used_max ...`）

---

## 5. 回滚

**一条命令**（内存安全时）：

```bash
systemctl disable --now zram-swap.service \
  && rm -f /etc/sysctl.d/99-zram-swap.conf /etc/modules-load.d/zram.conf \
  && sysctl -w vm.swappiness=60
```

- `disable --now` 会触发 `ExecStop` = `zram-swap-down.sh`（**带 swapoff 安全检查**）：
  `MemAvailable < orig_data_size + 256MiB` 时它会**拒绝**并退出 3（此时不要强行回滚，等内存回落）。
- 只回滚运行期、保留持久化：`systemctl stop zram-swap.service`。
- 只回滚持久化、保留运行期：`systemctl disable zram-swap.service`（下次重启后回到 swap=0）。
- 恢复：`systemctl enable --now zram-swap.service`（幂等）。

**回滚的代价（同上一轮）**：`swapoff` 会把 zram 里的页换回物理内存 ⇒ 内存紧时回滚会长时间卡住甚至 OOM。
`zram-swap-down.sh` 会先打印 `mm_stat` 与判定式供判断。**内存紧张时不要回滚 zram。**

---

## 6. 仍然存在的已知边界（诚实登记，不在本轮解决）

1. **重启后的行为已实测**（见本轮报告「任务 1 · 重启验证」一节）——但**只做了一次**重启，样本量为 1。
2. **`memswap_limit` 仍未设**（上一轮遗留）：只设 `mem_limit` 时 Docker 默认允许容器使用等量 swap
   ⇒ 6 个受限容器的 `memory.swap.max` 合计远超 zram 的 2047MiB，理论上可把 zram 填满。
   zram 自身 `mem_limit=1GiB` 给"内存开销"封了顶，且当前用量仅 ~0–9MB。**建议下一窗口显式设 `memswap_limit`。**
3. **zram 在真实内存压力下的兜底效果未做压测**（只在受控 scope 里证明过换页路径可用）。
4. **压缩比 2.61×（上轮实测）不是 JVM 堆的真实压缩比**（数据集为零页 + urandom 混合），不可外推。
5. **`zram-swap.service` 自身没有 watchdog**：若内核某次升级后 `zram` 模块缺失，单元会失败
   （这是刻意的"响亮失败"），但**没有告警通道**——需要人工 `systemctl status zram-swap.service` 巡检，
   或后续接入监控。
