import { flushPromises, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { defineComponent, h, ref } from 'vue';
import References from '../ui/References.vue';
import { pairKey, sourcePair, ValidityBatch, type Badge } from './api';
import type { Session } from '../session';
import { encodeJson } from '../api';

const citation = { docId: '1', versionId: '2', chunkId: '3', docTitle: '固定版本资料' };
const badge: Badge = { documentId: '1', versionId: '2', state: 'EXPIRED', effectiveFrom: null, untilKind: 'DATE', effectiveUntil: '2026-12-31', corrected: false, current: false };
it('实时和历史引用使用同一标识，显示非当前版本和模型不感知说明', () => {
  for (const title of ['实时资料', '历史资料']) {
    const wrapper = mount(References, { props: { citations: [{ ...citation, docTitle: title }], validity: new Map([[pairKey(badge), badge]]) } });
    expect(wrapper.text()).toContain('已届满'); expect(wrapper.text()).toContain('非当前版本'); expect(wrapper.text()).toContain('模型不感知适用期'); wrapper.unmount();
  }
});
it('版本未知和标识故障保留引用，不伪装为未声明', () => {
  expect(sourcePair({ ...citation, versionId: null })).toBeNull();
  const wrapper = mount(References, { props: { citations: [{ ...citation, versionId: null }], validity: new Map() } });
  expect(wrapper.text()).toContain('版本未知'); expect(wrapper.text()).not.toContain('未声明'); expect(wrapper.text()).toContain('固定版本资料'); wrapper.unmount();
});
it('切换会话后旧标识迟到被丢弃，503 不影响引用正文', async () => {
  let resolve!: (response: Response) => void;
  const session = { getSnapshot: () => ({ epoch: 1 }), send: () => new Promise<Response>(done => { resolve = done; }) } as unknown as Session;
  const batch = new ValidityBatch(session), pending = batch.load([badge]); batch.clear();
  resolve(new Response(encodeJson({ code: 200, data: { items: [badge], asOfDate: '2027-01-01' } }), { headers: { 'Content-Type': 'application/json' } }));
  expect(await pending).toBeNull();
  const failed = new ValidityBatch({ getSnapshot: session.getSnapshot, send: async () => new Response(null, { status: 503 }) } as unknown as Session);
  await expect(failed.load([badge])).rejects.toMatchObject({ status: 503 });
  const wrapper = mount(References, { props: { citations: [citation], validity: new Map() } }); await flushPromises(); expect(wrapper.text()).toContain('固定版本资料'); wrapper.unmount();
});
