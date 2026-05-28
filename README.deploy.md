# 阿里云 ECS 部署方案（Docker MySQL 版本）

## 服务器信息

| 项目 | 值 |
|------|-----|
| 区域 | 华东2（上海）E |
| 公网 IP | 47.116.34.6 |
| 系统 | Alibaba Cloud Linux 3.2104 LTS 64位 |
| 配置 | 2 核 vCPU / 2 GiB 内存 |
| 实例 ID | i-uf61ycyu59jp7vrsq35l |

> 注意：2GB 内存较为紧张，需限制 JVM 内存占用。

## 执行位置说明（重要）

文档中每段代码块上方都有 **执行位置标签**：

| 标签 | 含义 | 在哪里执行 |
|------|------|------------|
| `[本地 PowerShell]` | 你自己电脑上的 PowerShell 终端 | Windows 本机，工作目录 `c:\Users\07222\java` |
| `[阿里云控制台]` | 阿里云网页控制台 | 浏览器中点击操作，不是命令 |
| `[服务器 root]` | ECS 上以 root 用户登录后执行 | SSH 登录到 47.116.34.6 |
| `[服务器 MySQL]` | 在 MySQL 客户端中执行 | 通过 `docker exec` 进入 mysql 命令行 |

---

## 部署架构

```
            浏览器
              │
              ▼
   ┌──────────────────────┐
   │  阿里云 ECS 47.116.34.6 │
   │  ┌────────────────┐   │
   │  │ Nginx :80      │   │  ← 静态文件 + /api 反向代理
   │  └────────┬───────┘   │
   │           │           │
   │  ┌────────▼───────┐   │
   │  │ Spring Boot    │   │  ← java -jar (systemd 管理)
   │  │ :8080          │   │
   │  └────────┬───────┘   │
   │           │           │
   │  ┌────────▼───────┐   │
   │  │ Docker MySQL   │   │  ← 1Panel 部署的 MySQL 9.0.1
   │  │ :13306→3306   │   │
   │  └────────────────┘   │
   └──────────────────────┘
```

---

## Task 1：阿里云控制台准备

### 1.1 配置安全组

> **执行位置：[阿里云控制台]** → ECS → 安全组 → 配置规则
> 网页操作，不是命令。开放以下入方向端口：

| 端口 | 协议 | 授权对象 | 用途 |
|------|------|----------|------|
| 22   | TCP  | 你的本地公网IP/32 | SSH（建议限制来源IP） |
| 80   | TCP  | 0.0.0.0/0 | HTTP 网站访问 |
| 443  | TCP  | 0.0.0.0/0 | HTTPS（备案后启用） |

> MySQL 13306 端口**不要**对外开放，仅限本机访问。

### 1.2 重置 ECS 密码并远程连接

> **执行位置：[阿里云控制台]** 点击「重置密码」→ 重启实例 → 再点击「远程连接」用 Workbench 登录
> 或者在本地 PowerShell 中 SSH 登录：

```powershell
# [本地 PowerShell] 执行
# 通过 SSH 连接到阿里云 ECS（输入控制台设置的 root 密码）
ssh root@47.116.34.6
```

> 后续 Task 2 ~ Task 5 的所有 `[服务器 ...]` 命令都在这个 SSH 会话里执行。

---

## Task 2：服务器环境初始化

### 2.1 系统更新与用户/Swap 配置

```bash
# [服务器 root] 执行 ↓↓↓

# 更新系统所有软件包到最新版本
dnf update -y

# 创建一个普通用户 deploy，后续用它启动后端服务（避免 root 跑业务进程）
useradd -m -s /bin/bash deploy
# 给 deploy 设置密码（按提示输入两次）
passwd deploy
# 把 deploy 加入 wheel 组，使其可以 sudo
usermod -aG wheel deploy

# 添加 2GB Swap（2GB 内存机器强烈建议，防止编译/运行时 OOM）
# 如果之前已添加过 Swap，先检查当前状态
free -h
# 如果 Swap 已经是 3GB，说明已有，跳过下面这行
fallocate -l 2G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=2048
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
free -h
```

### 2.2 安装 JDK 17

```bash
# [服务器 root] 执行 ↓↓↓

# 安装 OpenJDK 17（运行 + 编译都装上）
dnf install -y java-17-openjdk java-17-openjdk-devel

# 验证：应输出 openjdk 17.x.x
java -version
```

### 2.3 复用现有的 Docker MySQL（无需重新安装）

