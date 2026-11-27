/** 登录返回仅接受已知页面和精确标识，不在URL保存搜索词、请求或回执。 */
export function knowledgeRoute(value: unknown): boolean {
  if (typeof value !== 'string') return false;
  if (['/subscriptions', '/updates', '/digests'].includes(value)) return true;
  if (/^\/subscriptions\/documents\/[1-9]\d{0,18}$/.test(value)) return BigInt(value.split('/').at(-1)!) <= 9223372036854775807n;
  return /^\/(updates|digests)\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
}
