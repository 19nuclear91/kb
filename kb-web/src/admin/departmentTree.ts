import type { AdminDepartment } from './types';

export type Tree = { roots: AdminDepartment[]; children: Map<string, AdminDepartment[]> };
/** 按 parentId 构树，保留停用节点、孤立节点与有环目录；不由 path 推导权限。 */
export function buildTree(list: AdminDepartment[]): Tree {
  const ids = new Set(list.map(item => item.departmentId)), children = new Map<string, AdminDepartment[]>(), roots: AdminDepartment[] = [];
  for (const item of list) {
    if (item.parentId != null && ids.has(item.parentId) && item.parentId !== item.departmentId) {
      children.set(item.parentId, [...(children.get(item.parentId) ?? []), item]);
    } else {
      roots.push(item);
    }
  }
  const seen = new Set<string>();
  const visit = (id: string) => {
    if (seen.has(id)) {
      return;
    }
    seen.add(id);
    (children.get(id) ?? []).forEach(child => visit(child.departmentId));
  };
  roots.forEach(root => visit(root.departmentId));
  for (const item of list) {
    if (!seen.has(item.departmentId)) {
      roots.push(item);
      visit(item.departmentId);
    }
  }
  return { roots, children };
}
export function descendantsOf(tree: Tree, departmentId: string): Set<string> {
  const result = new Set<string>(), queue = [departmentId];
  while (queue.length) {
    for (const child of tree.children.get(queue.shift()!) ?? []) {
      if (!result.has(child.departmentId)) {
        result.add(child.departmentId);
        queue.push(child.departmentId);
      }
    }
  }
  return result;
}
export const departmentLabel = (department: AdminDepartment) => `${department.name}（${department.code}）`;
