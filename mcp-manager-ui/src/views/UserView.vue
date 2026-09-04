<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import * as orgApi from '@/api/org'
import type { RoleView, UserCreateRequest, UserUpdateRequest, UserView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { flattenDepartments, type DepartmentOption } from '@/utils/departments'
import { formatDateTime } from '@/utils/format'

const auth = useAuthStore()

const rows = ref<UserView[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ page: 0, size: 20 })

const roles = ref<RoleView[]>([])
const departmentOptions = ref<DepartmentOption[]>([])
const optionHint = ref('')

const dialog = reactive({
  visible: false,
  saving: false,
  id: null as number | null,
  form: {
    username: '',
    password: '',
    displayName: '',
    email: '',
    deptId: undefined as number | undefined,
    roleCodes: [] as string[],
    enabled: true
  }
})

const canWrite = computed(() => auth.can('user:write'))
const isEdit = computed(() => dialog.id != null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await orgApi.users({ page: query.page, size: query.size })
    rows.value = result.items
    total.value = result.total
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

/**
 * 角色与部门两个下拉各自独立失败。
 *
 * 管账号的人不一定有 role:read / dept:read，此时表格照常可用，
 * 只是新建账号时选不了角色——把这种情况如实说明，比整个页面报错强。
 */
async function loadOptions(): Promise<void> {
  const hints: string[] = []
  const [roleResult, deptResult] = await Promise.allSettled([
    auth.can('role:read') ? orgApi.roles() : Promise.resolve(null),
    auth.can('dept:read') ? orgApi.departmentTree() : Promise.resolve(null)
  ])
  roles.value = roleResult.status === 'fulfilled' && roleResult.value ? roleResult.value : []
  departmentOptions.value =
    deptResult.status === 'fulfilled' && deptResult.value ? flattenDepartments(deptResult.value) : []
  if (roles.value.length === 0) hints.push('角色列表')
  if (departmentOptions.value.length === 0) hints.push('部门列表')
  optionHint.value = hints.length > 0 ? `拿不到${hints.join('与')}，相关下拉为空` : ''
}

function onPageChange(page: number): void {
  query.page = page - 1
  void load()
}

function openCreate(): void {
  dialog.id = null
  dialog.form.username = ''
  dialog.form.password = ''
  dialog.form.displayName = ''
  dialog.form.email = ''
  dialog.form.deptId = undefined
  dialog.form.roleCodes = []
  dialog.form.enabled = true
  dialog.visible = true
}

function openEdit(row: UserView): void {
  dialog.id = row.id
  dialog.form.username = row.username
  dialog.form.password = ''
  dialog.form.displayName = row.displayName ?? ''
  dialog.form.email = row.email ?? ''
  dialog.form.deptId = row.deptId
  dialog.form.roleCodes = [...row.roles]
  dialog.form.enabled = row.enabled
  dialog.visible = true
}

async function submit(): Promise<void> {
  const password = dialog.form.password
  if (!isEdit.value) {
    if (!dialog.form.username.trim()) {
      ElMessage.warning('请填写用户名')
      return
    }
    if (password.length < 8 || password.length > 64) {
      ElMessage.warning('密码长度需在 8~64 位之间')
      return
    }
  } else if (password && (password.length < 8 || password.length > 64)) {
    ElMessage.warning('密码长度需在 8~64 位之间，留空表示不修改')
    return
  }
  if (dialog.form.roleCodes.length === 0) {
    ElMessage.warning('至少分配一个角色，否则该账号登录后什么也做不了')
    return
  }

  dialog.saving = true
  try {
    if (dialog.id == null) {
      const request: UserCreateRequest = {
        username: dialog.form.username.trim(),
        password,
        displayName: dialog.form.displayName.trim() || undefined,
        email: dialog.form.email.trim() || undefined,
        deptId: dialog.form.deptId,
        roleCodes: dialog.form.roleCodes
      }
      await orgApi.createUser(request)
      ElMessage.success('账号已创建')
    } else {
      const request: UserUpdateRequest = {
        displayName: dialog.form.displayName.trim() || undefined,
        email: dialog.form.email.trim() || undefined,
        deptId: dialog.form.deptId,
        roleCodes: dialog.form.roleCodes,
        enabled: dialog.form.enabled,
        password: password || undefined
      }
      await orgApi.updateUser(dialog.id, request)
      ElMessage.success('账号已更新')
    }
    dialog.visible = false
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    dialog.saving = false
  }
}

onMounted(() => {
  void load()
  void loadOptions()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>账号</h2>
        <p class="subtitle">
          账号的数据可见范围由所属部门决定：非平台管理员只能看到本部门及其子部门的资源。
          角色决定能做什么，部门决定能看到哪些。
        </p>
      </div>
      <div class="toolbar">
        <el-button type="primary" :disabled="!canWrite" @click="openCreate">新建账号</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="username" label="用户名" width="150">
        <template #default="{ row }"><span class="mono">{{ row.username }}</span></template>
      </el-table-column>
      <el-table-column label="姓名" width="140">
        <template #default="{ row }">{{ row.displayName || '—' }}</template>
      </el-table-column>
      <el-table-column label="邮箱" min-width="200">
        <template #default="{ row }">{{ row.email || '—' }}</template>
      </el-table-column>
      <el-table-column label="部门" width="140">
        <template #default="{ row }">{{ row.deptName || '—' }}</template>
      </el-table-column>
      <el-table-column label="角色" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="code in row.roles" :key="code" size="small" effect="plain" class="chip mono">
            {{ code }}
          </el-tag>
          <span v-if="row.roles.length === 0" class="muted">无角色</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="170">
        <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" :disabled="!canWrite" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有账号" /></template>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.size"
      :current-page="query.page + 1"
      @current-change="onPageChange"
    />

    <el-dialog v-model="dialog.visible" :title="isEdit ? '编辑账号' : '新建账号'" width="560px">
      <el-form label-width="90px" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="dialog.form.username" maxlength="64" :disabled="isEdit" class="mono" />
          <div v-if="isEdit" class="hint muted">用户名是登录凭据的一部分，创建后不可修改。</div>
        </el-form-item>
        <el-form-item :label="isEdit ? '重置密码' : '密码'">
          <el-input
            v-model="dialog.form.password"
            type="password"
            show-password
            maxlength="64"
            :placeholder="isEdit ? '留空表示不修改' : '8~64 位'"
          />
          <div class="hint muted">
            平台只保存 BCrypt 哈希，无法找回明文；忘记密码的唯一办法是在这里重置。
          </div>
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="dialog.form.displayName" maxlength="64" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="dialog.form.email" maxlength="128" />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="dialog.form.deptId" clearable placeholder="留空表示平台级" style="width: 100%">
            <el-option
              v-for="option in departmentOptions"
              :key="option.id"
              :label="option.label"
              :value="option.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="dialog.form.roleCodes" multiple filterable placeholder="选择角色" style="width: 100%">
            <el-option v-for="role in roles" :key="role.code" :label="`${role.name}（${role.code}）`" :value="role.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isEdit" label="启用">
          <el-switch v-model="dialog.form.enabled" />
          <div class="hint muted">停用后该账号立即无法登录，已签发的令牌也会在过期前被拒绝。</div>
        </el-form-item>
      </el-form>
      <p v-if="optionHint" class="muted hint">{{ optionHint }}（可能缺少 role:read / dept:read 权限）。</p>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chip {
  margin: 2px 4px 2px 0;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}
</style>