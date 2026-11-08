import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import ImportDraft from './ImportDraft.vue';

it('多选保留同名和无效项，标题可编辑并可显式移除', async () => {
  const wrapper = mount(ImportDraft, { props: { capabilities: { protocolVersion: 1, maxItems: 3, maxTotalBytes: 20, maxFileBytes: 10, maxManifestBytes: 1000, uploadConcurrency: 2, extensions: ['txt'] },
    spaces: [], levels: [0, 8], locked: false, moreSpaces: false } });
  const input = wrapper.get('input[type=file]');
  Object.defineProperty(input.element, 'files', { value: [new File(['one'], 'same.txt'), new File(['two'], 'same.txt'), new File(['zip'], 'x.zip')] });
  await input.trigger('change');
  expect(wrapper.findAll('li')).toHaveLength(3); expect(wrapper.text()).toContain('文件格式不受支持');
  const title = wrapper.get('input[aria-label="第 1 项标题"]'); await title.setValue('新标题');
  expect((title.element as HTMLInputElement).value).toBe('新标题');
  const remove = wrapper.findAll('button').find(button => button.text().includes('移除第 3 项'))!; await remove.trigger('click');
  expect(wrapper.findAll('li')).toHaveLength(2); expect(wrapper.text()).not.toContain('x.zip'); wrapper.unmount();
});
