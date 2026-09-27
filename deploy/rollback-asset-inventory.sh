#!/usr/bin/env bash
# rollback-asset-inventory.sh —— 回滚资产**只读清点** + 保留策略判定（**绝不删除**）
#
# 用法：bash /usr/local/sbin/rollback-asset-inventory.sh
#
# 为什么需要它：这台机器根盘 29G，长期在 85–90% 徘徊，而"可回收"的大头全是**回滚资产**
#   （`docker system df` 报的 4.896GB 里，绝大部分是 14 个 `ypbin/ypbin-iot:rollback-*` 的唯一层）。
#   靠"感觉"删会删掉真正要紧的那几个 ⇒ 需要一份**有序**的判定，而不是一次清理。
#
# 保留策略（2026-09-26 确立；**本脚本只判定与列出，不执行任何删除**）：
#   · **镜像 rollback tag**：保留 **最新 5 个**（按创建时间）+ **当前运行容器的镜像**（无论新旧）；
#   · **`/root` 下的 rollback jar**：保留 **最新 2 个**；
#   · **配置 / SQL / Nacos 快照**：**全部保留**（体积小、且是唯一的人工回滚手段）。
#   超出保留数的条目只作为 **`过期候选`** 列出，**交由用户决定**。
set -uo pipefail
say() { printf '%s\n' "$*"; }
KEEP_TAGS=5
KEEP_JARS=2
# access 的 jar 备份：策略「按服务各留最新」——access 只需 1 个（它是被 iot 内部调用的采集单元）
KEEP_JARS_ACCESS=1

say "==================== 回滚资产清点 @ $(date -u +%FT%TZ) ===================="

# ── A. 镜像 rollback tag ─────────────────────────────────────────────────────
say
say "── A. 镜像 rollback tag（策略：保留最新 $KEEP_TAGS 个 + 当前运行镜像）──"
# ⚠️ 必须按 **image ID** 比对，不能按 tag 名：运行容器用的是 `ypbin/ypbin-iot:local`，
#    而回滚 tag（如 `…:rollback-rot-20260926-144415`）指向**同一个 ID** ⇒ 按名字比会漏判。
RUNNING="$(docker ps --format '{{.Image}}' | sort -u)"
# 直接从**容器**取 `.Image`（那是 image ID）；注意不能对**镜像对象**用 `.Image`
# （镜像对象的字段是 `.Id`，用 `.Image` 会报 'map has no entry for key "Image"' —— 本轮实测踩到）。
# 归一化成 **12 位短 ID**：容器 `.Image` 是 `sha256:<64位>`，而 `docker images --format '{{.ID}}'`
# 给的是 12 位短 ID ⇒ 不归一化会永远比不上（本轮实测踩到，判定恒为"非运行镜像"）。
RUNNING_IDS="$(docker ps -q | while read -r cid; do
  docker inspect -f '{{.Image}}' "$cid" | sed 's/^sha256://' | cut -c1-12
done | sort -u)"
docker images --format '{{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.CreatedAt}}\t{{.Size}}' \
  | grep -i 'rollback' | sort -k3,4 -r > /tmp/.rb-tags.$$ || true
n=0
used=0
while IFS=$'\t' read -r ref id created size; do
  [ -n "${ref:-}" ] || continue
  n=$((n+1))
  # 顺序很重要：**先判"是否正在运行的镜像"**（它无论如何都要保留，且要计入 used），
  # 再判"是否在最新 N 内"。反过来写会让"既是运行镜像又在最新 N 内"的那条不计入 used（本轮实测踩到）。
  if printf '%s\n' "$RUNNING_IDS" | grep -qx "$id"; then
    used=$((used+1)); verdict="保留（**当前运行镜像**，同 image ID）"
  elif [ "$n" -le "$KEEP_TAGS" ]; then verdict="保留（最新 $KEEP_TAGS 内）"
  else verdict="**过期候选**（等用户决定）"; fi
  printf '  [%2d] %-58s %s %-9s %s\n' "$n" "$ref" "${id:0:12}" "$size" "$verdict"
done < /tmp/.rb-tags.$$
rm -f /tmp/.rb-tags.$$
[ "$n" -eq 0 ] && say "  （无 rollback tag）"
say "  合计 $n 个 rollback tag；其中 **$used 个**指向**正在运行的镜像**（按 image ID 判定 ⇒ 必须保留）"

