import { defineComponent, h, shallowRef, type Component } from 'vue';
import PageLoading from './PageLoading.vue';

/** 只标记模块下载失败，不把页面依赖的渲染异常混入下载故障。 */
export class PageModuleError extends Error {
  constructor() { super('页面资源加载失败'); }
}

export type PageModule = { default: Component };

/**
 * 页面级异步组件：挂载时才下载，加载状态只占用内容区。
 * 与 React.lazy 相同，下载结果（含失败）在内存中保留，同一页面不自动或重复下载，恢复须由用户主动刷新。
 * 不使用 defineAsyncComponent：其加载失败在组件已卸载时没有存活的边界接管，会作为未处理错误上报；
 * 这里只有仍挂载的实例才在渲染中抛出失败，交由页面边界按渲染错误接管，卸载后迟到的结果不产生任何效果。
 */
export function lazyPage(load: () => Promise<PageModule>): Component {
  let resolved: Component | null = null, failed = false, attempt: Promise<Component> | null = null;
  const start = () => attempt ??= load().then(module => {
    resolved = module.default;
    return module.default;
  }, () => {
    failed = true;
    throw new PageModuleError();
  });
  return defineComponent({
    name: 'LazyPage',
    inheritAttrs: false,
    setup(_, { attrs }) {
      const page = shallowRef<Component | null>(resolved);
      const failure = shallowRef(failed);
      if (!resolved && !failed) {
        start().then(component => {
          page.value = component;
        }, () => {
          failure.value = true;
        });
      }
      return () => {
        if (failure.value) {
          throw new PageModuleError();
        }
        return page.value ? h(page.value, attrs) : h(PageLoading);
      };
    },
  });
}
