# 部署文档（A1 全栈部署验收）

## 一、系统架构

道路运输安全生产管理平台采用 **单体模块化 + Docker Compose 编排** 架构：

| 服务 | 技术栈 | 端口 | 说明 |
|---|---|---|---|
| frontend | Vue3 + Vite + Element Plus + nginx | 80 | 前端静态托管 + `/api` 反向代理 |
| backend | Spring Boot 3.2.5 + MyBatis-Plus + MySQL + Redis + MinIO | 8080 | 业务后端 |
| mysql | MySQL 8.0 | 3306 | 业务数据库（Flyway 自动迁移） |
| redis | Redis 7 | 6379 | 验证码 / JWT 白名单 |
| minio | MinIO | 9000/9001 | 对象存储（PDF 归档产物） |
| e2e | alpine + curl + python3 + redis-cli | — | **仅 `profile=test`**，端到端测试容器，不常驻 |

所有服务配置 `restart: unless-stopped`；mysql / redis / minio / backend / frontend 均带 healthcheck，`backend` 通过 `/dev/tcp` 端口探测，不额外安装 curl。

## 二、一键部署（A1 验收通过）

### 前置条件
- Docker 20.10+ / Docker Compose v2
- 可用内存 ≥ 4GB
- 宿主机 80 / 8080 / 3306 / 6379 / 9000 / 9001 端口未被占用

### 启动全栈

```bash
cd server/deploy
docker compose up -d --build
```

一条命令从空环境拉起 **5 个常驻服务**（含镜像构建），首次约需 3~5 分钟。数据库表由 Flyway `V1__init.sql` / `V2__seed.sql` 自动初始化，无需手工导入。

### 验证

```bash
# 服务状态（各服务应显示 healthy）
docker compose ps

# 前端（浏览器访问）
open http://localhost

# 后端健康检查
curl http://localhost:8080/health
# => {"code":0,"data":{"status":"UP",...}}

# 端到端测试：在 compose 网络内的容器中跑 9 项闭环
docker compose --profile test run --rm e2e
# => 结果：10 通过，0 失败（含 1 项健康检查 + 9 项业务闭环）

# 也可在宿主机直接跑（自动改用 docker exec 读验证码）
bash e2e_test.sh
```

## 三、演示账号

| 角色 | 账号 | 密码 | 说明 |
|---|---|---|---|
| 企业 | aa_gaozhou | 123456 | 佛山市高明高洲燃气有限公司 |
| 服务商 | bb_provider | 123456 | 范本套管理（建套/设默认） |
| 超管 | cc_admin | 123456 | 平台级管理 |

## 四、数据持久化与重启恢复

三个数据卷**显式固定卷名**，不受 compose 项目名影响（备份/恢复脚本依赖此约定）：

| 卷 | 内容 |
|---|---|
| `safetrans-mysql-data` | MySQL 数据文件 |
| `safetrans-redis-data` | Redis RDB |
| `safetrans-minio-data` | PDF 归档产物 |

重启验证：

```bash
docker compose restart          # 或 docker compose down && docker compose up -d
docker compose ps               # 5 服务 healthy
curl http://localhost:8080/api/tpl/set -H "Authorization: Bearer <token>"
# 数据卷未删除 → 企业/岗位/范本套/归档项全部保留
```

`docker compose down -v` 才会删除数据卷（危险操作）。

## 五、备份与恢复

```bash
cd server/deploy

# 热备（不停服，默认）
./backup.sh
# 冷备（先停 frontend/backend 再备，一致性更强）
./backup.sh --cold
# 产出：backups/20261106_023000/{mysql.sql.gz,redis.tar.gz,minio.tar.gz,MANIFEST.txt}

# 覆盖式恢复（会清空当前 Redis / MinIO 卷）
./restore.sh backups/20261106_023000          # 交互确认
./restore.sh backups/20261106_023000 -y       # 自动化/无人值守
```

恢复流程：停 backend/frontend/redis/minio → MySQL 逻辑导入（dump 含 `--add-drop-database`，导入即覆盖）→ 清空并解压 Redis / MinIO 卷 → 拉起全部服务 → 轮询 `/health` 至 UP。

