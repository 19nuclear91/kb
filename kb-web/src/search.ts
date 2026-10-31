import { ApiError, messageOf } from './api';

const configured = Number(import.meta.env.VITE_SEARCH_MAX_QUERY_CODEPOINTS ?? 8000);
export const searchMaxQueryCodepoints = Number.isSafeInteger(configured) && configured > 0 ? configured : 8000;

export function searchInputError(query: string, maximum = searchMaxQueryCodepoints): string | undefined {
  if (!query?.trim()) return '请输入搜索词';
  let count = 0;
  for (const character of query) {
    const point = character.codePointAt(0)!;
    if (point === 0 || (point >= 0xd800 && point <= 0xdfff)) return '搜索输入包含无效字符，请修改后重试。';
    if (++count > maximum) return `搜索词最多 ${maximum} 个字符，请缩短后重试。`;
  }
}

export function searchErrorMessage(error: unknown): string {
  const messages: Record<number, string> = {
    400: '搜索输入不符合要求，请修改后重试。', 413: '搜索请求过大，请缩短后重试。',
    429: '搜索请求过于频繁，请稍后重试。', 503: '检索暂不可用，请稍后重试。',
  };
  return error instanceof ApiError && messages[error.status] ? messages[error.status] : messageOf(error);
}
