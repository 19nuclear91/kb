import { expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/vue';
import { chooseOption, closeDialog, currentDialog, renderWith, selectOptions } from './render';
import HelperFixture from './HelperFixture.vue';

it('测试辅助以内存路由渲染，可选择下拉项并在关闭弹窗后立即卸载正文', async () => {
  const view = await renderWith(HelperFixture, { path: '/documents/7', props: { title: '甲' } });
  expect(view.router.currentRoute.value.fullPath).toBe('/documents/7');
  expect(screen.getByText('标题：甲')).toBeTruthy();
  await view.setProps({ title: '乙' });
  expect(screen.getByText('标题：乙')).toBeTruthy();

  const dialog = currentDialog();
  expect(dialog.getAttribute('aria-label')).toBe('确认操作');
  expect(await selectOptions('空间', dialog)).toEqual(['甲空间', '乙空间']);
  await chooseOption('空间', '乙空间', dialog);
  expect(screen.getByText('已选：2')).toBeTruthy();

  await closeDialog();
  expect(screen.queryByText('已选：2')).toBeNull();
  expect(document.querySelector('[role="dialog"]')).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '打开' }));
  expect(currentDialog().textContent).toContain('已选：2');
});
