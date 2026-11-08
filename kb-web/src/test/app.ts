import { defineComponent, h, type VNodeChild } from 'vue';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, useRoute, useRouter, type HistoryState, type RouteLocationNormalizedLoaded, type RouteRecordRaw } from 'vue-router';
import { ElConfigProvider } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { render } from '@testing-library/vue';
import { locationState } from '../routing/locationState';
import { routes as appRoutes } from '../routing/routes';
import { createStatefulMemoryHistory } from './history';

/**
 * 以应用路由表与内存 history 同步挂载任意内容，对应 React 测试中的 <MemoryRouter initialEntries={[path]}>。
 * vue-router 的首次导航是异步的；这里直接以解析后的初始位置作为当前路由（应用路由表没有全局守卫与懒加载记录），
 * 安装时路由器不再发起首次导航，断言能看到首帧（如“正在确认管理权限…”）。组件内重定向仍是异步导航，需用 findBy 等待。
 */
export function mountRouter(content: () => VNodeChild, { path = '/', state, routes = appRoutes }: { path?: string; state?: HistoryState; routes?: RouteRecordRaw[] } = {}) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const history = createStatefulMemoryHistory();
  history.replace(path, state);
  const router = createRouter({ history, routes });
  router.currentRoute.value = router.resolve(path) as RouteLocationNormalizedLoaded;
  const Root = defineComponent({ setup: () => () => h(ElConfigProvider, { locale: zhCn }, content) });
  const view = render(Root, { global: { plugins: [pinia, router] } });
  return { ...view, router, pinia };
}

/** 显示当前路径，对应 React 测试中的 useLocation().pathname 探针。 */
export const LocationProbe = defineComponent({ setup() { const route = useRoute(); return () => h('output', route.path); } });
/** 显示当前历史记录项 state.from，对应 useLocation().state?.from 探针。 */
export const FromProbe = defineComponent({ props: { prefix: { type: String, default: 'from:' } }, setup(props) {
  const route = useRoute(), router = useRouter();
  return () => { void route.fullPath; return h('output', `${props.prefix}${String(locationState(router).from ?? '')}`); };
} });
