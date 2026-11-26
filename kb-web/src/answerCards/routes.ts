/** 同步路由识别不加载卡片正文协议或业务页面。 */
export function cardRouteKind(value: unknown): 'browse' | 'workspaces' | 'management' | 'workspace' | 'detail' | null {
  if (value === '/answer-cards') return 'browse';
  if (value === '/answer-cards/workspace') return 'workspaces';
  if (value === '/answer-cards/management') return 'management';
  if (typeof value !== 'string') return null;
  const match = /^\/answer-cards\/([1-9]\d{0,18})\/(workspace|revisions\/([1-9]\d{0,18}))$/.exec(value);
  if (!match || BigInt(match[1]!) > 9223372036854775807n || match[3] && BigInt(match[3]) > 9223372036854775807n) return null;
  return match[2] === 'workspace' ? 'workspace' : 'detail';
}
