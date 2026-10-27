import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Navigation } from './App';
import { conversationEnabled, safeReturnPath } from './navigation';

describe('用户端路由', () => {
  it('按初始地址选择导航', () => {
    render(<MemoryRouter initialEntries={['/search']}><Navigation /></MemoryRouter>);
    expect(screen.getByRole('link', { name: /知识搜索/ }).getAttribute('aria-current')).toBe('page');
  });
  it('通知页只接受固定站内返回地址，拒绝查询、身份参数和站外地址', () => {
    expect(safeReturnPath('/notifications')).toBe('/notifications');
    for (const path of ['/notifications?userId=8', '/notifications/1', '/notifications/', '//notifications', 'https://evil.test/notifications', '/notifications#x'])
      expect(safeReturnPath(path)).toBe('/documents');
    render(<MemoryRouter initialEntries={['/notifications']}><Navigation /></MemoryRouter>);
    expect(screen.getByRole('link', { name: /站内通知/ }).getAttribute('aria-current')).toBe('page');
  });
  it('只恢复明确站内页面', () => {
    for (const path of ['/documents/7', '/assistant/9', '/account', '/search']) expect(safeReturnPath(path)).toBe(path);
    for (const path of ['https://bad.test', '//bad.test', '/login', '/documents?query=secret', '/unknown']) expect(safeReturnPath(path)).toBe('/documents');
  });
  it('管理返回地址只接受明确站内路由', () => {
    for (const path of ['/admin/users', '/admin/departments', '/admin/roles', '/admin/audits']) expect(safeReturnPath(path)).toBe(path);
    for (const path of ['/admin', '/admin/', '/admin/users/', '/admin/users/1', '/admin/userss', '/admin/audits?requestId=private', '/admin/audits/other', '/admin/users?q=secret', '/admin/users#x', '/admin/../users', '//admin/users', '/ADMIN/USERS', '/admin/roles/../users'])
      expect(safeReturnPath(path)).toBe('/documents');
  });
  it('会话缺省关闭且只接受明确 true', () => {
    expect(conversationEnabled(undefined)).toBe(false);
    expect(conversationEnabled('false')).toBe(false);
    expect(conversationEnabled('true')).toBe(true);
  });
});
