import type { RouteRecordRaw } from 'vue-router';
import { canOpenDocuments, perm } from '../admin/access';
import ChangePasswordPage from '../pages/ChangePasswordPage.vue';
import LoginPage from '../pages/LoginPage.vue';
import { RESULT_PATH } from '../sso/storage';
import NotFound from './NotFound.vue';
import PageRoute, { type GuardOptions } from './PageRoute.vue';
import ProtectedLayout from './ProtectedLayout.vue';
import Redirect from './Redirect.vue';
import type * as pages from './lazyPages';

const page = (path: string, name: keyof typeof pages, guard?: GuardOptions): RouteRecordRaw => ({ path, component: PageRoute, props: { page: name, guard } });

/**
 * 路由记录只引用同步外壳，不使用 vue-router 的懒加载组件：后者会在导航确认前下载并阻塞导航，
 * 且会在管理守卫判定前下载管理页面。页面模块由外壳在守卫通过后、于内容区异步加载。
 * 认证结果页由 AppRoutes 在身份门禁之前直接处理，这里只登记路径。
 */
export const routes: RouteRecordRaw[] = [
  { path: '/login', component: LoginPage },
  { path: '/change-password', component: ChangePasswordPage },
  { path: RESULT_PATH, component: { render: () => null } },
  {
    path: '/', component: ProtectedLayout, children: [
      { path: '', component: Redirect, props: { to: '/documents' } },
      page('/documents', 'DocumentsPage'), page('/documents/:documentId', 'DocumentDetailPage'),
      page('/imports', 'ImportsPage'), page('/imports/:importId', 'ImportDetailPage'),
      page('/favorites', 'FavoritesPage'),
      page('/reviews', 'ReviewsPage'),
      page('/notifications', 'NotificationsPage'),
      page('/knowledge-gaps', 'KnowledgeGapsPage'), page('/knowledge-gaps/new', 'KnowledgeGapNewPage'),
      page('/knowledge-gaps/:gapId', 'KnowledgeGapDetailPage'),
      page('/search', 'SearchPage'), page('/assistant', 'AssistantPage'), page('/assistant/:conversationId', 'AssistantPage'),
      page('/account', 'AccountPage'),
      page('/admin/identity/lifecycle', 'LifecyclePage', { code: perm.lifecycleRead, title: '员工生命周期' }),
      page('/admin/knowledge-gaps', 'KnowledgeGapQueuePage', { code: perm.gapRead, title: '知识问题受理' }),
      page('/admin/knowledge-gaps/:gapId', 'KnowledgeGapReceptionPage', { code: perm.gapRead, title: '知识问题受理详情' }),
      page('/admin/users', 'UsersPage', { code: perm.userRead, title: '账号管理' }),
      page('/admin/departments', 'DepartmentsPage', { code: perm.deptRead, title: '部门管理' }),
      page('/admin/roles', 'RolesPage', { code: perm.roleRead, title: '角色管理' }),
      page('/admin/spaces', 'SpacesPage', { spaces: true, title: '知识空间' }),
      page('/admin/spaces/:spaceId', 'SpaceDetailPage', { title: '空间管理' }),
      page('/admin/documents', 'DocumentsIndexPage', { allow: canOpenDocuments, title: '文档管理面板' }),
      page('/admin/documents/:documentId', 'DocumentPanelPage', { title: '文档管理' }),
      page('/admin/embedding', 'EmbeddingPage', { code: perm.embeddingRead, title: '向量重建' }),
      page('/admin/parse/dead-letters', 'ParseDeadLettersPage', { code: perm.deadLetterRead, title: '解析死信' }),
      page('/admin/audits', 'AuditsPage', { code: perm.auditRead, title: '审计查询' }),
      page('/admin/document-tags', 'DocumentTagsPage', { code: perm.tagRead, title: '全局标签' }),
      page('/admin/ai/usage', 'AiUsagePage', { code: perm.aiUsageRead, title: 'AI 用量' }),
      page('/admin/reports/operations', 'OperationsReportsPage', { code: perm.operationsReportsRead, title: '运营概览' }),
      page('/admin/ai/audits', 'AiAuditsPage', { code: perm.aiAuditRead, title: 'AI 调用审计' }),
      { path: '/:pathMatch(.*)*', component: NotFound },
    ],
  },
];
