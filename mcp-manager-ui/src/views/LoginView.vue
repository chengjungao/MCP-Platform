<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'

const auth = useAuthStore()
const metaStore = useMetaStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit(): Promise<void> {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号与密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' && redirect ? redirect : '/')
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // /meta 是免鉴权的，登录页就能拿到协议版本，把 Modern-only 这条硬承诺摆在门口
  void metaStore.load()
})
</script>

<template>
  <div class="login-page">
    <!-- 科技感背景层（纯装饰，不参与交互） -->
    <div class="decor bg-grid" aria-hidden="true"></div>
    <div class="decor bg-floor" aria-hidden="true"></div>
    <div class="decor glow glow-a" aria-hidden="true"></div>
    <div class="decor glow glow-b" aria-hidden="true"></div>
    <div class="decor sweep" aria-hidden="true"></div>

    <div class="shell">
      <!-- 左侧：品牌与技术主张 -->
      <section class="side">
        <div class="side-body">
          <div class="brand">
            <span class="brand-mark">MCP</span>
            <span class="brand-name">BRIDGE PLATFORM</span>
          </div>

          <h1 class="headline">把企业 REST API 桥接成<br />可治理的 MCP Server</h1>
          <p class="intro">注册解析 → 覆盖精修 → 发布治理，一条链路面向 MCP 2026-07-28 协议。</p>

          <ul class="points">
            <li>
              <b><i>01</i> 注册解析</b>
              <span>Swagger 上传 / URL / 粘贴，diff 可审</span>
            </li>
            <li>
              <b><i>02</i> 覆盖精修</b>
              <span>base ⊕ overlay 合并，锚点挂起区可回溯</span>
            </li>
            <li>
              <b><i>03</i> 发布治理</b>
              <span>Auth-D · 审计 · 多实例负载 · 回滚</span>
            </li>
          </ul>
        </div>

        <p v-if="metaStore.meta" class="protocol">
          仅支持 MCP {{ metaStore.meta.supportedProtocolVersion }} · legacy 客户端显式拒绝（<span class="mono">-32022</span>）
          <a :href="metaStore.meta.upgradeGuideUrl" target="_blank" rel="noopener">升级指引 ↗</a>
        </p>
      </section>

      <!-- 右侧：登录表单 -->
      <section class="panel-side">
        <div class="panel">
          <h2>登录控制台</h2>
          <p class="panel-sub">使用部门账号进入管理端</p>

          <el-form label-position="top" @submit.prevent="submit">
            <el-form-item label="账号">
              <el-input
                v-model="form.username"
                placeholder="用户名"
                autocomplete="username"
                size="large"
                @keyup.enter="submit"
              />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="form.password"
                type="password"
                placeholder="密码"
                show-password
                autocomplete="current-password"
                size="large"
                @keyup.enter="submit"
              />
            </el-form-item>
            <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
              登录
            </el-button>
          </el-form>
        </div>
        <p class="copyright">MCP Bridge Platform · 企业内部控制台</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ===== 页面与背景 ===== */