你的服务器上已有 1Panel 部署的 Docker MySQL 9.0.1，**直接复用**，不需要再安装系统级 MySQL。

```bash
# [服务器 root] 执行 ↓↓↓

# 确认 Docker MySQL 容器正在运行
docker ps | grep mysql

# 查看容器信息（确认端口映射和数据卷）
docker inspect mysql_hznn-mysql_Hznn-1 | grep -E '"HostPort"|"Source"'
```

正常应看到：
- 端口：`0.0.0.0:13306→3306/tcp`
- 数据卷：映射到宿主机某个目录

### 2.4 获取 Docker MySQL 的 root 密码

```bash
# [服务器 root] 执行 ↓↓↓

# 从 Docker 容器的环境变量中获取 root 密码
docker inspect mysql_hznn-mysql_Hznn-1 | grep MYSQL_ROOT_PASSWORD
```

输出类似：
```
"MYSQL_ROOT_PASSWORD=tFcG6BBshZnLJyxb"
```

**记下这个密码**，后续连接 MySQL 需要。

### 2.5 创建业务数据库和账号

```bash
# [服务器 root] 用 root 密码连接 Docker MySQL
docker exec -it mysql_hznn-mysql_Hznn-1 mysql -uroot -p'tFcG6BBshZnLJyxb'
```

> 注意：把 `'tFcG6BBshZnLJyxb'` 替换成你查到的真实密码。

进入 `mysql>` 后：

```sql
-- [服务器 MySQL] 执行 ↓↓↓

-- 创建业务数据库（utf8mb4 支持 emoji）
CREATE DATABASE demo_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建应用专用账号（% 表示允许任何地址连接，Docker 网络需要）
-- 把密码改成你自己的强密码
CREATE USER 'demo'@'%' IDENTIFIED BY 'Demo@2026Pwd!';

-- 授权 demo 账号操作 demo_db 库
GRANT ALL PRIVILEGES ON demo_db.* TO 'demo'@'%';

-- 让权限立即生效，然后退出
FLUSH PRIVILEGES;
EXIT;
```

### 2.6 验证数据库连接

```bash
# [服务器 root] 从宿主机通过 TCP 连 Docker MySQL 验证
mysql -udemo -p'Demo@2026Pwd!' -h 127.0.0.1 -P 13306 demo_db -e "SELECT 'OK';"
```

> 注意：把 `'Demo@2026Pwd!'` 替换成你创建用户时设置的密码。

如果输出：
```
+----+
| OK |
+----+
| OK |
+----+
```
说明数据库配置成功。

### 2.7 安装 Nginx

```bash
# [服务器 root] 执行 ↓↓↓

# 安装 Nginx 并设置开机自启
dnf install -y nginx
systemctl enable --now nginx

# 此时浏览器访问 http://47.116.34.6 应该能看到 Nginx 默认欢迎页
```

---

## Task 3：后端部署

### 3.1 本地打包

```powershell
# [本地 PowerShell] 执行 ↓↓↓

# 进入后端项目目录
cd c:\Users\07222\java\backend

# Maven 打包，跳过单元测试以加快速度
# 产物在 target\demo-0.0.1-SNAPSHOT.jar
mvn clean package -DskipTests
```

### 3.2 在服务器上准备目录

```bash
# [服务器 root] 执行 ↓↓↓

# 创建后端部署目录及日志目录
mkdir -p /opt/demo/logs

# 把目录所有权交给 deploy 用户（systemd 服务用 deploy 启动）
chown -R deploy:deploy /opt/demo
```

### 3.3 上传 JAR 到服务器

```powershell
# [本地 PowerShell] 执行 ↓↓↓

# 用 scp 把打包好的 jar 上传到服务器 /opt/demo/ 目录
# 会提示输入 root 密码
scp c:\Users\07222\java\backend\target\demo-0.0.1-SNAPSHOT.jar root@47.116.34.6:/opt/demo/
```

### 3.4 创建生产配置文件

```bash
# [服务器 root] 执行 ↓↓↓

# 用 here-doc 把生产配置写到 /opt/demo/application-prod.yml
# 注意：
# 1. 数据库端口是 13306（Docker 映射的端口）
# 2. 密码改成你 Task 2.5 创建 demo 用户时设置的密码
cat > /opt/demo/application-prod.yml <<'EOF'
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:13306/demo_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf-8
    username: demo
    password: Demo@2026Pwd!
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect

logging:
  file:
    name: /opt/demo/logs/app.log
  level:
    root: INFO
EOF

# 配置文件归 deploy 所有
chown deploy:deploy /opt/demo/application-prod.yml
# 限制权限（含数据库密码）
chmod 600 /opt/demo/application-prod.yml
```

