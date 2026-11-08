import { defineComponent, h, nextTick, reactive, type Component } from 'vue';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { createRouter, RouterView, type RouteRecordRaw } from 'vue-router';
import { ElConfigProvider } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { fireEvent, render, waitFor, within } from '@testing-library/vue';
import { createStatefulMemoryHistory } from './history';

export interface RenderOptions {
  /** 初始地址，默认 `/`。 */
  path?: string;
  /** 被测组件挂载的路由模式，需读取路由参数时指定，例如 `/documents/:documentId`。 */
  routePath?: string;
  /** 完整路由表；指定后忽略 component 与 routePath。 */
  routes?: RouteRecordRaw[];
  /** 传给被测组件的初始属性，可用 setProps 更新。 */
  props?: Record<string, unknown>;
  /** 挂载前准备 store（如注入 Session 替身）。 */
  setup?: (pinia: Pinia) => void;
}

/** 以内存路由、Pinia 与中文语言包挂载组件，替代 React 测试中的 MemoryRouter 包裹；等待首次导航完成后返回。 */
export async function renderWith(component: Component | null, options: RenderOptions = {}) {
  const pinia = createPinia();
  setActivePinia(pinia);
  options.setup?.(pinia);
  const props = reactive({ ...options.props });
  const routes: RouteRecordRaw[] = options.routes ?? [
    { path: options.routePath ?? '/:pathMatch(.*)*', component: component!, props: () => props },
    ...(options.routePath ? [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] : []),
  ];
  const history = createStatefulMemoryHistory();
  history.replace(options.path ?? '/');
  const router = createRouter({ history, routes });
  const Root = defineComponent({ setup: () => () => h(ElConfigProvider, { locale: zhCn }, () => h(RouterView)) });
  const result = render(Root, { global: { plugins: [pinia, router] } });
  await router.isReady();
  await nextTick();
  return {
    ...result, router, pinia,
    /** 更新被测组件属性并等待渲染。 */
    async setProps(next: Record<string, unknown>) { Object.assign(props, next); await nextTick(); },
  };
}

export type Rendered = Awaited<ReturnType<typeof renderWith>>;

/** 挂载只依赖 Pinia 的组件（不经路由），props 与 slots 按 Testing Library 约定传入。 */
export function renderHost(component: Component, options: { props?: Record<string, unknown>; slots?: Record<string, unknown> } = {}) {
  const pinia = createPinia();
  setActivePinia(pinia);
  return { ...render(component, { ...options, global: { plugins: [pinia] } } as Parameters<typeof render>[1]), pinia };
}

/** 对应 React 的 renderHook：组合式函数在宿主 setup 内执行一次，属性变化经 reactive 传入；wrapper 以默认插槽包裹宿主。 */
export function renderHook<T, P extends object = Record<string, never>>(hook: (props: P) => T, options: { initialProps?: P; wrapper?: Component } = {}) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const props = reactive({ ...options.initialProps }) as P;
  const result = { current: undefined as T };
  const Host = defineComponent({
    setup() {
      result.current = hook(props);
      return () => null;
    },
  });
  const wrapper = options.wrapper;
  const Root = wrapper ? defineComponent({ setup: () => () => h(wrapper, null, { default: () => h(Host) }) }) : Host;
  const view = render(Root, { global: { plugins: [pinia] } });
  return {
    result, pinia, unmount: view.unmount,
    async rerender(next: Partial<P> = {}) {
      Object.assign(props, next);
      await nextTick();
    },
  };
}

/** 当前最上层的弹窗或抽屉。 */
export function currentDialog(): HTMLElement {
  const dialogs = [...document.querySelectorAll<HTMLElement>('[role="dialog"]')];
  const last = dialogs.at(-1);
  if (!last) {
    throw new Error('当前没有打开的弹窗或抽屉');
  }
  return last;
}

/** 点击弹窗或抽屉标题栏的关闭按钮。 */
export async function closeDialog(dialog: HTMLElement = currentDialog()) {
  await fireEvent.click(within(dialog).getByRole('button', { name: '关闭此对话框' }));
}

function combobox(label: string, scope: HTMLElement): HTMLElement {
  const target = within(scope).getByRole('combobox', { name: label });
  if (target.getAttribute('role') !== 'combobox') {
    throw new Error(`${label} 不是 Element Plus 下拉选择`);
  }
  return target;
}

/** 打开 Element Plus 下拉并返回其选项列表。 */
export async function openSelect(label: string, scope: HTMLElement = document.body): Promise<HTMLElement> {
  const input = combobox(label, scope);
  if (input.getAttribute('aria-expanded') !== 'true') {
    await fireEvent.click(input.closest('.el-select__wrapper') ?? input);
  }
  return waitFor(() => {
    const list = document.getElementById(input.getAttribute('aria-controls') ?? '');
    if (!list) {
      throw new Error(`${label} 的选项列表未出现`);
    }
    return list;
  });
}

/** 当前下拉中可选项的文本。 */
export async function selectOptions(label: string, scope: HTMLElement = document.body): Promise<string[]> {
  const list = await openSelect(label, scope);
  return within(list).queryAllByRole('option').map(item => item.textContent?.trim() ?? '');
}

/** 在下拉中点击指定选项。 */
export async function chooseOption(label: string, option: string, scope: HTMLElement = document.body) {
  const list = await openSelect(label, scope);
  await fireEvent.click(await within(list).findByRole('option', { name: option }));
}
