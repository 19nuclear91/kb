import { afterEach, beforeEach, vi } from 'vitest';
import { cleanup } from '@testing-library/vue';

Object.defineProperty(window, 'matchMedia', { value: () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }) });
globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
const computedStyle = window.getComputedStyle.bind(window);
beforeEach(() => {
  vi.spyOn(window, 'getComputedStyle').mockImplementation(element => computedStyle(element));
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('单元测试禁止真实网络请求')));
  sessionStorage.clear();
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
