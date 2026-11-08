import { describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick } from 'vue';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { Session } from '../session';
import SessionHost from '../SessionHost.vue';
import { deferred, json, me, storage } from '../test/fixtures';
import { renderHost } from '../test/render';
import AdminAccessProvider from './AdminAccessProvider.vue';
import { canOpenSpaces, useAdminAccess } from './access';
const Probe = defineComponent({ setup() {
  const access = useAdminAccess();
  return () => [h('output', `${access.status}:${access.spaceStatus}:${access.spaces.map(s => s.spaceId).join(',')}:${String(access.canManageSpace('9007199254740993'))}:${String(canOpenSpaces(access))}`), h('button', { onClick: () => access.check(true) }, '重读')];
} });
const settle = async () => { await Promise.resolve(); await nextTick(); };
function fixture() {
  const permissions: ReturnType<typeof deferred<Response>>[] = [], spaces: ReturnType<typeof deferred<Response>>[] = [];
  const transport = vi.fn((input: RequestInfo | URL) => {
    if (String(input) === '/api/auth/me') return Promise.resolve(json(me));
    const reply = deferred<Response>(); (String(input) === '/api/auth/permissions' ? permissions : spaces).push(reply); return reply.promise;
  });
  const session = new Session(storage(), transport as typeof fetch);
  renderHost(SessionHost, { props: { session }, slots: { default: () => h(AdminAccessProvider, null, () => h(Probe)) } });
  return { permissions, spaces, session };
}
describe('可管理空间事实', () => {
  it('两个来源并行且独立，保留精确 ID，仅收录 manageable', async () => {
    const h = fixture(); await waitFor(() => expect(h.session.getSnapshot().phase).toBe('ready'));
    await waitFor(() => expect(h.permissions.length).toBeGreaterThan(0)); expect(h.spaces.length).toBe(h.permissions.length);
    h.spaces.at(-1)!.resolve(json([{ spaceId: 9007199254740993n, manageable: true }, { spaceId: 7, manageable: false }]));
    expect(await screen.findByText('loading:ready:9007199254740993:true:true')).toBeTruthy();
    h.permissions.at(-1)!.resolve(new Response(null, { status: 503 }));
    expect(await screen.findByText('unavailable:ready:9007199254740993:true:true')).toBeTruthy();
    await fireEvent.click(screen.getByText('重读')); expect(screen.getByText('loading:loading::false:false')).toBeTruthy();
    h.permissions.at(-1)!.resolve(json(['space:create'])); h.spaces.at(-1)!.resolve(new Response(null, { status: 503 }));
    expect(await screen.findByText('ready:unavailable::false:true')).toBeTruthy();
  });
  it('旧 sequence、旧 epoch 和失败都不会恢复旧空间', async () => {
    const h = fixture(); await waitFor(() => expect(h.session.getSnapshot().phase).toBe('ready'));
    const older = h.spaces.at(-1)!;
    await fireEvent.click(screen.getByText('重读'));
    h.permissions.at(-1)!.resolve(json([])); h.spaces.at(-1)!.resolve(json([]));
    await screen.findByText('ready:ready::false:false');
    older.resolve(json([{ spaceId: 9007199254740993n, manageable: true }])); await settle();
    expect(screen.getByText('ready:ready::false:false')).toBeTruthy();
    await fireEvent.click(screen.getByText('重读')); const beforeSuspend = h.spaces.at(-1)!;
    h.session.suspend(); await nextTick();
    beforeSuspend.resolve(json([{ spaceId: 9007199254740993n, manageable: true }])); await settle();
    expect(screen.getByText('loading:loading::false:false')).toBeTruthy();
  });
});
