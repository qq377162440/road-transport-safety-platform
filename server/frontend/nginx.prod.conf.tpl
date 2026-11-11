# ============================================================================
# 生产 nginx 配置模板（E1）
# 由 render_nginx_conf.sh 把 __DOMAIN__ 替换后输出为 nginx.prod.conf 并挂载进容器。
# 使用 __DOMAIN__ 占位（而非 ${DOMAIN}），避免与 nginx 自身变量（$host 等）冲突。
#
# 生产证书放置：server/deploy/certs/fullchain.pem + privkey.pem
# ============================================================================

# ---- HTTP：仅用于 ACME 校验 / 健康探针，其余全部 301 到 HTTPS ----
server {
    listen 80;
    listen [::]:80;
    server_name __DOMAIN__;

    location = /healthz {
        access_log off;
        default_type text/plain;
        return 200 "ok";
    }

    # 证书续期（certbot webroot）按需放开
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

# ---- HTTPS 主站 ----
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name __DOMAIN__;

    ssl_certificate     /etc/nginx/certs/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers off;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 10m;
    ssl_session_tickets off;

    # 安全响应头
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=(self)" always;

    # 法规原文库支持大体积标准原文（JT/T 617 系列单册 22MB），放宽至 80m
    client_max_body_size 80m;
    root /usr/share/nginx/html;
    index index.html;
    access_log /var/log/nginx/access.log;
    error_log  /var/log/nginx/error.log warn;

    gzip on;
    gzip_min_length 1024;
    gzip_types text/plain text/css application/json application/javascript application/xml image/svg+xml;

    location = /healthz {
        access_log off;
        default_type text/plain;
        return 200 "ok";
    }

    # 带内容哈希的构建产物可长期缓存。
    # 注意：此处不能用 add_header —— nginx 中 location 一旦出现 add_header，
    # 就会阻断继承 server 级的所有 add_header（HSTS 等安全头会全部丢失）。
    # 统一改用 expires 指令设置缓存，保证安全响应头在全部 location 生效。
    location ~* \.(js|css|png|jpg|jpeg|gif|svg|woff2?|ttf|ico)$ {
        expires 30d;
        try_files $uri =404;
    }

    # SPA history 回退；index.html 不缓存，保证发版即时生效
    location / {
        try_files $uri $uri/ /index.html;
        expires -1;
    }

    # 后端 API（保留 /api 前缀）
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 10s;
        proxy_send_timeout 120s;
        proxy_read_timeout 120s;
    }

    # 后端健康检查（不暴露给公网的额外信息，仅返回 UP/DOWN）
    location = /health {
        proxy_pass http://backend:8080/health;
        proxy_set_header Host $host;
    }
}
