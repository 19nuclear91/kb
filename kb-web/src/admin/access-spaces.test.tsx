import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Session } from '../session';
import { SessionProvider } from '../SessionProvider';
import { deferred, json, me, storage } from '../test/fixtures';
import { AdminAccessProvider, canOpenSpaces, useAdminAccess } from './access';
function Probe() {
  const access = useAdminAccess();
  return <><output>{access.status}:{access.spaceStatus}:{access.spaces.map(s => s.spaceId).join(',')}:{String(access.canManageSpace('9007199254740993'))}:{String(canOpenSpaces(access))}</output><button onClick={() => access.check(true)}>重读</button></>;
}
function fixture() {
  const permissions: ReturnType<typeof deferred<Response>>[] = [], spaces: ReturnType<typeof deferred<Response>>[] = [];
  const transport = vi.fn((input: RequestInfo | URL) => {
    if (String(input) === '/api/auth/me') return Promise.resolve(json(me));
    const reply = deferred<Response>(); (String(input) === '/api/auth/permissions' ? permissions : spaces).push(reply); return reply.promise;
  });
  const session = new Session(storage(), transport as typeof fetch);
  render(<SessionProvider session={session}><AdminAccessProvider><Probe /></AdminAccessProvider></SessionProvider>);
  return { permissions, spaces, session };
}
describe('可管理空间事实', () => {
  it('两个来源并行且独立，保留精确 ID，仅收录 manageable', async () => {
    const h = fixture(); await waitFor(() => expect(h.session.getSnapshot().phase).toBe('ready'));
    await waitFor(() => expect(h.permissions.length).toBeGreaterThan(0)); expect(h.spaces.length).toBe(h.permissions.length);
    await act(async () => h.spaces.at(-1)!.resolve(json([{ spaceId: 9007199254740993n, manageable: true }, { spaceId: 7, manageable: false }])));
    expect(screen.getByText('loading:ready:9007199254740993:true:true')).toBeTruthy();
    await act(async () => h.permissions.at(-1)!.resolve(new Response(null, { status: 503 })));
    expect(screen.getByText('unavailable:ready:9007199254740993:true:true')).toBeTruthy();
    fireEvent.click(screen.getByText('重读')); expect(screen.getByText('loading:loading::false:false')).toBeTruthy();
    await act(async () => { h.permissions.at(-1)!.resolve(json(['space:create'])); h.spaces.at(-1)!.resolve(new Response(null, { status: 503 })); });
    expect(screen.getByText('ready:unavailable::false:true')).toBeTruthy();
  });
  it('旧 sequence、旧 epoch 和失败都不会恢复旧空间', async () => {
    const h = fixture(); await waitFor(() => expect(h.session.getSnapshot().phase).toBe('ready'));
    const older = h.spaces.at(-1)!;
    fireEvent.click(screen.getByText('重读'));
    await act(async () => { h.permissions.at(-1)!.resolve(json([])); h.spaces.at(-1)!.resolve(json([])); });
    await act(async () => older.resolve(json([{ spaceId: 9007199254740993n, manageable: true }])));
    expect(screen.getByText('ready:ready::false:false')).toBeTruthy();
    fireEvent.click(screen.getByText('重读')); const beforeSuspend = h.spaces.at(-1)!;
    act(() => h.session.suspend());
    await act(async () => beforeSuspend.resolve(json([{ spaceId: 9007199254740993n, manageable: true }])));
    expect(screen.getByText('loading:loading::false:false')).toBeTruthy();
  });
});
