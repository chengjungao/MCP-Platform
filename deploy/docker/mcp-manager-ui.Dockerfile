# 控制台镜像：纯静态资源 + nginx 同源反代。
#
# 与后端一致的约定：不在镜像里跑 npm ci / vite build。前端构建对 Node 版本与
# 网络镜像源都很敏感，塞进 docker build 会让「改一行 CSS」变成一次几分钟的完整安装。
# 先在宿主机 npm run build 产出 dist，镜像只负责发布与反代。
FROM nginx:1.27-alpine

COPY deploy/docker/ui-nginx.conf /etc/nginx/conf.d/default.conf
COPY mcp-manager-ui/dist          /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://localhost/ || exit 1