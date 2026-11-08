import { describe, expect, it } from 'vitest';

/** 返回源码中渲染原始 HTML 的用法；正文一律经插值作为文本显示。 */
export function rawHtmlUsages(source: string): string[] {
  return [...source.matchAll(/\bv-html\b|\binnerHTML\b|\bouterHTML\b|\binsertAdjacentHTML\b/g)].map(match => match[0]);
}

describe('正文安全渲染', () => {
  const sources = import.meta.glob<string>('./**/*.vue', { query: '?raw', import: 'default', eager: true });

  it('检查能识别原始 HTML 渲染', () => {
    expect(rawHtmlUsages('<template><div v-html="body" /></template>')).toEqual(['v-html']);
    expect(rawHtmlUsages('<script setup>node.innerHTML = body;</script>')).toEqual(['innerHTML']);
    expect(rawHtmlUsages('<template><pre>{{ body }}</pre></template>')).toEqual([]);
  });

  it('全部组件不渲染原始 HTML', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(0);
    const violations = Object.entries(sources).filter(([, source]) => rawHtmlUsages(source).length > 0).map(([file]) => file);
    expect(violations).toEqual([]);
  });
});
