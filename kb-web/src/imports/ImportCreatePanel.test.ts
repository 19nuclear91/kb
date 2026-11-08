import { mount, flushPromises } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { expect, it, vi } from 'vitest';
import { useSessionStore } from '../stores/session';
import type { Session } from '../session';
import { importApi } from './api';
import ImportCreatePanel from './ImportCreatePanel.vue';

it('确认创建后在原页面交接文件；读取核对成功需主动继续', async () => {
  const pinia = createPinia();
  useSessionStore(pinia).bind({ getSnapshot: () => ({ epoch: 1, phase: 'ready', me: null }), subscribe: () => () => {} } as unknown as Session);
  const create = vi.spyOn(importApi, 'create').mockRejectedValue(new Error('lost reply'));
  const resolve = vi.spyOn(importApi, 'resolve').mockResolvedValue('9');
  const wrapper = mount(ImportCreatePanel, { props: { capabilities: { protocolVersion: 1, maxItems: 2, maxTotalBytes: 10, maxFileBytes: 10,
    maxManifestBytes: 1000, uploadConcurrency: 2, extensions: ['txt'] }, spaces: [], levels: [0], moreSpaces: false }, global: { plugins: [pinia],
    stubs: { ImportDraft: { name: 'ImportDraft', template: '<div />' }, ImportActive: { name: 'ImportActive', props: ['importId', 'files', 'autoStart'], template: '<div>活动任务 {{ importId }}</div>' } } } });
  try {
    const file = new File(['hello'], 'x.txt');
    wrapper.findComponent({ name: 'ImportDraft' }).vm.$emit('prepared', { spaceId: '1', securityLevel: 0,
      items: [{ itemKey: 'one', fileName: 'x.txt', title: '标题', sizeBytes: 5, sha256: 'a'.repeat(64) }] }, new Map([['one', file]]));
    await flushPromises(); expect(wrapper.findComponent({ name: 'ImportActive' }).exists()).toBe(false);
    await wrapper.findAll('button').find(button => button.text() === '只读核对创建结果')!.trigger('click'); await flushPromises();
    const active = wrapper.findComponent({ name: 'ImportActive' });
    expect(active.props('importId')).toBe('9'); expect(active.props('files').get('one')).toBe(file); expect(active.props('autoStart')).toBe(false);
    expect(create).toHaveBeenCalledTimes(1);
  } finally { wrapper.unmount(); create.mockRestore(); resolve.mockRestore(); }
});
