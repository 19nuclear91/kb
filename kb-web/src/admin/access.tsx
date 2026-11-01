import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Alert, Button, Spin } from 'antd';
import { NavLink } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { PageHeader } from '../components';
import { adminPaths } from './api';
import { spacePaths, type AdminSpace } from './spaceApi';

export type AccessStatus = 'loading' | 'ready' | 'unavailable';
export interface AdminAccess { status: AccessStatus; spaceStatus: AccessStatus; spaces: readonly AdminSpace[]; factsKey?: string; permissionFactsKey?: string; canManageSpace: (id: string) => boolean; checking: boolean; has: (code: string) => boolean; check: (force?: boolean) => void }
const none: ReadonlySet<string> = new Set();
export const emptyAdminAccess: AdminAccess = { status: 'unavailable', spaceStatus: 'unavailable', spaces: [], canManageSpace: () => false, checking: false, has: () => false, check: () => {} };
/** 缺少提供者时一律视为不可确认，不默认放行任何管理能力。 */
export const AdminAccessContext = createContext<AdminAccess>(emptyAdminAccess);
export const useAdminAccess = () => useContext(AdminAccessContext);

type View = { epoch: number; status: AccessStatus; spaceStatus: AccessStatus; spaces: readonly AdminSpace[]; codes: ReadonlySet<string>; checking: boolean };
const loading = (epoch: number): View => ({ epoch, status: 'loading', spaceStatus: 'loading', spaces: [], codes: none, checking: true });
/** 本人当前功能权限，以会话 epoch 为界；读取失败只影响管理区，ready 的空集合代表确实无权限。 */
export function AdminAccessProvider({ children, loadSpaces = true }: { children: ReactNode; loadSpaces?: boolean }) {
  const { session, state } = useSession();
  const [view, setView] = useState<View>(loading(state.epoch));
  const sequence = useRef(0), active = useRef<AbortController | null>(null);
  const check = useCallback((force = false) => {
    if (active.current && !force) return;
    active.current?.abort();
    const controller = new AbortController(), seq = ++sequence.current, epoch = session.getSnapshot().epoch;
    active.current = controller;
    setView(loading(epoch));
    let pending = loadSpaces ? 2 : 1;
    const apply = (next: Partial<View>) => {
      if (seq !== sequence.current || epoch !== session.getSnapshot().epoch || controller.signal.aborted) return;
      pending--;
      if (!pending) active.current = null;
      setView(previous => ({ ...previous, ...next, epoch, checking: pending > 0 }));
    };
    void session.json<unknown>(adminPaths.mine, { signal: controller.signal }).then(codes => {
      if (Array.isArray(codes) && codes.every((code): code is string => typeof code === 'string')) apply({ status: 'ready', codes: new Set(codes) });
      else apply({ status: 'unavailable', codes: none });
    }, () => apply({ status: 'unavailable', codes: none }));
    if (!loadSpaces) setView(previous => ({ ...previous, spaceStatus: 'ready', spaces: [] }));
    if (loadSpaces) void session.json<unknown>(spacePaths.spaces, { signal: controller.signal }).then(result => {
      if (Array.isArray(result) && result.every(space => space && typeof space.spaceId === 'string' && typeof space.manageable === 'boolean'))
        apply({ spaceStatus: 'ready', spaces: (result as AdminSpace[]).filter(space => space.manageable) });
      else apply({ spaceStatus: 'unavailable', spaces: [] });
    }, () => apply({ spaceStatus: 'unavailable', spaces: [] }));
  }, [session, loadSpaces]);
  useEffect(() => {
    check();
    return () => { sequence.current++; active.current?.abort(); active.current = null; };
  }, [check, state.epoch]);
  const current: View = view.epoch === state.epoch ? view : loading(state.epoch);
  const value = useMemo<AdminAccess>(() => ({ status: current.status, spaceStatus: current.spaceStatus, spaces: current.spaces,
    factsKey: JSON.stringify([[...current.codes].sort(), current.spaces.map(space => `${space.spaceId}:${space.archived}`).sort()]),
    // 功能权限消费者不应因空间目录的加载、清空或恢复而丢失拒绝状态。
    permissionFactsKey: JSON.stringify([...current.codes].sort()),
    canManageSpace: id => current.spaceStatus === 'ready' && current.spaces.some(space => space.spaceId === id),
    checking: current.checking, has: code => current.status === 'ready' && current.codes.has(code), check }),
    [current.status, current.spaceStatus, current.spaces, current.checking, current.codes, check]);
  return <AdminAccessContext value={value}>{children}</AdminAccessContext>;
}

/** 管理页面守卫：按完整权限 code 精确判断，不依据角色名称、前缀或通配；进入时核对一次本人权限。 */
export function AdminGuard({ code, title, children, spaces = false }: { code?: string; title: string; children: ReactNode; spaces?: boolean }) {
  const access = useAdminAccess();
  const { check } = access;
  useEffect(() => { check(); }, [check]);
  if (access.status === 'loading' || (spaces && access.spaceStatus === 'loading')) return <div className="panel" role="status"><Spin /> <span className="muted">正在确认管理权限…</span></div>;
  if (access.status === 'unavailable' && (!spaces || !canOpenSpaces(access))) return <><PageHeader title={title} /><Alert type="warning" showIcon title="当前无法确认管理权限，管理功能暂不可用。"
    action={<Button disabled={access.checking} onClick={() => check(true)}>重新确认</Button>} /></>;
  if (spaces && !canOpenSpaces(access) && access.spaceStatus === 'unavailable') return <><PageHeader title={title} /><Alert type="warning" title="当前无法读取可管理空间。" action={<Button onClick={() => check(true)}>重新确认</Button>} /></>;
  if ((code && !access.has(code)) || (spaces && !canOpenSpaces(access))) return <><PageHeader title={title} /><Alert type="info" showIcon title="当前账号没有此管理页面的读取资格。" /></>;
  return children;
}

/** 服务端既有身份管理权限 code；各项互不蕴含，前端不新增 code。 */
export const perm = {
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
export const adminNavigation = [
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
] as const;
export function AdminNavigation() {
  const access = useAdminAccess();
  const items = adminNavigation.filter(item => item.visible(access));
  if (!items.length) return null;
  return <nav aria-label="管理导航" style={{ marginTop: 24 }}><div className="muted" style={{ padding: '0 16px' }}>管理</div>
    {items.map(item => <NavLink key={item.path} to={item.path}><span className="nav-mark">{item.mark}</span>{item.label}</NavLink>)}</nav>;
}
