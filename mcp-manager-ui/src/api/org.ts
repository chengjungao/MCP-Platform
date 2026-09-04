import { del, get, post, put } from './http'
import type {
  DepartmentRequest,
  DepartmentView,
  PageQuery,
  PageView,
  PermissionCatalogView,
  RoleRequest,
  RoleView,
  UserCreateRequest,
  UserUpdateRequest,
  UserView
} from './types'

export function users(params: PageQuery = {}): Promise<PageView<UserView>> {
  return get<PageView<UserView>>('/users', { ...params })
}

export function user(id: number): Promise<UserView> {
  return get<UserView>(`/users/${id}`)
}

export function createUser(request: UserCreateRequest): Promise<UserView> {
  return post<UserView>('/users', request)
}

export function updateUser(id: number, request: UserUpdateRequest): Promise<UserView> {
  return put<UserView>(`/users/${id}`, request)
}

/** 部门树，用于级联选择与树形展示。 */
export function departmentTree(): Promise<DepartmentView[]> {
  return get<DepartmentView[]>('/departments/tree')
}

/** 扁平部门列表，用于下拉选择。 */
export function departments(): Promise<DepartmentView[]> {
  return get<DepartmentView[]>('/departments')
}

export function createDepartment(request: DepartmentRequest): Promise<DepartmentView> {
  return post<DepartmentView>('/departments', request)
}

export function updateDepartment(id: number, request: DepartmentRequest): Promise<DepartmentView> {
  return put<DepartmentView>(`/departments/${id}`, request)
}

export function deleteDepartment(id: number): Promise<void> {
  return del<void>(`/departments/${id}`)
}

export function roles(): Promise<RoleView[]> {
  return get<RoleView[]>('/roles')
}

export function permissions(): Promise<PermissionCatalogView> {
  return get<PermissionCatalogView>('/permissions')
}

export function createRole(request: RoleRequest): Promise<RoleView> {
  return post<RoleView>('/roles', request)
}

export function updateRole(id: number, request: RoleRequest): Promise<RoleView> {
  return put<RoleView>(`/roles/${id}`, request)
}

export function deleteRole(id: number): Promise<void> {
  return del<void>(`/roles/${id}`)
}