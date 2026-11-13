# hmap-origin

黑马点评课程项目逐步升级为黑马点评 Plus 的学习仓库，保留原版快照、基线修复与功能演进历史。

当前代码采用 Java 17、Spring Boot 3.5.4，后端采用 Maven 多模块，保持一个核心服务进程和 Redis Stream 异步下单结构。运行基础升级的业务验收以实际实验记录为准。

## 技术栈

- Java 17
- Spring Boot 3.5.4
- MyBatis-Plus 3.5.7，Boot 3 starter
- MySQL Connector/J 8.0.33
- Spring Data Redis、Lettuce，由 Spring Boot 管理版本
- Redisson 3.52.0
- Hutool 5.7.17、Lombok
- Vue 2、Element UI、Axios 静态前端
- Nginx

## 仓库结构

- backend/pom.xml：后端 Maven 父工程与聚合入口。
- backend/hmdp-common/：通用响应 Result，普通 JAR。
- backend/hmdp-parameter/：请求/响应 DTO，普通 JAR。
- backend/hmdp-core-service/：业务、启动入口、配置、资源和测试，可执行 JAR。
- frontend/：课程静态前端资源。
- deploy/nginx.conf：前端服务及 API 代理配置。
- .env.example：环境变量名称与非敏感示例。
- AGENTS.md：长期协作规则。

## 环境要求

- JDK 17，IDE、Maven 与应用运行使用同一 JDK。
- Maven 3.x；实际可用性通过构建验证。
- MySQL 5.7 或 8.0；连接兼容性需在实际服务端版本验证。
- Redis：课程原功能基线为 5.x；使用 GEOSEARCH 时需要 Redis 6.2 或更高版本。
- 已有 Nginx 开发环境。

## 本地运行

### 1. 使用已有开发数据

数据库脚本位于 backend/hmdp-core-service/src/main/resources/db/hmdp.sql，包含 DROP/CREATE 与演示数据。

已有开发库无需重新导入。首次初始化前检查脚本和目标库，只在独立测试环境按需执行，不覆盖现有业务数据。

### 2. 配置连接参数

按 .env.example 将变量配置到启动终端或 IDEA 运行配置。Spring Boot 不会自动读取仓库中的 .env.example。

MYSQL_PASSWORD 必须通过环境变量或本地受保护配置提供，源码不提供密码默认值。

Redis 使用 spring.data.redis 配置前缀；StringRedisTemplate 与 Redisson 读取相同的主机、端口、密码和数据库编号。保留 REDIS_HOST、REDIS_PORT 等环境变量名称。

后端默认端口为 8081。JDBC 继续使用 serverTimezone=UTC，避免运行基础迁移同时改变活动时间解释；历史时间与边界行为需实际回归。

### 3. 构建

在 backend/ 执行：

~~~powershell
java -version
mvn -version
mvn -pl hmdp-core-service -am clean package "-DskipTests"
~~~

-DskipTests 编译主源码和测试源码，但不执行测试方法。现有 SpringBootTest 含 Redis 预热和 GEO 写入方法，不作为无副作用的全量回归执行。

### 4. 启动

在已配置连接参数的终端执行：

~~~powershell
java -jar .\hmdp-core-service\target\hmdp-core-service-0.0.1-SNAPSHOT.jar
~~~

启动会运行 Redis Stream 消费者，可能处理开发环境中的在途订单。启动前确认目标数据和在途消息归属；消费组初始化由现有消费者处理，无需每次手工重建。

### 5. 访问前端

沿用现有 Nginx 部署：静态资源目录为 html/hmdp，代理配置参考 deploy/nginx.conf。

浏览器默认访问 http://127.0.0.1:8080，/api 代理到后端 8081。静态前端没有 Vue3/Vite 工程，不需要执行 npm 初始化。

## 已有能力

- 手机验证码登录、Redis Token 会话。
- 店铺缓存、空值保护、店铺类型缓存。
- Redis GEO 附近店铺查询。
- 探店博客、点赞、关注、Feed、共同关注与签到。
- Lua 预扣库存、Redis Stream 异步下单、Redisson 分布式锁。

接口返回订单编号表示异步请求已受理，最终落库应核对数据库；现有页面的成功提示不能单独证明订单已创建。

## 学习与验收边界

原始快照为 676e91b8c39710e22d3f5787df56f839277b9598。主线持续演进，回看原版应读取固定提交。

模块拆分保持单库、单个核心服务进程与 Stream 链路。启动类包名为 com.hmdp，资源类路径与接口契约保留。Kafka、多级缓存、可靠订单闭环、补偿对账、订阅候补与分片按独立任务实施。

项目仍保留课程简化权限、异步订单恢复边界及部分数据准备测试；构建成功不等于业务验收通过。凭据、验证码与个人数据不得写入代码、实验记录或提交。
