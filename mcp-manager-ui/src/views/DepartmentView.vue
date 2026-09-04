<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { notifyError } from '@/api/http'
import * as orgApi from '@/api/org'
import type { DepartmentRequest, DepartmentView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { flattenDepartments, type DepartmentOption } from '@/utils/departments'

const auth = useAuthStore()

const tree = ref<DepartmentView[]>([])
const options = ref<DepartmentOption[]>([])
const loading = ref(false)

const dialog = reactive({
  visible: false,
  saving: false,
  id: null as number | null,
  form: {
    name: '',
    parentId: undefined as number | undefined,
    description: '',
    enabled: true
  }
})

const canWrite = computed(() => auth.can('dept:write'))

async function load(): Promise<void> {
  loading.value = true
  try {
    tree.value = await orgApi.departmentTree()
    options.value = flattenDepartments(tree.value)
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function openCreate(parent?: DepartmentView): void {
  dialog.id = null
  dialog.form.name = ''
  dialog.form.parentId = parent?.id
  dialog.form.description = ''
  dialog.form.enabled = true
  dialog.visible = true
}

function openEdit(row: DepartmentView): void {
  dialog.id = row.id
  dialog.form.name = row.name
  dialog.form.parentId = row.parentId
  dialog.form.description = row.description ?? ''
  dialog.form.enabled = row.enabled
  dialog.visible = true
}

/** 编辑时的父级候选要排除自己与自己的整棵子树，否则能配出环。 */
const parentOptions = computed(() =>
  dialog.id == null ? options.value : flattenDepartments(tree.value, 0, dialog.id)
)

async function submit(): Promise<void> {
  if (!dialog.form.name.trim()) {
    ElMessage.warning('请填写部门名')
    return
  }
  const request: DepartmentRequest = {
    name: dialog.form.name.trim(),
    parentId: dialog.form.parentId ?? null,
    description: dialog.form.description.trim() || undefined,
    enabled: dialog.form.enabled
  }
  dialog.saving = true
  try {
    if (dialog.id == null) {
      await orgApi.createDepartment(request)
      ElMessage.success('部门已创建')
    } else {
      await orgApi.updateDepartment(dialog.id, request)
      ElMessage.success('部门已更新')
    }
    dialog.visible = false
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    dialog.saving = false
  }
}

async function remove(row: DepartmentView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除部门 ${row.name}。该部门下有 ${row.memberCount} 个账号时后端会拒绝删除，` +
        '需要先把成员迁走或停用部门。',
      '删除部门',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await orgApi.deleteDepartment(row.id)
    ElMessage.success('部门已删除')
    await load()
  } catch (error) {
    notifyError(error)
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>部门</h2>
        <p class="subtitle">
          部门是数据可见范围的边界：非平台管理员只能看到本部门及其子部门的注册文档、Server 与审计记录。
        </p>
      </div>
      <div class="toolbar">
        <el-button type="primary" :disabled="!canWrite" @click="openCreate()">新建顶级部门</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="tree"
      border
      row-key="id"
      default-expand-all
      :tree-props="{ children: 'children' }"
    >
      <el-table-column prop="name" label="部门" min-width="240" />
      <el-table-column prop="memberCount" label="成员数" width="100" align="center" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="220">
        <template #default="{ row }">{{ row.description || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" :disabled="!canWrite" @click="openCreate(row)">
            新建子部门
          </el-button>
          <el-button link type="primary" size="small" :disabled="!canWrite" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" :disabled="!canWrite" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="还没有部门" /></template>
    </el-table>

    <el-dialog v-model="dialog.visible" :title="dialog.id == null ? '新建部门' : '编辑部门'" width="520px">
      <el-form label-width="90px" @submit.prevent="submit">
        <el-form-item label="部门名">
          <el-input v-model="dialog.form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-select v-model="dialog.form.parentId" clearable placeholder="留空表示顶级部门" style="width: 100%">
            <el-option
              v-for="option in parentOptions"
              :key="option.id"
              :label="option.label"
              :value="option.id"
              :disabled="option.disabled"
            />
          </el-select>
          <div v-if="dialog.id != null" class="hint muted">
            列表里已经排除了本部门及其所有下级——把子孙设成父级会让部门树出现环。
          </div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="dialog.form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="dialog.form.enabled" />
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
.hint {
  font-size: 12px;
  line-height: 1.6;
}
</style>