> `backups/` 已在 `.gitignore` 中忽略，备份可能含真实业务数据，禁止入库。生产环境应把备份目录挂载到独立磁盘/对象存储并加密留存。

## 六、环境变量

后端通过环境变量覆盖配置（compose 已设置开发默认值）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| MYSQL_HOST / MYSQL_PORT / MYSQL_DB | mysql / 3306 / safetrans | 数据库 |
| MYSQL_ROOT_PASSWORD | root123 | 数据库 root 密码 |
| REDIS_HOST / REDIS_PORT | redis / 6379 | Redis |
| MINIO_ENDPOINT | http://minio:9000 | 对象存储 |
| MINIO_ROOT_USER / MINIO_ROOT_PASSWORD | minioadmin / minioadmin | 对象存储密钥 |
| JWT_SECRET | （开发默认值） | JWT 签名密钥（**生产必须修改**） |

可在 `server/deploy/.env` 中覆盖（该文件已被忽略）。当前默认值仅用于本地开发，生产环境必须全部替换——凭据外置与密钥管理是 A3 的验收项。

## 七、中文字体说明

PDF 引擎使用 **文泉驿正黑（WenQuanYi Zen Hei）TTF** 字体，已打包进后端 jar（`src/main/resources/fonts/`），无需额外安装系统字体。

> **技术约束**：openhtmltopdf 1.0.10 + PDFBox 2.0.24 不支持 CFF/OTF 字体子集化（报 `OTF fonts do not have a glyf table`），因此选用 TrueType（glyf 表）格式的文泉驿正黑。若需宋体字形，需下载 TrueType 格式的宋体替换 `fonts/` 目录下的字体文件，并同步修改 `PdfService` 的字体注册。

## 八、常用运维命令

```bash
docker compose ps                          # 服务状态
docker compose logs -f backend             # 后端日志
docker compose restart backend             # 重启单个服务
docker compose down                        # 停止全栈（保留数据）
docker compose down -v                     # 停止并清除数据卷（危险！）
docker compose --profile test run --rm e2e # 容器内端到端测试
```

## 九、阶段 A/B/C 已落地能力

| 阶段 | 能力 | 入口 |
|---|---|---|
| A2 | 统一错误码、参数校验、`sys_oper_log` 操作日志、通用文件上传、分页封装、Swagger | `/swagger-ui.html`、`/api/file/upload` |
| A3 | 单元测试、三角色越权拦截、JWT 刷新、密码策略、匿名接口限流、`application-prod.yml` 凭据外置 | `.env.example` |
| A4 | Flyway V3~V6：一期 56 表 / 二期 28 表 / 10+11 盒目录树 / 一人一档一车一档字典 | 共 100 张表 |
| A5 | Element Plus 按需引入、通用表格/表单/上传组件、菜单与路由由元数据配置驱动 | `tools/gen_biz_meta.py` |
| B1~B9 | 人员/车辆/制度/三检/隐患/会议/档案柜/服务商代录/适老化 H5 | `/biz/<模块>`、`/archive-overview`、`/collect`、`/m/check` |
| C1~C7 | 培训学时与考试、应急/事故/考核台账、风险可视驾驶舱、自定义签署 PDF、1000 并发压测 | `/risk-dashboard`、`/flow-tools` |

**架构说明**：106 张表不逐表堆四层代码，采用**元数据驱动的通用业务引擎**。DDL 逐字取自 `database/schema.sql`，字段白名单由 `tools/gen_biz_meta.py` 生成到 `modules.json`；通用 CRUD 只接受白名单字段并加反引号，租户隔离与 MyBatis 插件一致；任何产生记录的操作由 `ArchiveRegistrar` 幂等登记 `archive_item`（铁律 6）。有状态流转（三检月报、隐患闭环、会议四段式、代录、学时考试、签署合成）由 `BizFlowController` 专用实现。新增模块只需改生成器配置，无需手写四层代码与页面。

## 十、验收脚本

```bash
cd server/deploy

# 9 项垂直切片闭环回归
docker compose --profile test run --rm e2e

# 阶段 A/B/C 全链路验收（38 项）
docker compose --profile test run --rm e2e ./e2e_phase_test.sh

# C1 线上学习 1000 并发压测（宿主机）
python3 stress_test.py

# 后端单元测试
cd ../backend && mvn test
```

