/** 旧服务、未知字段类型及读取失败均不授予删除资格。 */
export function canDelete(value: unknown): boolean {
  return value !== null && typeof value === 'object' && 'canDelete' in value && value.canDelete === true;
}
