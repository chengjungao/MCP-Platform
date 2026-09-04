# ============================================================
#  MCP Bridge Platform - Maven 构建入口（PowerShell）
#  强制使用项目内 .mvn\settings.xml（HTTPS 镜像），避免机器全局
#  settings 中的 HTTP 镜像被 Maven 3.9 的 http-blocker 拦截。
#
#  用法：
#    .\build.ps1 clean package
#    .\build.ps1 -pl mcp-manager -am spring-boot:run
# ============================================================
$ErrorActionPreference = 'Stop'
$settings = Join-Path $PSScriptRoot '.mvn\settings.xml'
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Error '未在 PATH 中找到 mvn，请先安装 Maven 3.9+ 并配置 PATH。'
    exit 1
}
& mvn -s $settings -gs $settings @args
exit $LASTEXITCODE
