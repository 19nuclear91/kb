import { ApiError } from '../api';
import type { ExactInteger } from './types';

const MAX_INTEGER = 9223372036854775807n;
export const GAP_BODY_LIMIT = 64 * 1024;
export const GAP_CURSOR = /^[A-Za-z0-9_-]{1,2731}$/;
export function gapId(value: unknown): string {
  if (!['string', 'number', 'bigint'].includes(typeof value) || (typeof value === 'number' && !Number.isSafeInteger(value))) throw new ApiError(400);
  const text = String(value);
  if (!/^[1-9]\d{0,18}$/.test(text) || BigInt(text) > MAX_INTEGER) throw new ApiError(400);
  return text;
}
export function gapVersion(value: ExactInteger | unknown): bigint {
  if (!['string', 'number', 'bigint'].includes(typeof value) || (typeof value === 'number' && !Number.isSafeInteger(value))) throw new ApiError(400);
  const text = String(value);
  if (!/^(?:0|[1-9]\d{0,18})$/.test(text) || BigInt(text) > MAX_INTEGER) throw new ApiError(400);
  return BigInt(text);
}
export function gapUuid(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) throw new ApiError(400);
  return value.toLowerCase();
}
export function gapUnicode(value: unknown): string {
  if (typeof value !== 'string') throw new ApiError(400);
  for (const point of value) {
    const code = point.codePointAt(0)!;
    if (code === 0 || (code >= 0xd800 && code <= 0xdfff)) throw new ApiError(400);
  }
  return value;
}
/** 与服务端 String.strip 一致，保留不换行空格，不改写换行或 Unicode。 */
export function gapStrip(value: unknown): string {
  return gapUnicode(value).replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g, '');
}
export function gapText(value: unknown, maximum: number): string {
  const text = gapStrip(value), count = [...text].length;
  if (!count || count > maximum) throw new ApiError(400);
  return text;
}
export function gapTextLength(value: string): number { return [...gapStrip(value)].length; }
export function gapRequestId(value: unknown): string {
  const text = gapUnicode(value);
  if (!text || [...text].length > 128 || /\p{Cc}/u.test(text)) throw new ApiError(400);
  // 浏览器 Headers 只能传输 ByteString；本客户端生成 ASCII UUID，不假称能发送任意 Unicode 头。
  if (/[^\x20-\xff]/.test(text) || text.trim() !== text) throw new ApiError(400);
  return text;
}
export function gapKeys(value: unknown, required: readonly string[], optional: readonly string[] = []): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
    && required.every(key => Object.hasOwn(value, key)) && Object.keys(value).every(key => required.includes(key) || optional.includes(key));
}
export function gapLimit(value: unknown = 20): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 100) throw new ApiError(400);
  return value;
}
export function gapCursor(value: unknown): string | null {
  if (value == null) return null;
  if (typeof value !== 'string' || !GAP_CURSOR.test(value)) throw new ApiError(400);
  return value;
}
