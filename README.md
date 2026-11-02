# hmap-origin

黑马点评课程项目的完整源码快照，包含 Spring Boot 后端、课程提供的静态前端和 Nginx 反向代理配置。

> 该项目保留课程练习阶段的实现方式，适合学习和二次开发，不建议直接用于生产环境。

## 技术栈

- Java 8
- Spring Boot 2.3.12
- MyBatis-Plus 3.4.3
- MySQL 5.1 JDBC Driver
- Redis、Spring Data Redis、Redisson
- Hutool、Lombok
- Vue 2、Element UI、Axios
- Nginx

## 仓库结构

```text
hmap-origin/
├── backend/                 # Spring Boot 后端
├── frontend/                # 静态前端资源
├── deploy/
│   └── nginx.conf           # 项目 Nginx 配置
├── .env.example             # 环境变量示例
└── README.md
```

## 环境要求

- JDK 8
- Maven 3.x
- MySQL 5.7 或更高版本
- Redis 5.x 或更高版本
- Nginx 1.18.x

## 本地运行

### 1. 初始化数据库

```bash
mysql -u root -p < backend/src/main/resources/db/hmdp.sql
```

数据库脚本会创建 `hmdp` 数据库及课程演示数据。

### 2. 配置环境变量

根据 `.env.example` 设置数据库和 Redis 连接信息。PowerShell 示例：

```powershell
$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "hmdp"
$env:MYSQL_USERNAME = "root"
$env:MYSQL_PASSWORD = "your-password"
$env:REDIS_HOST = "127.0.0.1"
$env:REDIS_PORT = "6379"
```

### 3. 启动 Redis 并初始化秒杀消费组

首次运行时执行：

```bash
redis-cli XGROUP CREATE stream.orders g1 $ MKSTREAM
```

如果消费组已经存在，不需要重复创建。

### 4. 启动后端

```bash
cd backend
mvn spring-boot:run
```

后端默认监听 `http://127.0.0.1:8081`。

### 5. 部署前端

将 `frontend/` 中的文件放入 Nginx 的 `html/hmdp/` 目录，然后使用本仓库的 `deploy/nginx.conf` 替换 Nginx 的 `conf/nginx.conf`。

```bash
nginx -s reload
```

浏览器访问 `http://127.0.0.1:8080`。Nginx 会将 `/api` 请求转发到后端的 `8081` 端口。

## 已实现功能

- 手机验证码登录和 Redis Token 会话
- 店铺缓存、缓存穿透保护、店铺类型缓存
- Redis GEO 附近店铺查询
- 探店博客、点赞、关注和关注流
- 用户签到和共同关注
- Lua 预扣库存、Redis Stream 异步下单、Redisson 分布式锁

## 说明

- 数据库密码和 Redis 密码不再写入源码，请通过环境变量提供。
- 前端为课程提供的静态资源，不包含前端工程化源码。
- 项目仍保留课程中的简化权限设计和未完成功能，公开部署前应进行安全审查。

