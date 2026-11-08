import type { PermissionSummary } from './types';

const groupNames: Record<string, string> = { identity: '身份管理', audit: '审计', parse: '解析处理', space: '知识空间', document: '文档', embedding: '向量与检索' };
/** 分组只影响目录展示；未知前缀保持原文。 */
export function groupPermissions(list: PermissionSummary[]) {
  const groups = new Map<string, PermissionSummary[]>();
  for (const item of list) {
    const prefix = item.code.split(':')[0] || item.code;
    groups.set(prefix, [...(groups.get(prefix) ?? []), item]);
  }
  const order = Object.keys(groupNames), rank = (prefix: string) => order.includes(prefix) ? order.indexOf(prefix) : order.length;
  return [...groups.entries()].sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b)).map(([prefix, items]) => ({ prefix, name: groupNames[prefix] ?? prefix, items }));
}