# ── B. /root 下的 rollback jar（**按服务各自**保留最新 N 个）─────────────────
#
# ⚠️ 为什么必须按服务分组、并且**自检盲区**（2026-09-27 实测教训）：
#   原实现只 glob `/root/ypbin-iot-jar-rollback-*.jar`，于是两类文件**永远不出现**在清单里：
#     · `/root/ypbin-access-jar-*.jar`（另一个服务的 jar 备份，**整个服务都没被覆盖**）
#     · `/root/ypbin-iot-jar-rollback2-*.jar` / `rollback3-*.jar`（`rollback-` 后面不是连字符 ⇒ glob 不匹配）
#   ⇒ 它们既不会被保留、也不会被列为过期候选，**只能靠人偶然发现**。
#   **清单不覆盖的对象等于没有策略**：所以本节末尾有一段自检，凡 `/root/*jar*.jar` 里没被
#   本节任何服务 glob 命中的，一律**显式打出来**（而不是静默漏掉）。
say
say "── B. /root 下的 rollback jar（策略：**按服务**各留最新 N 个）──"
n=0
for spec in "ypbin-iot:${KEEP_JARS}" "ypbin-access:${KEEP_JARS_ACCESS}"; do
  svc="${spec%%:*}"; keep="${spec##*:}"
  # shellcheck disable=SC2086
  files=$(ls -1t /root/${svc}-jar-*.jar 2>/dev/null)
  if [ -z "$files" ]; then
    say "  -- $svc（保留最新 $keep 个）：无"
    continue
  fi
  say "  -- $svc（保留最新 $keep 个）--"
  i=0
  for f in $files; do
    i=$((i+1)); n=$((n+1))
    if [ "$i" -le "$keep" ]; then verdict="保留（最新 $keep 内）"; else verdict="**过期候选**（等用户决定）"; fi
    printf '  [%2d] %-58s %-10s %s\n' "$n" "$(basename "$f")" "$(du -h "$f" | cut -f1)" "$verdict"
  done
done

# 盲区自检：/root 下所有 *jar*.jar 必须被上面的服务 glob 命中，否则显式报出（不静默漏）
blind=0
for f in $(ls -1 /root/*jar*.jar 2>/dev/null); do
  hit=0
  for svc in ypbin-iot ypbin-access; do
    case "$f" in /root/${svc}-jar-*.jar) hit=1 ;; esac
  done
  if [ "$hit" -eq 0 ]; then
    say "  ⚠️ **未纳入策略**：$f —— 清单的 glob 没覆盖它 ⇒ 按「等于没有策略」处理，请把它补进本节的服务清单"
    blind=$((blind+1))
  fi
done
say "  合计 $n 个（另有 $blind 个**未纳入策略**，见上）"

# ── C. 配置 / SQL / Nacos 快照（全留）────────────────────────────────────────
say
say "── C. 配置 / SQL / Nacos 快照（策略：**全部保留**）──"
say "  -- 目录型快照 --"
find /opt/ypbin /root -maxdepth 1 -type d \( -name "*rollback*" -o -name "*rotation*" -o -name "cred-hardening-*" -o -name "l3-*" -o -name "iot-demo" \) \
  -printf '    %10s  %TY-%Tm-%TdT%TH:%TM  %p\n' 2>/dev/null | sort -k3
say "  -- 文件型快照（.bak/.sql/.env 快照）--"
find /opt/ypbin /root -maxdepth 3 -type f \( -name "*.bak*" -o -name "*.env.bak*" -o -name "*before-deploy.env" -o -name "*.sql" \) \
  -printf '    %10s  %m  %TY-%Tm-%TdT%TH:%TM  %p\n' 2>/dev/null | sort -k4 | tail -40
say "  （以上**全部保留**：体积小，且是唯一的人工回滚手段）"

# ── D. ⚠️ 已知危险资产（禁止执行）────────────────────────────────────────────
say
say "── D. ⚠️ 已知**危险**回滚资产（已被 VOID，禁止执行）──"
find /opt/ypbin /root -maxdepth 3 \( -name "*.VOID-DO-NOT-RUN" -o -name "VOID.md" \) -printf '    %m  %p\n' 2>/dev/null
for d in /opt/ypbin/cred-hardening-*; do
  [ -d "$d" ] || continue
  printf '    %s  （该目录内若有 5 字符口令的 deploy.env.bak，误跑旧 rollback 会**降级口令并中断 5 服务鉴权**）\n' "$d"
  printf '      口令快照指纹检查：'
  if [ -f "$d/deploy.env.bak" ]; then
    sed -n 's/^NACOS_ADMIN_PASSWORD=//p' "$d/deploy.env.bak" | head -1 \
      | python3 -c 'import sys,hashlib
v=sys.stdin.read().strip()
print("len=%d sha256[:16]=%s" % (len(v), hashlib.sha256(v.encode()).hexdigest()[:16]) if v else "（空）")'
  else say "（无 deploy.env.bak）"; fi
done

# ── E. 现状 ─────────────────────────────────────────────────────────────────
say
say "── E. 现状 ──"
df -h / | sed 's/^/  /'
docker system df | sed 's/^/  /'
say
say "⚠️ 本脚本**只清点、不删除**。「过期候选」需人工逐条确认后再处理（并保留最小回滚集）。"
