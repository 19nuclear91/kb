/** 路由与登录返回共用的最小地址白名单；不依赖问题 DTO、正文或页面模块。 */
export function gapRouteKind(path: unknown): 'list' | 'new' | 'detail' | null {
  if (path === '/knowledge-gaps') return 'list';
  if (path === '/knowledge-gaps/new') return 'new';
  if (typeof path !== 'string') return null;
  const match = /^\/knowledge-gaps\/([1-9]\d{0,18})$/.exec(path);
  return match && BigInt(match[1]) <= 9223372036854775807n ? 'detail' : null;
}
export function adminGapRouteKind(path: unknown): 'list' | 'detail' | null {
  if (path === '/admin/knowledge-gaps') return 'list';
  if (typeof path !== 'string') return null;
  const match = /^\/admin\/knowledge-gaps\/([1-9]\d{0,18})$/.exec(path);
  return match && BigInt(match[1]) <= 9223372036854775807n ? 'detail' : null;
}