> **重要**：把 `password: Demo@2026Pwd!` 改成你实际设置的密码。

### 3.5 配置 systemd 服务

```bash
# [服务器 root] 执行 ↓↓↓

# 写入 systemd 服务定义文件
cat > /etc/systemd/system/demo-backend.service <<'EOF'
[Unit]
Description=Demo Backend Spring Boot
After=network.target

[Service]
Type=simple
User=deploy
WorkingDirectory=/opt/demo
# 2GB 内存机器，限制 JVM 堆内存最多 512M
ExecStart=/usr/bin/java -Xms256m -Xmx512m \
  -Dspring.profiles.active=prod \
  -Dspring.config.additional-location=/opt/demo/application-prod.yml \
  -jar /opt/demo/demo-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=10
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF

# 让 systemd 重新加载配置
systemctl daemon-reload

# 设置开机自启 + 立即启动后端服务
systemctl enable --now demo-backend

# 查看服务状态（应显示 active (running)）
systemctl status demo-backend

# 实时查看启动日志（确认无报错，Ctrl+C 退出查看）
journalctl -u demo-backend -f
```

### 3.6 验证后端

```bash
# [服务器 root] 执行 ↓↓↓

# 在服务器本机访问后端接口，应返回 JSON 分页数据
curl http://localhost:8080/api/items
```

---

## Task 4：前端部署

### 4.1 本地构建

```powershell
# [本地 PowerShell] 执行 ↓↓↓

# 进入前端项目目录
cd c:\Users\07222\java\frontend

# 安装依赖（已装可跳过）
npm ci

# 打包生产版本，产物在 dist\ 目录
npm run build
```

> 前端通过 Nginx 反向代理 `/api` 到后端，无需修改 `api/item.js` 中的 baseURL。

### 4.2 上传到服务器

```powershell
# [本地 PowerShell] 执行 ↓↓↓

# 把 dist 目录下所有文件上传到服务器 Nginx 站点目录
scp -r c:\Users\07222\java\frontend\dist\* root@47.116.34.6:/usr/share/nginx/html/
```

### 4.3 配置 Nginx

```bash
# [服务器 root] 执行 ↓↓↓

# 写入站点配置文件 /etc/nginx/conf.d/demo.conf
cat > /etc/nginx/conf.d/demo.conf <<'EOF'
server {
    listen 80;
    server_name 47.116.34.6;

    root /usr/share/nginx/html;
    index index.html;

    # SPA 路由支持：刷新子路由不会 404
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 把 /api/ 反向代理到本机 8080 的 Spring Boot
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }

    # 静态资源浏览器缓存 7 天
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2)$ {
        expires 7d;
        add_header Cache-Control "public, immutable";
    }

    access_log /var/log/nginx/demo-access.log;
    error_log  /var/log/nginx/demo-error.log;
}
EOF

# 删除 Nginx 默认欢迎页配置（可选，避免干扰）
# rm -f /etc/nginx/conf.d/default.conf

# 检查 Nginx 配置语法
nginx -t

# 配置 OK 则平滑重载（不中断服务）
systemctl reload nginx
```

### 4.4 访问验证

> **执行位置：本地浏览器**
> 打开 http://47.116.34.6 ，应能看到 CRUD 管理页面，新增/查询/删除均可正常操作。

---

## Task 5：HTTPS 与域名（可选）

### 5.1 域名解析

> **执行位置：[阿里云控制台]** → 域名 → 解析设置
> 添加一条 A 记录指向 47.116.34.6：

| 主机记录 | 类型 | 值 |
|---------|------|-----|
| @ 或 www | A | 47.116.34.6 |

> 国内服务器对外提供 80/443 服务必须先完成 **ICP 备案**。

### 5.2 申请并安装 SSL 证书

**方式 A：阿里云免费 SSL（推荐，控制台操作）**

> **执行位置：[阿里云控制台]** → SSL 证书 → 申请免费证书 → 下载 Nginx 版本
> 然后用 scp 上传到服务器并修改 `demo.conf` 引用证书路径。

**方式 B：Let's Encrypt（命令行自动配置）**