当前验收结果：闭环回归 **10/10**、全链路 **38/38**、单元测试 **6/6**、并发压测 **1000/1000 成功（QPS 470，P95 394ms）**。

## 十一、生产部署（E1）

生产环境使用**独立编排** `docker-compose.prod.yml`，与开发编排互不继承：启用 `prod` profile（所有凭据无默认值，缺失即启动失败）、仅 frontend 暴露 80/443、强制 HTTPS、逐服务资源限制、json-file 日志轮转。完整流程见 **[生产部署与运维手册](生产部署与运维手册.md)**。

```bash
cd server/deploy
cp .env.example .env && vi .env                      # 全部必填凭据
# 放入真实证书到 certs/fullchain.pem + certs/privkey.pem
DOMAIN=your.domain ./render_nginx_conf.sh
docker compose -f docker-compose.prod.yml config --quiet
docker compose -f docker-compose.prod.yml pull       # CI 预构建镜像
docker compose -f docker-compose.prod.yml up -d --no-build
```

本地 HTTPS 演练（自签证书，仅演练）：

```bash
DOMAIN=localhost ./certs/gen_selfsigned_cert.sh
DOMAIN=localhost ./render_nginx_conf.sh
docker tag deploy-frontend:latest safetrans/frontend:latest
docker tag deploy-backend:latest  safetrans/backend:latest
docker compose -f docker-compose.prod.yml up -d --no-build
curl -I http://localhost/            # 301
curl -k  https://localhost/health    # UP
```

运维脚本（`crontab.example` 已给出定时任务样例）：

| 脚本 | 用途 |
|---|---|
| `backup_cron.sh` | 每日冷备 + gzip/tar 完整性校验 + 异地同步钩子 + 14 天保留清理 |
| `monitor.sh` | 每 5 分钟巡检 6 类指标（容器/入口/健康/磁盘/备份/证书），异常经 `alert.sh` 推送 |
| `restore.sh` | 覆盖式恢复（支持生产编排与 Redis 密码，健康探测经 HTTPS） |
| `rollback.sh` / `go_live_check.sh` | E4 回滚与上线复测 |

> 生产必备但沙箱无法提供的真实资源：域名与 ICP 备案、CA 签发证书、生产服务器、告警 webhook、异地备份目标。这些是 E4《上线检查清单》的前置阻断项。

## 十二、开户引导与验收脚本（E2 / E3 / E4）

| 脚本 | 用途 |
|---|---|
| `open_account.sh` | 命令行签约开户：`./open_account.sh "企业全称" "信用代码" [GENERAL\|DANGER] [地区] [负责人] [电话]` |
| `e2e_onboarding_test.sh` | E2 开户引导 29 项全链路验收（容器内） |
| `e2e_test.sh` | 原有 9 项垂直切片闭环回归 |
| `e2e_phase_test.sh` | A/B/C 阶段 38 项全链路验收 |
| `stress_test.py` | C1 1000 并发压测 |
| `trial_run.sh` | E3 种子客户试运行演练（反馈闭环 + 四条标准计时，幂等可重跑） |
| `trial_report.py` | E3 试运行报告生成器（读反馈接口真实数据，输出 Markdown） |
| `go_live_check.sh` / `rollback.sh` | E4 上线复测与回滚演练 |

用户手册见 `docs/用户手册-企业版.md`、`docs/用户手册-服务商版.md`、`docs/培训材料与试运行方案.md`；演练报告见 `docs/试运行演练报告.md`。

```bash
# 开户后企业主账号（用户名=企业全称，初始密码 888888）从 /m/onboard 完成三步填报
# 服务商在「新企业接入引导」页查看六阶段进度、逐项勾选、做归档就位自检
docker compose --profile test run --rm e2e ./e2e_onboarding_test.sh
```

> 开户生成的账号身份由 `sys_user.user_type` 决定（不再是 `aa/bb/cc` 前缀），人员手机号自动开通的驾驶员账号为 `driver`，前端菜单按角色自动收敛。归档项由 `ArchiveRegistrar` 显式指定租户，服务商代录不会挂到平台名下。
