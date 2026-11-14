import { adminGapRouteKind, gapRouteKind } from './knowledgeGaps/routes';

export const navigation = [
  { path: '/documents', label: '文档中心', mark: '01' },
  { path: '/favorites', label: '我的收藏', mark: '02' },
  { path: '/search', label: '知识搜索', mark: '03' },
  { path: '/assistant', label: 'AI 助手', mark: '04' },
  { path: '/account', label: '个人账户', mark: '05' },
  { path: '/reviews', label: '版本审核', mark: '06' },
  { path: '/notifications', label: '站内通知', mark: '07' },
  { path: '/imports', label: '我的导入任务', mark: '08' },
  { path: '/knowledge-gaps', label: '知识需求', mark: '09' },
] as const;

export function safeReturnPath(value: unknown): string {
  if (gapRouteKind(value) || adminGapRouteKind(value)) return value as string;
  return typeof value === 'string' && /^\/(?:documents(?:\/[1-9]\d*)?|imports(?:\/[1-9]\d*)?|favorites|reviews|notifications|search|assistant(?:\/[1-9]\d*)?|account|admin\/(?:users|departments|roles|identity\/lifecycle|spaces(?:\/[1-9]\d*)?|documents(?:\/[1-9]\d*)?|document-tags|embedding|parse\/dead-letters|audits|reports\/operations|ai\/(?:usage|audits)))$/.test(value)
    ? value : '/documents';
}

export function conversationEnabled(value: unknown = import.meta.env.VITE_CONVERSATION_ENABLED): boolean {
  return value === 'true';
}