```bash
# [服务器 root] 执行 ↓↓↓

# 安装 certbot（含 nginx 插件）
dnf install -y certbot python3-certbot-nginx

# 自动签发证书并修改 Nginx 配置开启 HTTPS
# 把 yourdomain.com 替换为你已解析到本机的真实域名
certbot --nginx -d yourdomain.com

# certbot 会自动添加定时任务续期，无需额外操作
```

---

## Task 6：日常运维

### 6.1 后端更新流程

```powershell
# [本地 PowerShell] 执行 ↓↓↓
cd c:\Users\07222\java\backend
mvn clean package -DskipTests
scp target\demo-0.0.1-SNAPSHOT.jar root@47.116.34.6:/opt/demo/
```

```bash
# [服务器 root] 执行 ↓↓↓
# 重启后端服务（systemd 会平滑重启）
systemctl restart demo-backend
# 观察启动日志
journalctl -u demo-backend -f
```

### 6.2 前端更新流程

```powershell
# [本地 PowerShell] 执行 ↓↓↓
cd c:\Users\07222\java\frontend
npm run build
scp -r dist\* root@47.116.34.6:/usr/share/nginx/html/
# 静态文件不需要重启 Nginx，浏览器强刷即可（Ctrl+F5）
```

### 6.3 Docker MySQL 运维

```bash
# [服务器 root] 执行 ↓↓↓

# 查看 Docker MySQL 容器状态
docker ps | grep mysql

# 进入 MySQL 命令行
docker exec -it mysql_hznn-mysql_Hznn-1 mysql -uroot -p'tFcG6BBshZnLJyxb'

# 查看 MySQL 日志
docker logs mysql_hznn-mysql_Hznn-1 --tail 100

# 重启 Docker MySQL（如果改了配置需要重启）
docker restart mysql_hznn-mysql_Hznn-1

# 备份数据库
mysqldump -uroot -p'tFcG6BBshZnLJyxb' -h 127.0.0.1 -P 13306 demo_db > /opt/demo/backup.sql
```

### 6.4 常用排障命令

```bash
# [服务器 root] 执行 ↓↓↓

# —— 后端 ——
systemctl status demo-backend                    # 服务状态
journalctl -u demo-backend -n 200 --no-pager     # 最近 200 行日志
tail -f /opt/demo/logs/app.log                   # 实时业务日志

# —— Nginx ——
tail -f /var/log/nginx/demo-access.log           # 访问日志
tail -f /var/log/nginx/demo-error.log            # 错误日志

# —— 端口 ——
ss -tlnp | grep -E '80|8080|13306'               # 检查端口监听

# —— 资源 ——
free -h                                           # 内存与 Swap
ps -p $(pgrep -f demo-0.0.1) -o pid,rss,vsz,cmd  # Java 进程内存

# —— Docker ——
docker ps                                         # 查看运行中的容器
docker stats mysql_hznn-mysql_Hznn-1             # MySQL 容器资源占用
```

---

## 安全加固建议

> 以下为推荐项，按需在 **[服务器 root]** 上执行。

1. **禁止 root 远程登录**：`vi /etc/ssh/sshd_config` 改 `PermitRootLogin no` → `systemctl restart sshd`，之后用 deploy 用户登录再 sudo。
2. **启用 SSH 密钥登录**：本地生成密钥 → `ssh-copy-id` 后禁用密码登录（`PasswordAuthentication no`）。
3. **MySQL 13306 不对外开放**：安全组不开放 13306 端口，仅限本机访问。
4. **数据库密码强度**：使用至少 16 位包含大小写字母、数字、特殊符号的密码。
5. **定期备份**：`mysqldump` + 阿里云 OSS 异地备份；或开通 RDS 替代 Docker MySQL。
6. **关闭 JPA 自动建表**：上线稳定后将 `ddl-auto: update` 改为 `validate`，用 Flyway/Liquibase 管理 Schema。
7. **配置阿里云云盾**：开启免费 DDoS 基础防护、Web 应用防火墙试用。

---

## 升级建议（业务增长后）

| 当前 | 推荐升级 |
|------|---------|
| Docker 自建 MySQL | 阿里云 **RDS for MySQL**（自动备份、高可用） |
| 单台 ECS | ECS + **SLB 负载均衡**，多实例部署后端 |
| 前端 Nginx 静态托管 | **OSS 静态网站托管 + CDN 加速** |
| 手动部署 | 阿里云 **云效 Flow** / Jenkins 自动化 CI/CD |
| 单机日志 | **SLS 日志服务** 集中收集分析 |
