import { expect, it } from 'vitest';
import { screen } from '@testing-library/vue';
import { renderHost } from '../test/render';
import DiagnosticLayer from './DiagnosticLayer.vue';
import { diagnosticFixture } from './fixtures';

it('通用身份与密级原因分别显示，来源标签不执行 HTML，无正文或版本入口', () => {
  const result = diagnosticFixture({ kind: 'documents', id: '20', spaceId: '10', userId: '8' });
  const blocked = structuredClone(result.before); blocked.subjectCondition = 'BLOCKED';
  for (const row of [...blocked.spacePermissions, ...blocked.documentPermissions!]) {
    row.grantMatch = 'NOT_EVALUATED'; row.scopeCondition = 'NOT_EVALUATED'; row.reasonCodes = ['SUBJECT_BLOCKED'];
  }
  blocked.currentVersionAccess.status = 'NOT_EVALUATED';
  const view = renderHost(DiagnosticLayer, { props: { layer: blocked, title: '当前' } });
  expect(screen.getByText('账号当前不满足访问条件；授权来源和密级条件未评估。')).toBeTruthy();
  view.unmount();
  result.before.sources = [{ key: 'DOCUMENT:1:8:view', sourceKind: 'DOCUMENT', principalType: 'USER', principalId: '8', label: '<img src=x onerror=alert(1)>', permission: 'view', origin: 'EXISTING' }];
  result.before.documentPermissions![0] = { permission: 'view', grantMatch: 'MATCHED', scopeCondition: 'BLOCKED', reasonCodes: ['CLEARANCE_BLOCKED'], sourceKeys: ['DOCUMENT:1:8:view'] };
  result.before.currentVersionAccess = { status: 'ASSESSED', scope: 'CURRENT_PUBLISHED', view: 'DENIED', download: 'DENIED', viewReasonCodes: ['CLEARANCE_BLOCKED'], downloadReasonCodes: ['CLEARANCE_BLOCKED', 'DOWNLOAD_NOT_GRANTED'] };
  const checked = renderHost(DiagnosticLayer, { props: { layer: result.before, title: '若提交后' } });
  expect(screen.getByText(/<img src=x/)).toBeTruthy(); expect(checked.container.querySelector('img')).toBeNull();
  expect(screen.getAllByText(/密级条件未通过/).length).toBeGreaterThan(0); expect(screen.queryByRole('link')).toBeNull();
  expect(screen.queryByRole('button')).toBeNull();
});
