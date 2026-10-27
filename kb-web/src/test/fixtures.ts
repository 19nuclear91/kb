import { vi } from 'vitest';
import { encodeJson } from '../api';
import type { Me, Turn } from '../types';
export const me: Me = { user: { userId: '1', username: 'alice', displayName: '小林', departmentId: '2', clearanceLevel: 1, enabled: true }, roles: ['3'], mustChangePassword: false };
export const turn: Turn = { id: '12345678-1234-1234-1234-123456789012', turnSeq: 1n, question: '原问题', answer: '已保存前缀', status: 'CANCELLED', visibility: 'VISIBLE', citations: [], usageStatus: 'UNKNOWN', settlementState: 'PENDING', promptTokens: null, completionTokens: null, chargedTokens: 9 };
export const json = (value: unknown, envelope = true, status = 200) => new Response(encodeJson(envelope ? { code: 200, data: value } : value), { status, headers: { 'Content-Type': 'application/json' } });
export function storage(saved = true) {
  const values = new Map<string, string>(saved ? [['kb.local-session', JSON.stringify({ tokenName: 'satoken', tokenValue: 'unit-token' })]] : []);
  return { values, getItem: vi.fn((key: string) => values.get(key) ?? null), setItem: vi.fn((key: string, value: string) => { values.set(key, value); }), removeItem: vi.fn((key: string) => { values.delete(key); }) };
}
export function deferred<T>() { let resolve!: (value: T) => void, reject!: (reason?: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
