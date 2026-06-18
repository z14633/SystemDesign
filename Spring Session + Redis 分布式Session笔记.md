# Spring Session + Redis 分布式Session

## 一、为什么需要分布式Session

### 传统Session的痛点

传统 `HttpSession` 存在 **Tomcat的JVM内存** 中：

1. **多实例无法共享**：部署多台服务器时，session只存在于创建它的机器上，用户请求被转发到另一台服务器时session丢失
2. **服务器重启丢失**：Tomcat重启后内存清空，所有用户都得重新登录

### Spring Session + Redis 的解决方案

将session数据存入Redis，所有服务器实例共享同一份session数据，任何实例都能读取同一个用户的登录状态。

---

## 二、项目配置

### pom.xml 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.session</groupId>
    <artifactId>spring-session-data-redis</artifactId>
</dependency>
```

### application.yml 配置

> [!important] Spring Boot 2.x vs 3.x Redis配置路径差异
> Spring Boot **2.x**: `spring.redis.host` / `spring.redis.port`
> Spring Boot **3.x**: `spring.data.redis.host` / `spring.data.redis.port`
> 项目使用 Spring Boot 2.7.18，必须使用 `spring.redis.*` 路径，否则配置不生效，会使用默认值 `localhost:6379`

```yaml
spring:
  session:
    store-type: redis          # 指定session存储方式为Redis
  redis:
    host: 212.64.12.73
    port: 6379
    password: 123456           # Redis有密码必须配置，否则报 NOAUTH 错误
```

### JSON序列化配置（让Redis中数据可读）

默认Java序列化在Redis中是二进制乱码，配置JSON序列化后数据可读：

```java
@Configuration
public class SessionConfig {

    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }
}
```

> [!warning] 切换序列化器后必须清除旧数据
> 旧的Java序列化数据和新的JSON序列化器不兼容，必须清除Redis中旧的session数据：
> ```bash
> # redis-cli 中执行
> KEYS spring:session:*
> DEL spring:session:sessions:xxx ...  # 删除所有旧key
> # 或者直接
> FLUSHDB
> ```

### Entity类要求

存入Redis Session的对象必须实现 `Serializable`：

```java
public class User implements Serializable {
    private Long id;
    private String name;
    // 构造器、getter、setter...
}
```

---

## 三、Spring Session 自动配置原理

### 全链路：从依赖到Filter生效

```
pom.xml 加依赖
  → classpath 中有了 spring-session-data-redis 的类
  → Spring Boot 启动读取 spring-boot-autoconfigure.jar 中的 META-INF/spring.factories
  → 发现 SessionAutoConfiguration 自动配置类
  → @ConditionalOnClass(RedisIndexedSessionRepository.class) 条件满足 ✅
  → 自动注册两个核心 Bean:
      1. RedisIndexedSessionRepository (读写Redis)
      2. SessionRepositoryFilter (偷梁换柱的Filter)
  → SessionRepositoryFilter 自动注册到 Servlet Filter Chain
  → 所有请求经过此Filter → HttpSession被替换为RedisSession
```

> [!note] 零代码生效
> 只需加依赖 + 配置Redis连接信息，Spring Boot自动完成全部配置，无需手动注册Filter或写任何额外代码。

### 条件检查机制

| 条件注解 | 检查内容 | 满足条件 |
|---------|---------|---------|
| `@ConditionalOnClass(SessionRepository.class)` | classpath中是否有SessionRepository | 引入spring-session-data-redis ✅ |
| `@ConditionalOnClass(RedisIndexedSessionRepository.class)` | classpath中是否有Redis仓库类 | 同上 ✅ |
| `@ConditionalOnMissingBean(SessionRepository.class)` | 容器中是否已手动定义SessionRepository | 没有手动定义 ✅ |

---

## 四、SessionRepositoryFilter —— "偷梁换柱"的核心

### Filter做了什么

SessionRepositoryFilter 用 **装饰器模式** 包装了原始的 HttpServletRequest：

```java
// SessionRepositoryFilter.doFilterInternal() 核心逻辑：

// 1. 创建包装类，重写getSession()
SessionRepositoryRequestWrapper wrappedRequest = 
    new SessionRepositoryRequestWrapper(request, response, servletContext);

// 2. 把包装后的request传给后续Filter和Controller
filterChain.doFilter(wrappedRequest, wrappedResponse);

// 3. 请求结束后，把session数据写入Redis
wrappedRequest.commitSession();
```

### SessionRepositoryRequestWrapper 的关键方法

```java
@Override
public HttpSession getSession(boolean create) {
    // 从Cookie取sessionId
    String requestedSessionId = getRequestedSessionId();
    
    if (requestedSessionId != null) {
        // ★ 从Redis查找session，不是从Tomcat内存！
        S session = sessionRepository.findById(requestedSessionId);
        if (session != null) {
            // ★ 返回RedisSession的HttpSession包装类
            return new HttpSessionWrapper(session, this);
        }
    }
    
    if (create) {
        // ★ 创建新session，也是基于Redis的
        S session = sessionRepository.createSession();
        return new HttpSessionWrapper(session, this);
    }
    return null;
}
```

> [!tip] Debug验证
> 在Controller中打断点，查看 `session` 对象的实际类型：
> - 不是 `org.apache.catalina.session.StandardSession` (Tomcat原生)
> - 而是 `SessionRepositoryFilter$HttpSessionWrapper` (Redis包装类)

### 请求处理完整流程

```
浏览器发请求 (POST /user/login)
    │
    │ Cookie: SESSION=7d375425... (或首次无Cookie)
    │
