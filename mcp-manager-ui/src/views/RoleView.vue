<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { notifyError } from '@/api/http'
import * as orgApi from '@/api/org'
import type { RoleView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'

const auth = useAuthStore()
const metaStore = useMetaStore()

const roles = ref<RoleView[]>([])
const catalog = ref<string[]>([])
const loading = ref(false)

const dialog = reactive({
  visible: false,
  saving: false,
  id: null as number | null,
  builtin: false,
  form: {
    code: '',
    name: '',
    description: '',
    permissions: [] as string[]
  }
})

const canWrite = computed(() => auth.can('role:write'))

/** 权限点按前缀分组，一屏能看全「这个角色在哪些域上有权」。 */
const groups = computed(() => {
  const buckets = new Map<string, string[]>()
  for (const permission of catalog.value) {
    const prefix = permission.split(':')[0] ?? 'other'
    const bucket = buckets.get(prefix)
    if (bucket) {
      bucket.push(permission)
    } else {
      buckets.set(prefix, [permission])
    }
  }
  return [...buckets.entries()].map(([prefix, items]) => ({ prefix, items }))
})

async function load(): Promise<void> {
  loading.value = true
  try {
    roles.value = await orgApi.roles()
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function loadCatalog(): Promise<void> {
  try {
    catalog.value = (await orgApi.permissions()).permissions
  } catch (error) {
    notifyError(error)
  }
}

function openCreate(): void {
  dialog.id = null
  dialog.builtin = false
  dialog.form.code = ''
  dialog.form.name = ''
  dialog.form.description = ''
  dialog.form.permissions = []
  dialog.visible = true
}

function openEdit(role: RoleView): void {
  dialog.id = role.id
  dialog.builtin = role.builtin
  dialog.form.code = role.code
  dialog.form.name = role.name
  dialog.form.description = role.description ?? ''
  dialog.form.permissions = [...role.permissions]
  dialog.visible = true
}

function toggleGroup(items: string[], checked: boolean): void {
  const set = new Set(dialog.form.permissions)
  for (const item of items) {
    if (checked) set.add(item)
    else set.delete(item)
  }
  dialog.form.permissions = [...set]
}

function groupState(items: string[]): boolean | 'indeterminate' {
  const selected = items.filter((item) => dialog.form.permissions.includes(item)).length
  if (selected === 0) return false
  return selected === items.length ? true : 'indeterminate'
}

async function submit(): Promise<void> {
  if (!dialog.form.name.trim()) {
    ElMessage.warning('请填写角色名')
    return
  }
  if (!dialog.builtin && !dialog.form.code.trim()) {
    ElMessage.warning('请填写角色编码')
    return
  }
  dialog.saving = true
  try {
    const request = {
      code: dialog.form.code.trim(),
      name: dialog.form.name.trim(),
      description: dialog.form.description.trim() || undefined,
      permissions: dialog.form.permissions
    }
    if (dialog.id == null) {
      await orgApi.createRole(request)
      ElMessage.success('角色已创建')
    } else {
      await orgApi.updateRole(dialog.id, request)
      ElMessage.success('角色已更新，相关账号下次请求即生效')
    }
    dialog.visible = false
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    dialog.saving = false
  }
}

async function remove(role: RoleView): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除角色 ${role.name}（${role.code}）。已有账号引用时后端会拒绝。`, '删除角色', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await orgApi.deleteRole(role.id)
    ElMessage.success('角色已删除')
    await load()
  } catch (error) {
    notifyError(error)
  }
}

onMounted(() => {
  void metaStore.load()
  void load()
  void loadCatalog()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>角色权限</h2>
        <p class="subtitle">
          权限点是鉴权的最小单位，角色只是权限点的命名集合。后端按
          <span class="mono">hasAuthority('权限点')</span> 判定，不按角色名判定。
        </p>
      </div>
      <div class="toolbar">
        <el-button type="primary" :disabled="!canWrite" @click="openCreate">新建角色</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>内置角色的编码不可改，也不能删除</template>
      <template #default>
        编码是代码里引用的常量（例如平台管理员判定依赖 <span class="mono">PLATFORM_ADMIN</span>），
        改掉会让鉴权规则静默失效；权限点本身可以调整。
        平台内置角色共 {{ metaStore.meta?.builtinRoles?.length ?? 0 }} 个，
        可分配的权限点全集来自 <span class="mono">GET /api/v1/permissions</span>。
      </template>
    </el-alert>

    <el-table v-loading="loading" :data="roles" border stripe>
      <el-table-column prop="code" label="编码" width="180">
        <template #default="{ row }">
          <span class="mono">{{ row.code }}</span>
          <el-tag v-if="row.builtin" type="warning" size="small" effect="plain">内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column prop="description" label="描述" min-width="220">
        <template #default="{ row }">{{ row.description || '—' }}</template>
      </el-table-column>
      <el-table-column label="权限点数" width="100" align="center">
        <template #default="{ row }">{{ row.permissions.length }}</template>
      </el-table-column>
      <el-table-column label="权限点" min-width="320">
        <template #default="{ row }">
          <el-tag
            v-for="permission in row.permissions"
            :key="permission"
            size="small"
            effect="plain"
            class="chip mono"
          >
            {{ permission }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" :disabled="!canWrite" @click="openEdit(row)">编辑</el-button>
          <el-button
            link
            type="danger"
            size="small"
            :disabled="!canWrite || row.builtin"
            @click="remove(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有角色" /></template>
    </el-table>

    <el-dialog v-model="dialog.visible" :title="dialog.id == null ? '新建角色' : '编辑角色'" width="720px" top="6vh">
      <el-form label-width="90px" @submit.prevent="submit">
        <el-form-item label="编码">
          <el-input
            v-model="dialog.form.code"
            maxlength="64"
            placeholder="大写，例如 DEPT_REVIEWER"
            :disabled="dialog.builtin"
          />
          <div v-if="dialog.builtin" class="hint muted">内置角色的编码不可修改。</div>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="dialog.form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="dialog.form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="权限点">
          <div class="perm-box">
            <div v-for="group in groups" :key="group.prefix" class="perm-group">
              <el-checkbox
                :model-value="groupState(group.items) === true"
                :indeterminate="groupState(group.items) === 'indeterminate'"
                @change="(value: boolean | string | number) => toggleGroup(group.items, value === true)"
              >
                <strong>{{ group.prefix }}</strong>
              </el-checkbox>
              <el-checkbox-group v-model="dialog.form.permissions" class="perm-items">
                <el-checkbox v-for="permission in group.items" :key="permission" :value="permission">
                  <span class="mono small">{{ permission }}</span>
                </el-checkbox>
              </el-checkbox-group>
            </div>
            <p v-if="groups.length === 0" class="muted hint">拿不到权限点全集。</p>
          </div>
          <div class="hint muted">
            已选 {{ dialog.form.permissions.length }} / {{ catalog.length }} 个权限点。
            前端隐藏入口只是体验优化，真正的判定在后端；给多了不会报错，只会让人看到不该看的东西。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.chip {
  margin: 2px 4px 2px 0;
}

.perm-box {
  width: 100%;
  max-height: 320px;
  padding: 4px 8px;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.perm-group {
  padding: 6px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.perm-group:last-child {
  border-bottom: none;
}

.perm-items {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  padding-left: 24px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}
</style>