import { PageLoadBoundary } from './routing/PageLoadBoundary';
import { LifecyclePage } from './routing/lazyPages';
import { AccountPage, DocumentsPage, DocumentDetailPage, SearchPage, FavoritesPage, ReviewsPage, NotificationsPage, AssistantPage, UsersPage, DepartmentsPage, RolesPage, SpacesPage, SpaceDetailPage, DocumentsIndexPage, DocumentPanelPage, EmbeddingPage, ParseDeadLettersPage, AuditsPage, AiUsagePage, AiAuditsPage, OperationsReportsPage, DocumentTagsPage } from './routing/lazyPages';
import { Button, Space, Spin, Alert } from 'antd';
import { NavLink, Navigate, Route, Routes, Outlet, useLocation } from 'react-router-dom';
import { navigation, safeReturnPath } from './navigation';
import { SessionProvider, useSession } from './SessionProvider';
import { Session } from './session';
import { LoginPage } from './pages/LoginPage';
import { ChangePasswordPage } from './pages/ChangePasswordPage';

import { UnreadBadge } from './notifications/unread';

import { AdminAccessProvider, AdminGuard, AdminNavigation, canOpenDocuments, perm } from './admin/access';

import { useEffect, useRef, type ReactNode } from 'react';
import { SsoResultPage } from './pages/SsoResultPage';
import { browserStorage, RESULT_PATH } from './sso/storage';
import { getSsoFlow } from './sso/flow';

export function Navigation({ extras = {} }: { extras?: Partial<Record<string, ReactNode>> }) {
  return <nav aria-label="主要导航">{navigation.map(item =>
    <NavLink key={item.path} to={item.path}><span className="nav-mark">{item.mark}</span>{item.label}{extras[item.path]}</NavLink>,
  )}</nav>;
}

export function ProtectedLayout() {
  const { session, state } = useSession(), location = useLocation();
  if (state.phase === 'anonymous') return <Navigate to="/login" state={{ from: safeReturnPath(location.pathname + location.search + location.hash) }} replace />;
  if (state.phase === 'password') return <Navigate to="/change-password" replace />;
  // 仅在身份 ready 后挂载本人权限上下文；强制改密阶段已在上方重定向，不预取管理权限或数据。
  return <AdminAccessProvider loadSpaces={location.pathname !== '/admin/reports/operations'}><div className="app-shell"><aside className="sidebar"><div className="brand"><span className="brand-mark">知</span><div>知序<small>企业知识库</small></div></div><Navigation extras={{ '/notifications': <UnreadBadge /> }} /><AdminNavigation />
    <div style={{ marginTop: 48 }}><p className="muted">{state.me?.user.displayName}</p><Button onClick={() => void session.logout()}>退出登录</Button></div></aside>
    <main className="workspace"><Outlet /></main></div></AdminAccessProvider>;
}
export function AppRoutes() {
  const { session, state } = useSession();
  const location = useLocation();
  if (location.pathname === RESULT_PATH) return <SsoResultPage />;
  if (state.phase === 'loading') return <div className="auth-page" role="status"><Space><Spin />正在确认登录状态…</Space></div>;
  if (state.phase === 'unavailable') return <div className="auth-page"><section className="auth-card"><Alert type="warning" title={state.notice} /><Space style={{ marginTop: 20 }}>
    <Button onClick={() => void session.refresh()}>重新确认身份</Button><Button onClick={() => void session.logout()}>退出</Button></Space></section></div>;
  return <Routes><Route path="/login" element={<LoginPage />} /><Route path="/change-password" element={<ChangePasswordPage />} />
    <Route element={<ProtectedLayout />}><Route index element={<Navigate to="/documents" replace />} />
      <Route path="/documents" element={<PageLoadBoundary><DocumentsPage /></PageLoadBoundary>} /><Route path="/documents/:documentId" element={<PageLoadBoundary><DocumentDetailPage /></PageLoadBoundary>} />
      <Route path="/favorites" element={<PageLoadBoundary><FavoritesPage /></PageLoadBoundary>} />
      <Route path="/reviews" element={<PageLoadBoundary><ReviewsPage /></PageLoadBoundary>} />
      <Route path="/notifications" element={<PageLoadBoundary><NotificationsPage /></PageLoadBoundary>} />
      <Route path="/search" element={<PageLoadBoundary><SearchPage /></PageLoadBoundary>} /><Route path="/assistant" element={<PageLoadBoundary><AssistantPage /></PageLoadBoundary>} /><Route path="/assistant/:conversationId" element={<PageLoadBoundary><AssistantPage /></PageLoadBoundary>} />
      <Route path="/account" element={<PageLoadBoundary><AccountPage /></PageLoadBoundary>} />
      <Route path="/admin/identity/lifecycle" element={<AdminGuard code={perm.lifecycleRead} title="员工生命周期"><PageLoadBoundary><LifecyclePage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/users" element={<AdminGuard code={perm.userRead} title="账号管理"><PageLoadBoundary><UsersPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/departments" element={<AdminGuard code={perm.deptRead} title="部门管理"><PageLoadBoundary><DepartmentsPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/roles" element={<AdminGuard code={perm.roleRead} title="角色管理"><PageLoadBoundary><RolesPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/spaces" element={<AdminGuard spaces title="知识空间"><PageLoadBoundary><SpacesPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/spaces/:spaceId" element={<AdminGuard title="空间管理"><PageLoadBoundary><SpaceDetailPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/documents" element={<AdminGuard allow={canOpenDocuments} title="文档管理面板"><PageLoadBoundary><DocumentsIndexPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/documents/:documentId" element={<AdminGuard title="文档管理"><PageLoadBoundary><DocumentPanelPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/embedding" element={<AdminGuard code={perm.embeddingRead} title="向量重建"><PageLoadBoundary><EmbeddingPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/parse/dead-letters" element={<AdminGuard code={perm.deadLetterRead} title="解析死信"><PageLoadBoundary><ParseDeadLettersPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/audits" element={<AdminGuard code={perm.auditRead} title="审计查询"><PageLoadBoundary><AuditsPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/document-tags" element={<AdminGuard code={perm.tagRead} title="全局标签"><PageLoadBoundary><DocumentTagsPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/ai/usage" element={<AdminGuard code={perm.aiUsageRead} title="AI 用量"><PageLoadBoundary><AiUsagePage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/reports/operations" element={<AdminGuard code={perm.operationsReportsRead} title="运营概览"><PageLoadBoundary><OperationsReportsPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="/admin/ai/audits" element={<AdminGuard code={perm.aiAuditRead} title="AI 调用审计"><PageLoadBoundary><AiAuditsPage /></PageLoadBoundary></AdminGuard>} />
      <Route path="*" element={<div><h1>页面不存在</h1><NavLink to="/documents">返回文档中心</NavLink></div>} />
    </Route></Routes>;
}
let defaultSession: Session | undefined;
export default function App({ session }: { session?: Session }) {
  const current = session ?? (defaultSession ??= new Session(browserStorage));
  const location = useLocation(), previous = useRef({ session: current, path: location.pathname });
  useEffect(() => {
    const before = previous.current;
    if ([RESULT_PATH, '/login', '/account'].includes(before.path) && (location.pathname !== before.path || before.session !== current)) getSsoFlow(before.session).leave();
    previous.current = { session: current, path: location.pathname };
  }, [current, location.pathname]);
  return <SessionProvider session={current} handoff={location.pathname === RESULT_PATH}><AppRoutes /></SessionProvider>;
}