.login-page {
  position: relative;
  display: flex;
  min-height: 100%;
  overflow: hidden;
  color: #dbeafe;
  background:
    radial-gradient(1100px 760px at 80% -12%, rgba(14, 74, 143, 0.55) 0%, transparent 62%),
    radial-gradient(860px 640px at 4% 108%, rgba(8, 96, 140, 0.4) 0%, transparent 58%),
    linear-gradient(155deg, #04101f 0%, #06182e 46%, #020812 100%);
}

.decor {
  position: absolute;
  pointer-events: none;
}

/* 细网格：中央偏亮、四周渐隐，形成数据面板感 */
.bg-grid {
  inset: 0;
  background-image:
    linear-gradient(rgba(64, 200, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(64, 200, 255, 0.06) 1px, transparent 1px);
  background-size: 38px 38px;
  -webkit-mask-image: radial-gradient(ellipse 88% 78% at 32% 28%, #000 22%, transparent 78%);
  mask-image: radial-gradient(ellipse 88% 78% at 32% 28%, #000 22%, transparent 78%);
}

/* 底部透视地板网格：向远处延伸 + 缓慢前进 */
.bg-floor {
  top: 46%;
  right: -18%;
  bottom: -14%;
  left: -18%;
  background-image:
    linear-gradient(rgba(56, 227, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 227, 255, 0.08) 1px, transparent 1px);
  background-size: 46px 46px;
  transform: perspective(640px) rotateX(62deg);
  transform-origin: 50% 0;
  animation: floor-move 22s linear infinite;
  -webkit-mask-image: linear-gradient(to bottom, #000 0%, #000 32%, transparent 86%);
  mask-image: linear-gradient(to bottom, #000 0%, #000 32%, transparent 86%);
}

@keyframes floor-move {
  from { background-position: 0 0; }
  to { background-position: 0 46px; }
}

/* 光晕呼吸 */
.glow {
  border-radius: 50%;
  filter: blur(90px);
}

.glow-a {
  top: -170px;
  left: -150px;
  width: 580px;
  height: 580px;
  background: radial-gradient(circle, rgba(22, 110, 190, 0.85) 0%, rgba(22, 110, 190, 0) 68%);
  animation: glow-a 12s ease-in-out infinite alternate;
}

.glow-b {
  top: 18%;
  right: -200px;
  width: 680px;
  height: 680px;
  opacity: 0.7;
  background: radial-gradient(circle, rgba(10, 126, 168, 0.6) 0%, rgba(10, 126, 168, 0) 70%);
  animation: glow-b 15s ease-in-out infinite alternate;
}

@keyframes glow-a {
  to { transform: translate(70px, 60px) scale(1.12); }
}

@keyframes glow-b {
  to { transform: translate(-80px, 50px) scale(0.88); }
}

/* 斜向扫光 */
.sweep {
  top: -30%;
  bottom: -30%;
  left: 0;
  width: 240px;
  background: linear-gradient(
    100deg,
    transparent 0%,
    rgba(80, 215, 255, 0.05) 42%,
    rgba(150, 245, 255, 0.09) 50%,
    rgba(80, 215, 255, 0.05) 58%,
    transparent 100%
  );
  transform: skewX(-12deg);
  animation: sweep-x 8s linear infinite;
}

@keyframes sweep-x {
  from { transform: translateX(-46vw) skewX(-12deg); }
  to { transform: translateX(130vw) skewX(-12deg); }
}

@media (prefers-reduced-motion: reduce) {
  .bg-floor,
  .glow-a,
  .glow-b,
  .sweep {
    animation: none;
  }
}

/* ===== 左右分栏骨架 ===== */
.shell {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: stretch;
  gap: 64px;
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: 0 6vw 0 7vw;
}

/* ===== 左侧品牌区 ===== */
.side {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: flex-start;
  min-width: 0;
  padding: 64px 0 32px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 34px;
}

.brand-mark {
  padding: 6px 11px;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 1px;
  color: #04121f;
  border-radius: 8px;
  background: linear-gradient(135deg, #46e6ff 0%, #3b82f6 100%);
  box-shadow: 0 0 20px rgba(56, 227, 255, 0.45);
}

.brand-name {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 3px;
  color: #7fb2d9;
}

.headline {
  margin: 0 0 16px;
  font-size: clamp(26px, 3vw, 36px);
  font-weight: 700;
  line-height: 1.32;
  letter-spacing: 0.5px;
  color: #eaf6ff;
}

.intro {
  margin: 0 0 38px;
  font-size: 14px;
  line-height: 1.8;
  color: #79a4c9;
}

.points {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.points li {
  padding-left: 52px;
  position: relative;
}

.points li::before {
  position: absolute;
  top: 2px;
  left: 0;
  width: 34px;
  height: 34px;
  content: '';
  border: 1px solid rgba(64, 200, 255, 0.35);
  border-radius: 8px;
  background: linear-gradient(150deg, rgba(64, 200, 255, 0.16) 0%, rgba(64, 200, 255, 0.02) 100%);
  box-shadow: inset 0 0 12px rgba(64, 200, 255, 0.08);
}

.points b {
  display: block;
  margin-bottom: 3px;
  font-size: 15px;
  font-weight: 600;
  color: #eaf6ff;
}

.points b i {
  margin-right: 8px;
  font-style: normal;
  font-size: 12px;
  font-weight: 700;
  color: #46e6ff;
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.points span {
  font-size: 13px;
  color: #7fa6c8;
}

.protocol {
  margin: 32px 0 0;
  font-size: 12px;
  color: #52799c;
}

.protocol .mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  color: #8fb8d8;
}

.protocol a {
  color: #46e6ff;
  text-decoration: none;
}

.protocol a:hover {
  text-decoration: underline;
}

/* ===== 右侧登录卡 ===== */
.panel-side {
  display: flex;
  flex: 0 0 400px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  padding: 48px 0 40px;
}

.panel {
  position: relative;
  width: 100%;
  padding: 40px 36px 34px;
  border: 1px solid rgba(96, 200, 255, 0.18);
  border-radius: 14px;
  background: rgba(7, 19, 38, 0.66);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}

/* 四角括号装饰（只画左上 / 右下两角，克制） */
.panel::before,
.panel::after {
  position: absolute;
  width: 16px;
  height: 16px;
  content: '';
  pointer-events: none;
}

.panel::before {
  top: -1px;
  left: -1px;
  border-top: 2px solid rgba(70, 230, 255, 0.7);
  border-left: 2px solid rgba(70, 230, 255, 0.7);
  border-radius: 14px 0 0 0;
}

.panel::after {
  right: -1px;
  bottom: -1px;
  border-right: 2px solid rgba(70, 230, 255, 0.7);
  border-bottom: 2px solid rgba(70, 230, 255, 0.7);
  border-radius: 0 0 14px 0;
}

.panel h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #eaf6ff;
}

.panel-sub {
  margin: 6px 0 26px;
  font-size: 13px;
  color: #6f96ba;
}

/* Element Plus 表单暗色化 */
.panel :deep(.el-form-item) {
  margin-bottom: 22px;
}

.panel :deep(.el-form-item__label) {
  font-size: 13px;
  font-weight: 500;
  color: #b7d4ec;
}

.panel :deep(.el-input__wrapper) {
  background-color: rgba(13, 32, 58, 0.55);
  box-shadow: 0 0 0 1px rgba(96, 200, 255, 0.2) inset;
  transition: box-shadow 0.2s ease;
}

.panel :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(96, 200, 255, 0.4) inset;
}

.panel :deep(.el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px rgba(70, 230, 255, 0.75) inset,
    0 0 14px rgba(70, 230, 255, 0.18);
}

.panel :deep(.el-input__inner) {
  color: #e6f6ff;
  caret-color: #46e6ff;
}

.panel :deep(.el-input__inner::placeholder) {
  color: rgba(150, 196, 228, 0.4);
}

.panel :deep(.el-input__password) {
  color: #8fc6e8;
}

.submit {
  width: 100%;
  height: 44px;
  margin-top: 4px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 4px;
  color: #04202f;
  border: none;
  border-radius: 8px;
  background: linear-gradient(90deg, #2fd0ff 0%, #37e0d0 100%);
  box-shadow: 0 8px 24px rgba(47, 208, 255, 0.28);
  transition: transform 0.15s ease, box-shadow 0.2s ease, filter 0.2s ease;
}

.submit:hover,
.submit:focus {
  color: #02161f;
  border: none;
  background: linear-gradient(90deg, #3dd8ff 0%, #4cecdb 100%);
  box-shadow: 0 10px 30px rgba(47, 208, 255, 0.4);
  transform: translateY(-1px);
}

.copyright {
  margin: 0;
  font-size: 12px;
  letter-spacing: 1px;
  color: #40617f;
}

/* ===== 窄屏降级：只留右侧卡片，居中 ===== */
@media (max-width: 960px) {
  .side {
    display: none;
  }

  .shell {
    gap: 0;
    justify-content: center;
    padding: 0 24px;
  }

  .panel-side {
    flex: 0 1 420px;
  }
}
</style>
