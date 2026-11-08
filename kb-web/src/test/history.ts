import { createMemoryHistory, type HistoryState, type RouterHistory } from 'vue-router';

/**
 * 内存 history 不保存导航 state（vue-router 的实现中 state 恒为空对象），而登录返回地址经 state.from 传递。
 * 测试包装按位置记录 state，与生产使用的 createWebHistory（state 即 window.history.state）读法一致。
 */
export function createStatefulMemoryHistory(): RouterHistory {
  const base = createMemoryHistory();
  const states: HistoryState[] = [{}];
  let index = 0;
  const clamp = (value: number) => Math.max(0, Math.min(value, states.length - 1));
  return {
    get location() { return base.location; },
    get state() { return states[index]; },
    get base() { return base.base; },
    createHref: href => base.createHref(href),
    push(to, state) {
      base.push(to, state);
      states.splice(index + 1);
      states.push(state ?? {});
      index = states.length - 1;
    },
    replace(to, state) {
      base.replace(to, state);
      states[index] = state ?? {};
    },
    go(delta, triggerListeners) {
      index = clamp(index + delta);
      base.go(delta, triggerListeners);
    },
    listen: callback => base.listen(callback),
    destroy() {
      base.destroy();
      states.splice(0, states.length, {});
      index = 0;
    },
  };
}
