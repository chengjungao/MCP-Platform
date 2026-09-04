<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type { LbStrategy, ServerView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { formatDuration, labelOf, LB_STRATEGY_LABEL } from '@/utils/format'
import { joinCsv, splitCsvNumbers, splitLines } from '@/utils/form'

const props = defineProps<{ serverId: number; server: ServerView }>()
const emit = defineEmits<{ saved: [ServerView] }>()

const auth = useAuthStore()
const saving = ref(false)

/**
 * `PUT /servers/{id}/upstream` 是整体替换，不是「null 表示不改」：
 * 没传的超时会被重置成后端默认值（连接 3s、读取 30s、重试 1 次、502/503/504）。
 * 所以这里必须回显全量、整体提交，不能套用覆盖面板的裁剪规则。
 */
const form = reactive({
  baseUrlsText: '',
  lbStrategy: 'ROUND_ROBIN' as LbStrategy,
  connectTimeoutMs: 3000,
  readTimeoutMs: 30000,
  retries: 1,
  retryOnStatusText: '502, 503, 504',
  cbFailureThreshold: 5,
  cbOpenMs: 30000,
  cbHalfOpenProbes: 2
})

watch(
  () => props.server,
  (value) => {
    const upstream = value.upstream
    form.baseUrlsText = (upstream?.baseUrls ?? []).join('\n')
    form.lbStrategy = upstream?.lbStrategy ?? 'ROUND_ROBIN'
    form.connectTimeoutMs = upstream?.connectTimeoutMs ?? 3000
    form.readTimeoutMs = upstream?.readTimeoutMs ?? 30000
    form.retries = upstream?.retries ?? 1
    form.retryOnStatusText = joinCsv(upstream?.retryOnStatus ?? [502, 503, 504])
    form.cbFailureThreshold = upstream?.circuitBreaker?.failureThreshold ?? 5
    form.cbOpenMs = upstream?.circuitBreaker?.openMs ?? 30000
    form.cbHalfOpenProbes = upstream?.circuitBreaker?.halfOpenProbes ?? 2
  },
  { immediate: true }
)

async function submit(): Promise<void> {
  const baseUrls = splitLines(form.baseUrlsText)
  if (baseUrls.length === 0) {
    ElMessage.warning('至少填写一个上游地址')
    return
  }
  const bad = baseUrls.filter((url) => !/^https?:\/\//i.test(url))
  if (bad.length > 0) {
    ElMessage.warning(`上游地址必须以 http:// 或 https:// 开头：${bad[0]}`)
    return
  }
  saving.value = true
  try {
    emit(
      'saved',
      await serverApi.updateUpstream(props.serverId, {
        baseUrls,
        lbStrategy: form.lbStrategy,
        connectTimeoutMs: form.connectTimeoutMs,
        readTimeoutMs: form.readTimeoutMs,
        retries: form.retries,
        retryOnStatus: splitCsvNumbers(form.retryOnStatusText),
        cbFailureThreshold: form.cbFailureThreshold,
        cbOpenMs: form.cbOpenMs,
        cbHalfOpenProbes: form.cbHalfOpenProbes
      })
    )
  } catch (error) {
    notifyError(error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-form label-width="130px" class="panel-form" @submit.prevent="submit">
    <el-form-item label="上游地址">
      <el-input
        v-model="form.baseUrlsText"
        type="textarea"
        :rows="3"
        placeholder="每行一个，例如 http://order-service:8080"
        :disabled="!auth.can('server:write')"
      />
      <div class="hint muted">
        一行一个。多个地址时由 Executor 按下面的策略选址；地址末尾的斜杠会被吃掉再拼接请求路径。
      </div>
    </el-form-item>

    <el-form-item label="负载均衡">
      <el-radio-group v-model="form.lbStrategy" :disabled="!auth.can('server:write')">
        <el-radio-button value="ROUND_ROBIN">{{ labelOf(LB_STRATEGY_LABEL, 'ROUND_ROBIN') }}</el-radio-button>
        <el-radio-button value="WEIGHTED">{{ labelOf(LB_STRATEGY_LABEL, 'WEIGHTED') }}</el-radio-button>
      </el-radio-group>
    </el-form-item>

    <el-alert v-if="form.lbStrategy === 'WEIGHTED'" type="warning" :closable="false" show-icon class="tip">
      <template #title>当前版本不落库权重，选 WEIGHTED 的实际效果等同轮询</template>
      <template #default>
        Manager 写入快照时 weights 恒为空数组，Executor 发现权重数量与地址数量不匹配后会退回轮询
        （这条退化路径有单元测试覆盖）。权重编辑随 P1 提供，现在请直接用轮询，免得配置与实际行为不一致。
      </template>
    </el-alert>

    <el-form-item label="连接超时">
      <el-input-number v-model="form.connectTimeoutMs" :min="100" :max="60000" :step="500" :disabled="!auth.can('server:write')" />
      <span class="unit muted">毫秒</span>
    </el-form-item>

    <el-form-item label="读取超时">
      <el-input-number v-model="form.readTimeoutMs" :min="100" :max="300000" :step="1000" :disabled="!auth.can('server:write')" />
      <span class="unit muted">毫秒</span>
    </el-form-item>

    <el-form-item label="重试次数">
      <el-input-number v-model="form.retries" :min="0" :max="5" :disabled="!auth.can('server:write')" />
      <span class="unit muted">0 表示不重试。只对下面的状态码与连接失败生效，不会重放已成功返回的请求。</span>
    </el-form-item>

    <el-form-item label="重试状态码">
      <el-input v-model="form.retryOnStatusText" placeholder="502, 503, 504" :disabled="!auth.can('server:write')" />
      <div class="hint muted">逗号分隔。留空表示任何状态码都不重试——注意这与「用默认值」不是一回事。</div>
    </el-form-item>

    <el-divider content-position="left">熔断</el-divider>

    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>熔断状态按 Executor 节点各自维护，不跨节点共享</template>
      <template #default>
        它度量的是「本节点到上游」这条链路的健康度。共享出去会让一个节点的网络抖动
        变成整个集群拒绝调用，而共享的收益（少几次失败探测）远小于这个代价。
      </template>
    </el-alert>

    <el-form-item label="失败阈值">
      <el-input-number v-model="form.cbFailureThreshold" :min="1" :max="100" :disabled="!auth.can('server:write')" />
      <span class="unit muted">连续失败达到该次数后打开熔断</span>
    </el-form-item>

    <el-form-item label="熔断保持">
      <el-input-number v-model="form.cbOpenMs" :min="1000" :max="600000" :step="1000" :disabled="!auth.can('server:write')" />
      <span class="unit muted">毫秒，约 {{ formatDuration(form.cbOpenMs) }}；到期转半开</span>
    </el-form-item>

    <el-form-item label="半开探测数">
      <el-input-number v-model="form.cbHalfOpenProbes" :min="1" :max="20" :disabled="!auth.can('server:write')" />
      <span class="unit muted">半开期间放行的探测请求数，全部成功才闭合；任一失败立刻重新打开</span>
    </el-form-item>

    <el-form-item>
      <el-button type="primary" :loading="saving" :disabled="!auth.can('server:write')" @click="submit">
        保存上游策略
      </el-button>
      <span v-if="!auth.can('server:write')" class="muted hint">当前账号没有 server:write 权限</span>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.panel-form {
  max-width: 760px;
}

.tip {
  margin-bottom: 16px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}

.unit {
  margin-left: 8px;
  font-size: 12px;
}
</style>