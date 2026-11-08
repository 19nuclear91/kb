import { describe, expect, it, vi } from 'vitest';
import { defineAsyncComponent, defineComponent, h, nextTick, onErrorCaptured, onMounted, ref, watch } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, render, screen } from '@testing-library/vue';
import { boundaryTakesOver, errorSource } from './errorSource';

/** 记录 errorCaptured 收到的 info 并按分类决定是否接管，接管时阻止继续传播。 */
function capture(child: () => ReturnType<typeof h>) {
  const seen: { info: string; taken: boolean }[] = [];
  const Boundary = defineComponent({ setup() {
    onErrorCaptured((_, __, info) => {
      const taken = boundaryTakesOver(info);
      seen.push({ info, taken });
      return taken ? false : undefined;
    });
    return () => child();
  } });
  return { seen, Boundary };
}
const boom = () => { throw new Error('boom'); };

describe('错误来源分类：开发构建的文字 info', () => {
  it('渲染错误被接管', () => {
    const { seen, Boundary } = capture(() => h({ render: boom }));
    render(Boundary); expect(seen).toEqual([{ info: 'render function', taken: true }]);
  });
  it('setup 错误被接管', () => {
    const { seen, Boundary } = capture(() => h({ setup: boom }));
    render(Boundary); expect(seen).toEqual([{ info: 'setup function', taken: true }]);
  });
  it('生命周期钩子错误被接管', () => {
    const { seen, Boundary } = capture(() => h({ setup() { onMounted(boom); return () => null; } }));
    render(Boundary); expect(seen).toEqual([{ info: 'mounted hook', taken: true }]);
  });
  it('异步组件加载器错误被接管', async () => {
    const { seen, Boundary } = capture(() => h(defineAsyncComponent({ loader: () => Promise.reject(new Error('chunk')), delay: 0 })));
    render(Boundary); await flushPromises(); expect(seen).toEqual([{ info: 'async component loader', taken: true }]);
  });
  it('事件处理器错误不被接管，继续传播', async () => {
    const unhandled = vi.fn();
    const { seen, Boundary } = capture(() => h({ render: () => h('button', { onClick: boom }, '操作') }));
    render(Boundary, { global: { config: { errorHandler: unhandled } } });
    await fireEvent.click(screen.getByRole('button', { name: '操作' }));
    expect(seen).toEqual([{ info: 'native event handler', taken: false }]); expect(unhandled).toHaveBeenCalledOnce();
  });
  it('watcher 回调错误不被接管，继续传播', async () => {
    const unhandled = vi.fn(), value = ref(0);
    const { seen, Boundary } = capture(() => h({ setup() { watch(value, boom); return () => null; } }));
    render(Boundary, { global: { config: { errorHandler: unhandled } } });
    value.value = 1; await nextTick();
    expect(seen).toEqual([{ info: 'watcher callback', taken: false }]); expect(unhandled).toHaveBeenCalledOnce();
  });
});

describe('错误来源分类：生产构建的代码 info', () => {
  const production = (code: string) => `https://vuejs.org/error-reference/#runtime-${code}`;
  it.each([['1', '渲染'], ['0', 'setup'], ['m', '生命周期'], ['bum', '卸载前钩子'], ['13', '异步组件加载器']])('代码 %s（%s）被接管', code => {
    expect(errorSource(production(code))).toBe(code); expect(boundaryTakesOver(production(code))).toBe(true);
  });
  it.each([['5', '原生事件处理器'], ['6', '组件事件处理器'], ['3', 'watcher 回调'], ['2', 'watcher getter'], ['8', '指令钩子']])('代码 %s（%s）不被接管', code => {
    expect(boundaryTakesOver(production(code))).toBe(false);
  });
  it('无法识别的来源不接管', () => {
    expect(errorSource('unknown')).toBeNull(); expect(boundaryTakesOver('unknown')).toBe(false); expect(boundaryTakesOver(production('99'))).toBe(false);
  });
});
