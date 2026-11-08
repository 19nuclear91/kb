import type { AdminResource } from '../hooks';
import type { AdminDepartment } from '../types';

export type Departments = AdminResource<AdminDepartment[]>;
export const departmentLabel = (department: AdminDepartment) => `${department.name}（${department.code}）${department.enabled ? '' : ' · 已停用'}`;
export function departmentText(departments: Departments, departmentId: string | null) {
  if (departmentId == null) {
    return '未设置';
  }
  const found = departments.data?.find(item => item.departmentId === departmentId);
  return found ? departmentLabel(found) : `部门 ID ${departmentId}`;
}
export const missingDepartments = '缺少 identity:dept:read，无法列出部门候选；不提供手工输入部门 ID。';
