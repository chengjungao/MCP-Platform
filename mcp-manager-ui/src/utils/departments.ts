import type { DepartmentView } from '@/api/types'

export interface DepartmentOption {
  id: number
  label: string
  disabled: boolean
}

/**
 * 把部门树摊平成带缩进的选项列表，供 el-select 使用。
 *
 * 这里刻意不用 el-tree-select：选「父部门」和「授权部门」都是低频但要求准确的操作，
 * 深层组织里树选择器要点开好几次才看得到目标，而下拉列表把层级直接摊在一屏里更好核对。
 * 缩进用全角空格，半角空格会被 HTML 折叠掉。
 *
 * `excludeId` 会连同该节点的整棵子树一起排除——编辑部门时若能把自己的子孙选成父级，
 * 树就会出现环，`/departments/tree` 递归组装时会直接栈溢出。
 */
export function flattenDepartments(
  nodes: DepartmentView[],
  depth = 0,
  excludeId?: number
): DepartmentOption[] {
  const result: DepartmentOption[] = []
  for (const node of nodes) {
    if (excludeId != null && node.id === excludeId) continue
    result.push({
      id: node.id,
      label: `${'　'.repeat(depth)}${node.name}${node.enabled ? '' : '（已停用）'}`,
      disabled: !node.enabled
    })
    if (node.children && node.children.length > 0) {
      result.push(...flattenDepartments(node.children, depth + 1, excludeId))
    }
  }
  return result
}