import { defineComponent, h, type Component, type VNodeChild } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { locationState } from '../routing/locationState';

/**
 * 文字占位页，供 vi.mock 工厂返回：`vi.mock('./pages/X.vue', () => import('./test/stubs').then(m => m.stubPage('文字')))`。
 * 本模块不得导入路由表或页面，避免 mock 工厂与被 mock 模块循环等待。
 */
export function stubPage(text: string | (() => VNodeChild)): { default: Component } {
  return { default: defineComponent({ setup: () => () => (typeof text === 'function' ? h('div', [text()]) : h('div', text)) }) };
}

/** 显示「前缀 + 当前历史记录项 state.from」的占位页，对应 `<div>登录入口：{useLocation().state?.from}</div>`。 */
export function stubFromPage(prefix: string): { default: Component } {
  return { default: defineComponent({ setup() {
    const route = useRoute(), router = useRouter();
    return () => {
      void route.fullPath;
      return h('div', `${prefix}${String(locationState(router).from ?? '')}`);
    };
  } }) };
}
