#!/usr/bin/env bash
# rt — 红队台账 CLI（Eta 版）
#
# DSH 版红队插件把「资产库 / 漏洞库 / 得分 / 落库」做成了 33 个 redteam_* 工具。
# Eta 没有这些内置工具，这里用 bash + sqlite3 补上同一套语义，命令落在 $RT_HOME/rt.db。
#
# 用法:  rt <域> <动作> [--key=value ...]
#   rt asset add    --ip=1.2.3.4 --port=443 --url=https://x --title=后台 --service=nginx --version=1.24
#   rt asset query  [--sort=todo] [--limit=50] [--ip=1.2.3.4]
#   rt asset get    --id=12
#   rt asset test   --id=12 --status=tested --test="已扫 web 指纹" --surface="疑似 struts2"
#   rt asset assess --id=12 --priority=high --reason="对外 443，版本旧"
#   rt asset stats
#   rt vuln add     --asset=12 --target=https://x --title="CVE-2023-21839" --severity=high
#   rt vuln query   [--asset=12] [--status=confirmed]
#   rt vuln update  --id=3 --status=confirmed
#   rt chain add    --agent=recon --stage=recon --tool="fscan -h ..." --detail="..." --result="..." [--point=terminal-access] [--evidence="..."]
#   rt chain list   [--limit=20]
#   rt cred add     --target=10.1.2.3 --source=弱口令 --tool="hydra" --value="admin:123456" [--ref=/path/proof.txt]
#   rt cred list
#   rt shell add    --asset=12 --kind=behinder --url=http://x/shell.jsp --key=pass
#   rt shell list
#   rt tunnel add   --kind=suo5 --listen=127.0.0.1:1080 --entry=http://x/shell.jsp --reach=10.0.0.0/8 --entry-kind=webshell --command="suo5 -t ..."
#   rt tunnel list
#   rt tunnel update --id=1 --note="已连通"
#   rt access add   --target=x --method=ssh --note="root"
#   rt access list
#   rt session list
#   rt session check --target=10.0.0.5:6379    # 只登记一次连通性检查，实际连通性用 nc/curl 自己验证
#   rt score hit    --code=terminal-access --target=10.1.2.3 --evidence="拿到 root" [--points=10]
#   rt score list
#   rt poc add      --title=... --kind=poc --path=/path
#   rt poc get      --id=1
#   rt poc search   --q=struts
#   rt evidence add --asset=12 --path=/sdcard/rt/shot.png --note="后台首页"
#   rt file add     --path=/path --note=...
#   rt preflight    # 环境自检：sqlite3 / 常用工具 / 环境变量
#
# 状态取值：
#   asset.test_status: untested|testing|tested|blocked|abandoned|no_surface
#   asset.priority:    high|medium|low
#   vuln.status:       candidate|confirmed|false-positive|exploited
#   chain.stage:       recon|internet|boundary|internal|target
set -uo pipefail

RT_HOME="${RT_HOME:-$HOME/redteam}"
DB="$RT_HOME/rt.db"
mkdir -p "$RT_HOME" 2>/dev/null

need_sqlite() {
  command -v sqlite3 >/dev/null 2>&1 || {
    echo "缺少 sqlite3：执行 apk add sqlite 后重试" >&2
    exit 3
  }
}

