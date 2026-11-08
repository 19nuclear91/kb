import type { Router } from 'vue-router';

/** 当前历史记录项携带的导航 state（如登录返回地址 from）；不写入 URL。 */
export function locationState(router: Router): Record<string, unknown> {
  const state = router.options.history.state;
  return state && typeof state === 'object' ? state as Record<string, unknown> : {};
}
