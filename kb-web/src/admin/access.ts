import { liveView } from '../liveView';
import { useAdminAccessStore, type AdminAccess } from '../stores/adminAccess';

export { emptyAdminAccess, type AccessStatus, type AdminAccess } from '../stores/adminAccess';

/** 当前管理资格；在渲染、computed 或 watch 中读取属性才会随资格变化更新。 */
export function useAdminAccess(): AdminAccess {
  const store = useAdminAccessStore();
  return liveView(() => store.access);
}

/** 服务端既有身份管理权限 code；各项互不蕴含，前端不新增 code。 */
export const perm = {
  lifecycleRead: 'identity:lifecycle:read', lifecycleManage: 'identity:lifecycle:manage', lifecycleRestore: 'identity:lifecycle:restore', emergencyManage: 'identity:emergency:manage',
  userRead: 'identity:user:read', userWrite: 'identity:user:write', password: 'identity:user:password', clearance: 'identity:clearance:write',
  roleRead: 'identity:role:read', roleAssign: 'identity:role:assign', roleWrite: 'identity:role:write', deptRead: 'identity:dept:read', deptWrite: 'identity:dept:write',
  spaceCreate: 'space:create', spaceManageAll: 'space:manage-all', grantManage: 'space:grant-manage', securityLevelWrite: 'document:security-level:write',
  embeddingRead: 'embedding:read', embeddingRebuild: 'embedding:rebuild', deadLetterRead: 'parse:deadletter:read', deadLetterReplay: 'parse:deadletter:replay',
  auditRead: 'audit:read',
  aiUsageRead: 'ai:usage:read', aiAuditRead: 'ai:audit:read',
  operationsReportsRead: 'operations:reports:read',
  tagRead: 'document:tag:read', tagWrite: 'document:tag:write',
} as const;
export const canOpenSpaces = (access: AdminAccess) => (access.spaceStatus === 'ready' && access.spaces.length > 0) || access.has(perm.spaceCreate) || access.has(perm.spaceManageAll);
/** 文档管理面板入口：知识空间条件之外再加密级调整权限；入口显示不代表任何文档可管理，面板各区块独立判定。 */
export const canOpenDocuments = (access: AdminAccess) => canOpenSpaces(access) || access.has(perm.securityLevelWrite);
export const adminNavigation = [
  { path: '/admin/identity/lifecycle', label: '员工生命周期', mark: 'A13', visible: (access: AdminAccess) => access.has(perm.lifecycleRead) },
  { path: '/admin/users', label: '账号管理', mark: 'A1', visible: (access: AdminAccess) => access.has(perm.userRead) },
  { path: '/admin/departments', label: '部门管理', mark: 'A2', visible: (access: AdminAccess) => access.has(perm.deptRead) },
  { path: '/admin/roles', label: '角色管理', mark: 'A3', visible: (access: AdminAccess) => access.has(perm.roleRead) },
  { path: '/admin/spaces', label: '知识空间', mark: 'A4', visible: canOpenSpaces },
  { path: '/admin/embedding', label: '向量重建', mark: 'A5', visible: (access: AdminAccess) => access.has(perm.embeddingRead) },
  { path: '/admin/parse/dead-letters', label: '解析死信', mark: 'A6', visible: (access: AdminAccess) => access.has(perm.deadLetterRead) },
  { path: '/admin/audits', label: '审计查询', mark: 'A7', visible: (access: AdminAccess) => access.has(perm.auditRead) },
  { path: '/admin/ai/usage', label: 'AI 用量', mark: 'A8', visible: (access: AdminAccess) => access.has(perm.aiUsageRead) },
  { path: '/admin/ai/audits', label: 'AI 调用审计', mark: 'A9', visible: (access: AdminAccess) => access.has(perm.aiAuditRead) },
  { path: '/admin/reports/operations', label: '运营概览', mark: 'A10', visible: (access: AdminAccess) => access.has(perm.operationsReportsRead) },
  { path: '/admin/document-tags', label: '全局标签', mark: 'A11', visible: (access: AdminAccess) => access.has(perm.tagRead) },
  { path: '/admin/documents', label: '文档管理面板', mark: 'A12', visible: canOpenDocuments },
] as const;
