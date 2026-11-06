import { lazy, type ComponentType } from 'react';

/** 只标记模块加载失败；不把页面自身的渲染异常当作网络故障。 */
export class PageModuleError extends Error {
  constructor() { super('页面资源加载失败'); }
}

export function lazyPage(load: () => Promise<{ default: ComponentType }>) {
  return lazy(() => load().catch(() => { throw new PageModuleError(); }));
}
