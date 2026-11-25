import { expect, it } from 'vitest';
import { canOpenDocuments, canOpenSpaces, perm } from '../admin/access';
import { emptyAdminAccess } from '../stores/adminAccess';

it('诊断功能权限不能单独开放空间或文档管理范围', () => {
  const access = { ...emptyAdminAccess, status: 'ready' as const, spaceStatus: 'ready' as const,
    permissions: [perm.diagnose], has: (code: string) => code === perm.diagnose };
  expect(canOpenSpaces(access)).toBe(false);
  expect(canOpenDocuments(access)).toBe(false);
  expect(access.has(perm.spaceManageAll)).toBe(false);
  expect(access.has(perm.grantManage)).toBe(false);
});
