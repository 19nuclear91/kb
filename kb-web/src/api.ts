import { parse, parseNumberAndBigInt, stringify } from 'lossless-json';

export class ApiError extends Error {
  constructor(public readonly status: number) { super(errorMessage(status)); }
}

export function errorMessage(status: number): string {
  return ({ 0: '网络结果尚未确认，请稍后核对。', 400: '输入或请求不符合要求。', 401: '登录状态已失效，请重新登录。',
    403: '当前操作被拒绝。', 404: '目标不存在或当前不可访问。', 409: '状态已变化，请刷新后确认。',
    413: '文件超过大小限制。', 415: '当前文件格式不受支持。', 429: '当前部门额度不足，请稍后再试。',
    503: '服务暂不可用，请稍后重试。' } as Record<number, string>)[status] ?? '请求处理失败，请稍后重试。';
}

export function decodeJson<T>(text: string): T {
  const walk = (value: unknown, key = ''): unknown => {
    if (typeof value === 'number' && Number.isInteger(value)) {
      if (key === 'id' || key.endsWith('Id') || key === 'roles') return String(value);
      if (key.endsWith('Seq')) return BigInt(value);
    }
    if (typeof value === 'bigint') {
      if (key === 'id' || key.endsWith('Id') || key === 'roles') return value.toString();
      if (key.endsWith('Seq') || value > BigInt(Number.MAX_SAFE_INTEGER) || value < BigInt(Number.MIN_SAFE_INTEGER)) return value;
      return Number(value);
    }
    if (Array.isArray(value)) return value.map(item => walk(item, key));
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([name, item]) => [name, walk(item, name)]));
    return value;
  };
  try { return walk(parse(text, undefined, parseNumberAndBigInt)) as T; }
  catch { throw new ApiError(503); }
}

export function encodeJson(value: unknown): string { return stringify(value)!; }

export async function readJson<T>(response: Response, envelope = true): Promise<T> {
  if (!response.ok) { await response.body?.cancel().catch(() => {}); throw new ApiError(response.status); }
  if (response.status === 204) return undefined as T;
  if (!response.headers.get('Content-Type')?.includes('application/json')) { await response.body?.cancel().catch(() => {}); throw new ApiError(503); }
  const data = decodeJson<unknown>(await response.text());
  if (!envelope) return data as T;
  const wrapped = data as { code: number; data: T };
  if (!wrapped || wrapped.code !== 200) throw new ApiError(503);
  return wrapped.data;
}

export function positiveId(value: unknown): string {
  const result = String(value ?? '');
  if (!/^[1-9]\d{0,18}$/.test(result) || BigInt(result) > 9223372036854775807n) throw new ApiError(400);
  return result;
}

export function filePath(value: string): string {
  if (!/^\/api\/documents\/[1-9]\d*(?:\/versions\/[1-9]\d*)?\/(?:preview|download)$/.test(value)) throw new ApiError(400);
  return value;
}

export function isAbort(error: unknown): boolean { return error instanceof Error && error.name === 'AbortError'; }
export function messageOf(error: unknown): string { return error instanceof ApiError ? error.message : '请求未能完成，请稍后核对。'; }
