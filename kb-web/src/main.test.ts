import type { App } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { screen } from '@testing-library/vue';
import { expect, it, vi } from 'vitest';

vi.mock('./SessionProvider', () => ({ useSession: () => ({ state: { epoch: 1 } }) }));
vi.mock('./App.vue', async () => {
  const { defineComponent, h } = await import('vue');
  const { default: PageLoadBoundary } = await import('./routing/PageLoadBoundary.vue');
  const BrokenPage = defineComponent({ setup: () => () => { throw new Error('private-entry-error-and-resource-url'); } });
  return { default: defineComponent({ setup: () => () => h(PageLoadBoundary, null, () => h(BrokenPage)) }) };
});

it('真实入口注册安全诊断回调，错误边界接管后不输出原始异常', async () => {
  const rootElement = document.createElement('div'); rootElement.id = 'root'; document.body.append(rootElement);
  vi.spyOn(console, 'error').mockImplementation(() => {});
  try {
    await import('./main'); await flushPromises();
    expect(screen.getByRole('heading', { name: '页面显示异常' })).toBeTruthy();
    expect(console.error).toHaveBeenCalledExactlyOnceWith('PAGE_RENDER_FAILED');
    expect(rootElement.textContent).not.toContain('private-entry-error-and-resource-url');
  } finally {
    (rootElement as Element & { __vue_app__?: App }).__vue_app__?.unmount();
    rootElement.remove();
  }
});