init_db() {
  need_sqlite
  sqlite3 "$DB" <<'SQL'
CREATE TABLE IF NOT EXISTS assets(
  id INTEGER PRIMARY KEY AUTOINCREMENT, ip TEXT, port TEXT, url TEXT, title TEXT,
  service TEXT, version TEXT, priority TEXT, reason TEXT,
  test_status TEXT DEFAULT 'untested', test_notes TEXT, surface TEXT, blocked INTEGER DEFAULT 0,
  created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS vulns(
  id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id INTEGER, target TEXT, title TEXT,
  severity TEXT, status TEXT DEFAULT 'candidate', note TEXT,
  created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS chains(
  id INTEGER PRIMARY KEY AUTOINCREMENT, agent TEXT, stage TEXT, tool TEXT, detail TEXT,
  result TEXT, point_code TEXT, evidence TEXT, created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS creds(
  id INTEGER PRIMARY KEY AUTOINCREMENT, target TEXT, source TEXT, tool TEXT, secret_ref TEXT,
  secret_value TEXT, created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS shells(
  id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id INTEGER, kind TEXT, url TEXT, pass_key TEXT,
  created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS tunnels(
  id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, listen TEXT, entry TEXT, reach TEXT,
  entry_kind TEXT, command TEXT, note TEXT, created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS access(
  id INTEGER PRIMARY KEY AUTOINCREMENT, target TEXT, method TEXT, note TEXT,
  created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS scores(
  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT, target TEXT, evidence TEXT, points INTEGER,
  created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS pocs(
  id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, kind TEXT, path TEXT, note TEXT,
  created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS evidence(
  id INTEGER PRIMARY KEY AUTOINCREMENT, asset_id INTEGER, kind TEXT, path TEXT, note TEXT,
  created TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS files(
  id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT, note TEXT, created TEXT DEFAULT CURRENT_TIMESTAMP);
SQL
}

arg() { # arg key
  local key="$1" out=""
  shift
  for a in "$@"; do
    case "$a" in
      --"$key"=*) out="${a#--$key=}" ;;
    esac
  done
  printf '%s' "$out"
}

q() { sqlite3 -header -column "$DB" "$1"; }
esc() { printf "%s" "$1" | sed "s/'/''/g"; }

need_sqlite
init_db

domain="${1:-}"; action="${2:-}"
if [ "$domain" = "preflight" ]; then action="preflight"; fi
shift 2 2>/dev/null || true

case "$domain:$action" in
  asset:add)
    echo "asset id=$(sqlite3 "$DB" "INSERT INTO assets(ip,port,url,title,service,version) VALUES('$(esc "$(arg ip "$@")")','$(esc "$(arg port "$@")")','$(esc "$(arg url "$@")")','$(esc "$(arg title "$@")")','$(esc "$(arg service "$@")")','$(esc "$(arg version "$@")")'); SELECT last_insert_rowid();")" ;;
  asset:query)
    limit="$(arg limit "$@")"; [ -n "$limit" ] || limit=50
    where="1=1"
    [ -n "$(arg sort "$@")" ] && [ "$(arg sort "$@")" = "todo" ] && where="$where AND (priority IS NULL OR test_status='untested')"
    [ -n "$(arg ip "$@")" ] && where="$where AND ip='$(esc "$(arg ip "$@")")'"
    q "SELECT id,ip,port,url,priority,test_status FROM assets WHERE $where ORDER BY id DESC LIMIT $limit;" ;;
  asset:get)
    q "SELECT * FROM assets WHERE id=$(arg id "$@");" ;;
  asset:test)
    id="$(arg id "$@")"; st="$(arg status "$@")"
    add=$([ "$st" = "blocked" ] && echo ", blocked=blocked+1" || echo "")
    sqlite3 "$DB" "UPDATE assets SET test_status='$(esc "$st")', test_notes=COALESCE(test_notes,'')||char(10)||'$(esc "$(arg test "$@")")', surface=COALESCE('$(esc "$(arg surface "$@")")', surface)$add WHERE id=$id;"
    echo "asset $id -> $st" ;;
  asset:assess)
    sqlite3 "$DB" "UPDATE assets SET priority='$(esc "$(arg priority "$@")")', reason='$(esc "$(arg reason "$@")")' WHERE id=$(arg id "$@");"
    echo "asset $(arg id "$@") assessed" ;;
  asset:stats)
    q "SELECT test_status, COUNT(*) n FROM assets GROUP BY test_status;" ;;
  vuln:add)
    asset_id="$(arg asset "$@")"; [ -n "$asset_id" ] || asset_id="NULL"
    echo "vuln id=$(sqlite3 "$DB" "INSERT INTO vulns(asset_id,target,title,severity,note) VALUES($asset_id,'$(esc "$(arg target "$@")")','$(esc "$(arg title "$@")")','$(esc "$(arg severity "$@")")','$(esc "$(arg note "$@")")'); SELECT last_insert_rowid();")" ;;
  vuln:query)
    where="1=1"
    [ -n "$(arg asset "$@")" ] && where="$where AND asset_id=$(arg asset "$@")"
    [ -n "$(arg status "$@")" ] && where="$where AND status='$(esc "$(arg status "$@")")'"
    q "SELECT id,asset_id,target,title,severity,status FROM vulns WHERE $where ORDER BY id DESC LIMIT 50;" ;;
  vuln:update)
    sqlite3 "$DB" "UPDATE vulns SET status='$(esc "$(arg status "$@")")', note=COALESCE(note,'')||char(10)||'$(esc "$(arg note "$@")")' WHERE id=$(arg id "$@");"
    echo "vuln $(arg id "$@") updated" ;;
  chain:add)
    id=$(sqlite3 "$DB" "INSERT INTO chains(agent,stage,tool,detail,result,point_code,evidence) VALUES('$(esc "$(arg agent "$@")")','$(esc "$(arg stage "$@")")','$(esc "$(arg tool "$@")")','$(esc "$(arg detail "$@")")','$(esc "$(arg result "$@")")','$(esc "$(arg point "$@")")','$(esc "$(arg evidence "$@")")'); SELECT last_insert_rowid();")
    echo "chain id=$id" ;;
  chain:list)
    limit="$(arg limit "$@")"; [ -n "$limit" ] || limit=20
    q "SELECT id,agent,stage,substr(tool,1,30) tool,substr(result,1,30) result,point_code,created FROM chains ORDER BY id DESC LIMIT $limit;" ;;
  cred:add)
    id=$(sqlite3 "$DB" "INSERT INTO creds(target,source,tool,secret_ref,secret_value) VALUES('$(esc "$(arg target "$@")")','$(esc "$(arg source "$@")")','$(esc "$(arg tool "$@")")','$(esc "$(arg ref "$@")")','$(esc "$(arg value "$@")")'); SELECT last_insert_rowid();")
    echo "cred id=$id" ;;
  cred:list)
    q "SELECT id,target,source,secret_value,created FROM creds ORDER BY id DESC LIMIT 50;" ;;
  shell:add)
    id=$(sqlite3 "$DB" "INSERT INTO shells(asset_id,kind,url,pass_key) VALUES(NULLIF('$(arg asset "$@")','')+0,'$(esc "$(arg kind "$@")")','$(esc "$(arg url "$@")")','$(esc "$(arg key "$@")")'); SELECT last_insert_rowid();")
    echo "shell id=$id" ;;
  shell:list)
    q "SELECT id,asset_id,kind,url,created FROM shells ORDER BY id DESC LIMIT 50;" ;;
  tunnel:add)
    id=$(sqlite3 "$DB" "INSERT INTO tunnels(kind,listen,entry,reach,entry_kind,command,note) VALUES('$(esc "$(arg kind "$@")")','$(esc "$(arg listen "$@")")','$(esc "$(arg entry "$@")")','$(esc "$(arg reach "$@")")','$(esc "$(arg entry-kind "$@")")','$(esc "$(arg command "$@")")','$(esc "$(arg note "$@")")'); SELECT last_insert_rowid();")
    echo "tunnel id=$id" ;;
  tunnel:list)
    q "SELECT id,kind,listen,entry,reach,note,created FROM tunnels ORDER BY id DESC LIMIT 50;" ;;
  tunnel:update)
    sqlite3 "$DB" "UPDATE tunnels SET note=COALESCE(note,'')||char(10)||'$(esc "$(arg note "$@")")' WHERE id=$(arg id "$@");"
    echo "tunnel $(arg id "$@") updated" ;;
  access:add)
    id=$(sqlite3 "$DB" "INSERT INTO access(target,method,note) VALUES('$(esc "$(arg target "$@")")','$(esc "$(arg method "$@")")','$(esc "$(arg note "$@")")'); SELECT last_insert_rowid();")
    echo "access id=$id" ;;
  access:list)
    q "SELECT id,target,method,note,created FROM access ORDER BY id DESC LIMIT 50;" ;;
  session:list)
    q "SELECT 'shell' kind,id,url target,created FROM shells UNION ALL SELECT 'tunnel',id,listen,created FROM tunnels ORDER BY created DESC LIMIT 30;" ;;
  session:check)
    target="$(arg target "$@")"
    echo "登记：${target} 连通性检查 $(date '+%F %T')（实际连通请用 nc -zv / curl -m5 自行验证，把结果写进 rt chain add）"
    sqlite3 "$DB" "INSERT INTO access(target,method,note) VALUES('$(esc "$target")','session-check','连通性检查登记');" ;;
  score:hit)
    out=$(sqlite3 "$DB" "INSERT INTO scores(code,target,evidence,points) VALUES('$(esc "$(arg code "$@")")','$(esc "$(arg target "$@")")','$(esc "$(arg evidence "$@")")',NULLIF('$(arg points "$@")','')+0); SELECT last_insert_rowid()||'|'||(SELECT COALESCE(SUM(COALESCE(points,0)),0) FROM scores);")
    echo "score id=${out%%|*} total=${out##*|}" ;;
  score:list)
    q "SELECT code,COUNT(*) n,COALESCE(SUM(COALESCE(points,0)),0) pts FROM scores GROUP BY code ORDER BY pts DESC;" ;;
  poc:add)
    id=$(sqlite3 "$DB" "INSERT INTO pocs(title,kind,path,note) VALUES('$(esc "$(arg title "$@")")','$(esc "$(arg kind "$@")")','$(esc "$(arg path "$@")")','$(esc "$(arg note "$@")")'); SELECT last_insert_rowid();")
    echo "poc id=$id" ;;
  poc:get)
    q "SELECT * FROM pocs WHERE id=$(arg id "$@");" ;;
  poc:search)
    q "SELECT id,title,kind,path FROM pocs WHERE title LIKE '%$(esc "$(arg q "$@")")%' OR note LIKE '%$(esc "$(arg q "$@")")%' LIMIT 20;" ;;
  evidence:add)
    id=$(sqlite3 "$DB" "INSERT INTO evidence(asset_id,kind,path,note) VALUES(NULLIF('$(arg asset "$@")','')+0,'$(esc "$(arg kind "$@")")','$(esc "$(arg path "$@")")','$(esc "$(arg note "$@")")'); SELECT last_insert_rowid();")
    echo "evidence id=$id" ;;
  file:add)
    id=$(sqlite3 "$DB" "INSERT INTO files(path,note) VALUES('$(esc "$(arg path "$@")")','$(esc "$(arg note "$@")")'); SELECT last_insert_rowid();")
    echo "file id=$id" ;;
  preflight|*:preflight)
    echo "== rt preflight =="
    printf 'db: %s\n' "$DB"
    for t in sqlite3 curl nmap nc python3 fscan gogo; do
      if command -v "$t" >/dev/null 2>&1; then echo "  [ok]   $t"; else echo "  [miss] $t"; fi
    done
    for v in FOFA_KEY; do
      if [ -n "${!v:-}" ]; then echo "  [ok]   \$$v"; else echo "  [miss] \$$v（缺它只能靠 crt.sh / 子域枚举兜底）"; fi
    done
    echo "assets=$(sqlite3 "$DB" 'SELECT COUNT(*) FROM assets;') scores=$(sqlite3 "$DB" 'SELECT COUNT(*) FROM scores;')" ;;
  *)
    sed -n '2,60p' "$0" | sed 's/^# \{0,1\}//' ; exit 2 ;;
esac