Tomcat接收请求 → Filter Chain:
    │
    ├─ SessionRepositoryFilter ★★★
    │   1. 从Cookie取sessionId
    │   2. sessionRepository.findById() → 从Redis查session
    │   3. 创建SessionRepositoryRequestWrapper (重写getSession)
    │   4. filterChain.doFilter(wrappedRequest, ...)
    │
    ├─ Controller执行
    │   session.setAttribute("user", user)
    │   ★ 实际修改的是RedisSession内部属性
    │
    ├─ SessionRepositoryFilter finally块
    │   commitSession() → sessionRepository.save()
    │   ★ 写入Redis 3个Key
    │   ★ 设置Cookie: SESSION=sessionId
    │
    └─ 返回响应给浏览器
```

---

## 五、Redis中3个Key的机制

### 3个Key的结构

一次 `session.setAttribute("user", user)` 后，Redis中产生3个Key：

#### Key1: `spring:session:sessions:{sessionId}` (Hash)

存储session的所有数据：

| Hash Field | Value | 说明 |
|-----------|-------|------|
| `creationTime` | JSON时间戳 | session创建时间 |
| `lastAccessedTime` | JSON时间戳 | 最后访问时间 |
| `maxInactiveInterval` | `1800` | 超时时间(秒) |
| `sessionAttr:user` | `{"id":1,"name":"zhangsan"}` | 存入的用户数据 |

#### Key2: `spring:session:sessions:expires:{sessionId}` (String)

值为空，唯一作用是设置 **TTL**（与session超时时间一致），让Redis自动感知session过期。

#### Key3: `spring:session:expirations:{timestamp}` (Set)

时间戳作为key名（按分钟分桶），存储同一分钟过期的所有sessionId。

### 为什么需要3个Key？

Redis TTL过期机制的缺陷：
- **惰性删除**：只有访问过期key时才检查删除，没人访问就永远不会删
- **定期删除**：每100ms随机抽查，可能漏掉

3-Key机制双重保险：
- **Key2 (TTL)** → Redis自动感知过期，触发keyspace notification
- **Key3 (Set桶)** → Spring Session后台定时任务每分钟扫描到期桶，主动清理

### Session生命周期

```
创建:
  → 写Key1(Hash数据)
  → 写Key2(TTL=1800秒)
  → 写Key3(加入过期桶)

访问(续期):
  → 更新Key1的lastAccessedTime
  → 重置Key2的TTL=1800秒
  → 从旧Key3移除，加入新Key3(新的过期时间点)

过期(30分钟无活动):
  → Key2 TTL到期，Redis自动删除Key2
  → 触发keyspace notification
  → Spring Session监听事件，删除Key1和Key3中的sessionId

兜底清理(定时任务):
  → 每分钟扫描Key3(expirations Set)
  → 检查Key2是否已被Redis删除
  → 已删除 → 清理Key1和Key3
```

### Key3为什么要用时间戳分桶？

```
expirations:1781516400000  ← 12:30过期的所有session
  { session-1, session-2, session-3 }

expirations:1781516460000  ← 12:31过期的所有session
  { session-4 }
```

好处：
1. 不需要扫描所有session，只扫描到期桶 → 高效
2. 同一分钟过期的session放一起 → 批量清理
3. session续期时从旧桶移到新桶 → 精确管理过期时间

---

## 六、redis-cli 操作命令

```bash
# 查看所有session相关key
KEYS spring:session:*

# 查看某个session的所有数据
HGETALL spring:session:sessions:{sessionId}

# 查看session中特定属性
HGET spring:session:sessions:{sessionId} sessionAttr:user

# 删除所有session数据(切换序列化器后必须执行)
FLUSHDB
```

配置JSON序列化后，`sessionAttr:user` 的值变为可读JSON：

```json
{"@class":"com.h3c.entity.User","id":1,"name":"zhangsan"}
```

> `@class` 字段是 `GenericJackson2JsonRedisSerializer` 自动添加的，用于反序列化时还原为正确的Java类型。

---

## 七、踩坑记录

| 问题 | 原因 | 解决 |
|------|------|------|
| 连接 `localhost:6379` 失败 | Spring Boot 2.x用 `spring.redis.*` 而非 `spring.data.redis.*` | 改为 `spring.redis.host/port` |
| NOAUTH认证错误 | Redis有密码但配置未填写 | 添加 `spring.redis.password` |
| JSON反序列化报错读旧数据 | 旧Java序列化数据与新JSON序列化器不兼容 | 清除Redis中旧session数据后重启 |

---

## 八、一句话总结

> Spring Session 用 Filter 把 HttpSession "偷换"成 RedisSession，所有session操作透明地读写Redis，实现多实例共享。Redis中用3个Key（Hash存数据 + TTL感知过期 + Set兜底清理）双重保险确保过期session被可靠清理。Spring Boot通过自动配置机制（spring.factories + @ConditionalOnClass），零代码即可生效。