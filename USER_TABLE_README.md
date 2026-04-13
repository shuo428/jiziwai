# 用户表使用说明

## 概述

已成功创建用户表及相关功能，包含用户注册、登录、密码加密等完整功能。

## 数据库表结构

### t_user 表

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | BIGSERIAL | 用户ID (主键，自增) |
| username | VARCHAR(50) | 用户名 (唯一) |
| password | VARCHAR(255) | 密码 (BCrypt 加密) |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 (自动更新) |
| deleted | BOOLEAN | 逻辑删除标记 |

## 密码加密

使用 **BCrypt** 算法进行密码加密，具有以下特点：

- ✅ 单向加密，无法解密
- ✅ 每次加密结果不同，防止彩虹表攻击
- ✅ 自动加盐，安全性高
- ✅ 工业标准算法

示例代码：

```java
// 加密密码
String encrypted = PasswordUtil.encode("123456");

// 验证密码
boolean isMatch = PasswordUtil.matches("123456", encrypted);
```

## 配置说明

### 1. 数据库配置

修改 `application.properties` 中的数据库连接信息：

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/jiziwai
spring.datasource.username=postgres
spring.datasource.password=your_password
```

### 2. 创建数据库表

执行 SQL 脚本：[schema.sql](file:///d:/用户/Code/jiziwai/springboot-jni/src/main/resources/db/schema.sql)

```sql
-- 在 PostgreSQL 中执行
\i src/main/resources/db/schema.sql
```

或使用数据库管理工具导入该文件。

## API 接口

### 1. 用户注册

**POST** `/api/user/register`

请求体：
```json
{
  "username": "testuser",
  "password": "123456"
}
```

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "username": "testuser",
    "createdAt": "2025-11-27T18:26:13",
    "updatedAt": "2025-11-27T18:26:13",
    "deleted": false
  }
}
```

### 2. 用户登录

**POST** `/api/user/login`

请求体：
```json
{
  "username": "testuser",
  "password": "123456"
}
```

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "user": {
      "id": 1,
      "username": "testuser",
      "createdAt": "2025-11-27T18:26:13",
      "updatedAt": "2025-11-27T18:26:13",
      "deleted": false
    },
    "token": "your-satoken-here"
  }
}
```

### 3. 获取用户信息

**GET** `/api/user/info`

请求头：
```
satoken: your-token
```

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "username": "testuser",
    "createdAt": "2025-11-27T18:26:13",
    "updatedAt": "2025-11-27T18:26:13",
    "deleted": false
  }
}
```

### 4. 修改密码

**POST** `/api/user/changePassword`

请求头：
```
satoken: your-token
```

请求体：
```json
{
  "oldPassword": "123456",
  "newPassword": "654321"
}
```

### 5. 退出登录

**POST** `/api/user/logout`

请求头：
```
satoken: your-token
```

## 项目文件列表

### 核心文件

- [User.java](file:///d:/用户/Code/jiziwai/springboot-jni/src/main/java/springbootjni/entity/User.java) - 用户实体类
- [UserMapper.java](file:///d:/用户/Code/jiziwai/springboot-jni/src/main/java/springbootjni/mapper/UserMapper.java) - Mapper 接口
- [UserMapper.xml](file:///d:/用户/Code/jiziwai/springboot-jni/src/main/resources/mappers/UserMapper.xml) - MyBatis XML 配置
- [UserService.java](file:///d:/用户/Code/jiziwai/springboot-jni/src/main/java/springbootjni/service/UserService.java) - 业务逻辑层
- [UserController.java](file:///d:/用户/Code/jiziwai/springboot-jni/src/main/java/springbootjni/controller/UserController.java) - REST API 控制器
- [PasswordUtil.java](file:///d:/用户/Code/jiziwai/springboot-jni/src/main/java/springbootjni/util/PasswordUtil.java) - 密码加密工具

### 配置文件

- [schema.sql](file:///d:/用户/Code/jiziwai/springboot-jni/src/main/resources/db/schema.sql) - 数据库建表脚本
- [pom.xml](file:///d:/用户/Code/jiziwai/springboot-jni/pom.xml) - Maven 依赖配置
- [application.properties](file:///d:/用户/Code/jiziwai/springboot-jni/src/main/resources/application.properties) - 应用配置

## 使用步骤

### 1. 安装 PostgreSQL

确保已安装 PostgreSQL 数据库，并创建数据库：

```sql
CREATE DATABASE jiziwai;
```

### 2. 执行建表脚本

运行 `schema.sql` 创建用户表和触发器。

### 3. 更新 Maven 依赖

```bash
mvn clean install
```

### 4. 启动应用

```bash
mvn spring-boot:run
```

### 5. 测试接口

使用 Postman 或 curl 测试接口：

```bash
# 注册用户
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'

# 登录
curl -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'
```

## 安全特性

1. **密码加密存储** - 使用 BCrypt 算法
2. **逻辑删除** - 软删除，不物理删除数据
3. **时间自动更新** - 使用数据库触发器
4. **Sa-Token 认证** - 基于 token 的身份验证
5. **密码字段保护** - 返回用户信息时自动清空密码字段

## 注意事项

> [!IMPORTANT]
> 请务必修改 `application.properties` 中的数据库密码，不要使用默认密码。

> [!WARNING]
> 密码长度至少 6 位，建议包含大小写字母、数字和特殊字符。

> [!TIP]
> BCrypt 加密性能较慢，这是为了增加暴力破解难度，属于正常现象。
