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
