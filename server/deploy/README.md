# 部署文档

## 一、系统架构

道路运输安全生产管理平台采用 **单体模块化 + Docker Compose 编排** 架构：

| 服务 | 技术栈 | 端口 | 说明 |
|---|---|---|---|
| frontend | Vue3 + Vite + Element Plus + nginx | 80 | 前端静态托管 + `/api` 反向代理 |
| backend | Spring Boot 3.2.5 + MyBatis-Plus + MySQL + Redis + MinIO | 8080 | 业务后端 |
| mysql | MySQL 8.0 | 3306 | 业务数据库（Flyway 自动迁移） |
| redis | Redis 7 | 6379 | 验证码 / JWT 白名单 |
| minio | MinIO | 9000/9001 | 对象存储（PDF 归档产物） |

## 二、一键部署

### 前置条件
- Docker 20.10+ / Docker Compose v2
- 可用内存 ≥ 4GB

### 启动全栈

```bash
cd server/deploy
docker compose up -d --build
```

首次构建约需 3~5 分钟（后端 maven 构建 + 前端 node 构建）。

### 验证

```bash
# 前端（浏览器访问）
open http://localhost

# 后端健康检查
curl http://localhost:8080/health

# 集成测试（9 项闭环）
bash e2e_test.sh
```

## 三、演示账号

| 角色 | 账号 | 密码 | 说明 |
|---|---|---|---|
| 企业 | aa_gaozhou | 123456 | 佛山市高明高洲燃气有限公司 |
| 服务商 | bb_provider | 123456 | 范本套管理（建套/设默认） |
| 超管 | cc_admin | 123456 | 平台级管理 |

## 四、环境变量

后端通过环境变量覆盖配置（docker-compose 已设置默认值）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| MYSQL_HOST / MYSQL_PORT / MYSQL_DB | mysql / 3306 / safetrans | 数据库 |
| MYSQL_USER / MYSQL_PASSWORD | root / root123 | 数据库账号 |
| REDIS_HOST / REDIS_PORT | redis / 6379 | Redis |
| MINIO_ENDPOINT | http://minio:9000 | 对象存储 |
| MINIO_ACCESS_KEY / MINIO_SECRET_KEY | minioadmin / minioadmin | 对象存储密钥 |
| JWT_SECRET | （开发默认值） | JWT 签名密钥（生产必须修改） |

## 五、数据持久化

三个数据卷持久化数据：

| 卷 | 内容 |
|---|---|
| mysql-data | MySQL 数据文件 |
| redis-data | Redis 数据 |
| minio-data | PDF 归档产物 |

## 六、中文字体说明

PDF 引擎使用 **文泉驿正黑（WenQuanYi Zen Hei）TTF** 字体，已打包进后端 jar（`src/main/resources/fonts/`），无需额外安装系统字体。

> **技术约束**：openhtmltopdf 1.0.10 + PDFBox 2.0.24 不支持 CFF/OTF 字体子集化（报 `OTF fonts do not have a glyf table`），因此选用 TrueType（glyf 表）格式的文泉驿正黑。若需宋体字形，需下载 TrueType 格式的宋体（如「思源宋体 TTF 转换版」）替换 `fonts/` 目录下的字体文件，并同步修改 `PdfService` 的字体注册。

## 七、常用运维命令

```bash
# 查看服务状态
docker compose ps

# 查看后端日志
docker compose logs -f backend

# 重启单个服务
docker compose restart backend

# 停止全栈
docker compose down

# 停止并清除数据卷（危险！）
docker compose down -v
```
