import { markRaw, shallowReactive } from 'vue';
import { liveView } from '../liveView';
import { emptyAdminAccess, type AdminAccess } from '../stores/adminAccess';

const fake = shallowReactive<{ value: AdminAccess | null }>({ value: null });

/**
 * 对应 React 测试中的 `<AdminAccessContext value={...}>`：设置后 `useAdminAccess()` 返回该值，修改即驱动组件更新；
 * 传 null 恢复真实 store。需配合 `vi.mock('./access', async original => (await import('../test/access')).withFakeAccess(await original()))`。
 */
export function provideAccess(value: AdminAccess | null) {
  fake.value = value ? markRaw(value) : null;
}

export function withFakeAccess<T extends { useAdminAccess: () => AdminAccess }>(actual: T): T {
  return {
    ...actual,
    useAdminAccess: () => (fake.value ? liveView(() => fake.value ?? emptyAdminAccess) : actual.useAdminAccess()),
  };
}
