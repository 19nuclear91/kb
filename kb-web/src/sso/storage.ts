import { ApiError } from '../api';
import { safeReturnPath } from '../navigation';
import { isPlatform, type Intent, type Platform } from './api';

export const FLOW_KEY = 'kb.sso-flow', NAV_KEY = 'kb.sso-navigation';
export const RESULT_PATH = '/auth/sso/result';
export const browserStorage: StorageLike = {
  getItem: key => sessionStorage.getItem(key), setItem: (key, value) => sessionStorage.setItem(key, value),
  removeItem: key => sessionStorage.removeItem(key),
};
export type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
export type FlowPhase = 'STARTING' | 'ARMED' | 'CONSUMING' | 'CONFIRMED' | 'REJECTED' | 'UNCERTAIN';
export interface FlowHint {
  version: 1; attemptKey: string; intent: Intent; platform: Platform; phase: FlowPhase; expiresAt: number; returnPath: string;
}
export interface NavigationHint { version: 1; returnPath: string; platform?: Platform; expiresAt: number }
const ttl = 600000, limit = 2048;
export class HintStorageError extends ApiError {
  constructor() { super(503); this.message = '标签页无法保存授权流程，请检查浏览器存储设置。'; }
}
export class SsoHints {
  constructor(private storage: StorageLike, private now = () => Date.now()) {}
  private read(key: string): Record<string, unknown> | null {
    try {
      const text = this.storage.getItem(key);
      if (!text) return null;
      if (new TextEncoder().encode(text).byteLength > limit) throw new Error();
      const value = JSON.parse(text);
      if (!value || typeof value !== 'object' || Array.isArray(value) || value.version !== 1
        || !Number.isSafeInteger(value.expiresAt) || value.expiresAt <= this.now() || value.expiresAt > this.now() + ttl
        || typeof value.returnPath !== 'string' || safeReturnPath(value.returnPath) !== value.returnPath) throw new Error();
      return value;
    } catch { this.remove(key); return null; }
  }
  private write(key: string, value: FlowHint | NavigationHint) {
    try {
      const text = JSON.stringify(value);
      if (new TextEncoder().encode(text).byteLength > limit) throw new Error();
      this.storage.setItem(key, text);
      if (this.storage.getItem(key) !== text) throw new Error();
    } catch { throw new HintStorageError(); }
  }
  private remove(key: string) { try { this.storage.removeItem(key); } catch { /* 失效提示不能扩大授权。 */ } }
  readFlow(): FlowHint | null {
    const value = this.read(FLOW_KEY);
    if (!value) return null;
    const keys = ['version', 'attemptKey', 'intent', 'platform', 'phase', 'expiresAt', 'returnPath'];
    if (Object.keys(value).some(key => !keys.includes(key)) || !isPlatform(value.platform)
      || !['LOGIN', 'BIND'].includes(String(value.intent)) || typeof value.attemptKey !== 'string'
      || !/^[0-9a-f-]{36}$/i.test(value.attemptKey) || !['STARTING', 'ARMED', 'CONSUMING', 'CONFIRMED', 'REJECTED', 'UNCERTAIN'].includes(String(value.phase))) {
      this.remove(FLOW_KEY); return null;
    }
    return value as unknown as FlowHint;
  }
  start(intent: Intent, platform: Platform, returnPath: unknown): FlowHint {
    const hint: FlowHint = { version: 1, attemptKey: crypto.randomUUID(), intent, platform, phase: 'STARTING', expiresAt: this.now() + ttl, returnPath: safeReturnPath(returnPath) };
    this.write(FLOW_KEY, hint);
    this.navigateTo(hint.returnPath);
    return hint;
  }
  update(hint: FlowHint, phase: FlowPhase): FlowHint {
    if (this.readFlow()?.attemptKey !== hint.attemptKey) throw new HintStorageError();
    const next = { ...hint, phase }; this.write(FLOW_KEY, next); return next;
  }
  navigation(): NavigationHint | null {
    const value = this.read(NAV_KEY);
    if (!value) return null;
    if (Object.keys(value).some(key => !['version', 'returnPath', 'platform', 'expiresAt'].includes(key))
      || (value.platform !== undefined && !isPlatform(value.platform))) { this.remove(NAV_KEY); return null; }
    return value as unknown as NavigationHint;
  }
  navigateTo(returnPath: unknown, platform?: Platform) {
    const hint: NavigationHint = { version: 1, returnPath: safeReturnPath(returnPath), expiresAt: this.now() + ttl };
    if (platform !== undefined) { if (!isPlatform(platform)) throw new HintStorageError(); hint.platform = platform; }
    this.write(NAV_KEY, hint);
  }
}
