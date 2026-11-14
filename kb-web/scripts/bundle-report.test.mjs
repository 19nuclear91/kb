import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { bundleReport, checkIsolation, compare } from './bundle-report.mjs';

function fixture(graph) {
  const manifest = {}, chunks = {}, assets = {}, data = {};
  for (const [file, config] of Object.entries(graph)) {
    const { imports = [], dynamicImports = [], css = [], modules = [file], entry = file === 'entry.js' } = config;
    manifest[file] = { file, imports, dynamicImports, css };
    chunks[file] = { imports, dynamicImports, css, modules, entry };
    for (const asset of [file, ...css]) {
      data[asset] = Buffer.from(asset.repeat(40));
      assets[asset] = createHash('sha256').update(data[asset]).digest('hex');
    }
  }
  return { manifest, analysis: { schema: 1, chunks, assets }, html: '<script type="module" src="/entry.js"></script>',
    readAsset: file => data[file] ?? assert.fail(`资源不存在 ${file}`), routeModules: { documents: ['list'], detail: ['detail'] } };
}
test('旧单包路由计入入口，JS/CSS 分开且按文件压缩', () => {
  const report = bundleReport(fixture({ 'entry.js': { modules: ['list', 'detail'], css: ['style.css'] } }));
  assert.deepEqual(report.scenarios.documents.files, ['entry.js', 'style.css']);
  assert.deepEqual(report.scenarios.detail.total, report.scenarios.entry.total);
  assert.equal(report.scenarios.documents.incremental.total.all.files, 0);
  assert.equal(report.scenarios.entry.total.js.files, 1);
  assert.equal(report.scenarios.entry.total.css.files, 1);
  assert.equal(report.scenarios.entry.total.all.gzip, report.files['entry.js'].gzip + report.files['style.css'].gzip);
});
test('菱形依赖与环按文件去重，动态页面不进入入口', () => {
  const input = fixture({
    'entry.js': { imports: ['left.js', 'right.js'], dynamicImports: ['list.js', 'detail.js'] },
    'left.js': { imports: ['shared.js'] }, 'right.js': { imports: ['shared.js'] },
    'shared.js': { imports: ['left.js'], css: ['shared.css'] },
    'list.js': { modules: ['list'], imports: ['shared.js'] }, 'detail.js': { modules: ['detail'], imports: ['shared.js'] },
  });
  const report = bundleReport(input);
  assert.equal(report.scenarios.entry.total.all.files, 5);
  assert.deepEqual(report.scenarios.documents.incremental.files, ['list.js']);
  assert.deepEqual(report.scenarios.detail.incremental.files, ['detail.js']);
  input.html += '<link rel="modulepreload" href="/list.js">';
  assert.equal(bundleReport(input).scenarios.documents.incremental.total.all.files, 0);
});
test('缺少资源、映射、路由、入口或不一致构建必须失败', () => {
  const make = () => fixture({ 'entry.js': { modules: ['list', 'detail'], css: ['style.css'] } });
  let input = make(); delete input.analysis.assets['style.css']; assert.throws(() => bundleReport(input), /缺少 JS\/CSS/);
  input = make(); input.analysis.chunks['entry.js'].modules = []; assert.throws(() => bundleReport(input), /缺少模块映射/);
  input = make(); input.analysis.chunks['entry.js'].modules = ['list']; assert.throws(() => bundleReport(input), /缺少路由/);
  input = make(); input.html = ''; assert.throws(() => bundleReport(input), /缺少有效入口/);
  input = make(); input.manifest['entry.js'].imports = ['missing.js']; assert.throws(() => bundleReport(input), /缺少依赖/);
  input = make(); input.analysis.assets['entry.js'] = 'changed'; assert.throws(() => bundleReport(input), /资源与分析不一致/);
  input = make(); input.readAsset = () => { throw new Error('ENOENT'); }; assert.throws(() => bundleReport(input), /ENOENT/);
});
test('结构和基线门槛不会把无改善视为成功', () => {
  const input = fixture({ 'entry.js': { modules: ['list', 'detail', 'src/pages/AccountPage.vue'] } });
  const report = bundleReport(input);
  report.scenarios.login = report.scenarios.entry;
  report.provenance = { node: 'same' };
  assert.throws(() => checkIsolation(report), /受保护页面/);
  assert.throws(() => compare(report, report, { requireReduction: true }), /未下降/);
  assert.throws(() => compare(report, { ...report, provenance: { node: 'different' } }), /基线条件不同/);
});
test('Vue 并行路径与 SFC 子模块参与路由归属及隔离，最终路径规则一致', () => {
  for (const source of ['src-vue', 'src']) {
    const input = fixture({
      'entry.js': { modules: [`${source}/main.ts`], dynamicImports: ['list.js', 'detail.js'] },
      'list.js': { modules: [`${source}/pages/DocumentsPage.vue?vue&type=script&setup=true&lang.ts`] },
      'detail.js': { modules: [`${source}/pages/DocumentDetailPage.vue`] },
    });
    input.routeModules = { documents: ['src/pages/DocumentsPage.vue'], detail: ['src/pages/DocumentDetailPage.vue'] };
    const report = bundleReport(input); report.scenarios.login = report.scenarios.entry;
    assert.deepEqual(report.scenarios.documents.incremental.files, ['list.js']);
    assert.equal(report.files['list.js'].modules.length, 1);
    checkIsolation(report);
    report.files['list.js'].modules.push(`${source}/office/VersionPreview.vue?vue&type=script&lang.ts`);
    assert.throws(() => checkIsolation(report), /详情专用/);
    report.files['entry.js'].modules.push(`${source}/sso/BindingPanel.vue`);
    assert.throws(() => checkIsolation(report), /账户绑定/);
  }
});
test('管理与普通页面不能交叉内联，员工生命周期同样受入口隔离约束', () => {
  const report = bundleReport(fixture({ 'entry.js': { modules: ['list', 'detail'] } }));
  report.scenarios.login = report.scenarios.entry;
  report.files['entry.js'].modules.push('src-vue/admin/lifecycle/LifecyclePage.vue');
  assert.throws(() => checkIsolation(report), /受保护页面/);
  report.files['entry.js'].modules = [];
  report.files['user.js'] = { modules: ['src/pages/SearchPage.vue'] };
  report.scenarios.users = { files: ['user.js'] };
  assert.throws(() => checkIsolation(report), /交叉内联/);
});
test('跨栈比较须显式允许锁变化，其他构建条件仍严格匹配', () => {
  const before = bundleReport(fixture({ 'entry.js': { modules: ['list', 'detail'] } }));
  before.scenarios.login = before.scenarios.entry;
  before.provenance = { lockSha256: 'react-lock', node: '24', npm: '11', mode: 'production', publicFlags: { feature: null } };
  const after = structuredClone(before); after.provenance.lockSha256 = 'vue-lock';
  assert.throws(() => compare(after, before), /lockSha256/);
  compare(after, before, { allowLockChange: true });
  for (const key of ['node', 'npm', 'mode', 'publicFlags']) {
    const changed = structuredClone(after); changed.provenance[key] = 'changed';
    assert.throws(() => compare(changed, before, { allowLockChange: true }), new RegExp(key));
  }
  after.compression.gzipLevel = 1;
  assert.throws(() => compare(after, before, { allowLockChange: true }), /压缩条件/);
});
test('10% 包体边界精确计算，新场景只记录且不能豁免门槛场景或丢失原场景', () => {
  const before = bundleReport(fixture({ 'entry.js': { modules: ['list', 'detail'] } }));
  before.scenarios.login = before.scenarios.entry; before.provenance = {};
  for (const name of ['entry', 'login', 'documents']) before.scenarios[name].total.all.gzip = 100;
  const after = structuredClone(before);
  for (const name of ['entry', 'login', 'documents']) after.scenarios[name].total.all.gzip = 110;
  compare(after, before, { checkGrowth: true });
  after.scenarios.documents.total.all.gzip++;
  assert.throws(() => compare(after, before, { checkGrowth: true }), /超过基线 10%/);
  after.scenarios.lifecycle = after.scenarios.detail;
  assert.throws(() => compare(after, before), /基线缺少场景: lifecycle/);
  assert.equal(compare(after, before, { newScenarios: ['lifecycle'] }).lifecycle.gzip.delta, null);
  const missing = structuredClone(before); delete missing.scenarios.login;
  assert.throws(() => compare(after, missing, { newScenarios: ['login', 'lifecycle'] }), /基线缺少场景: login/);
  delete after.scenarios.detail;
  assert.throws(() => compare(after, before, { newScenarios: ['lifecycle'] }), /候选缺少场景: detail/);
});
test('CLI 缺少分析文件时非零退出，不输出成功报告', () => {
  const result = spawnSync(process.execPath, ['scripts/bundle-report.mjs', '--analysis', 'missing-fixture-analysis'], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /包体分析失败/);
  assert.doesNotMatch(result.stdout, /报告已保存/);
});
test('知识需求路由和反馈单独统计，入口只允许最小地址助手而非业务模块', () => {
  const input = fixture({
    'entry.js': { modules: ['src/main.ts', 'src/knowledgeGaps/routes.ts'], dynamicImports: ['list.js', 'gap.js', 'feedback.js'] },
    'list.js': { modules: ['src/pages/DocumentsPage.vue'] },
    'gap.js': { modules: ['src/pages/KnowledgeGapDetailPage.vue', 'src/knowledgeGaps/GapDetailWorkspace.vue'] },
    'feedback.js': { modules: ['src/knowledgeGaps/GapFeedbackDialog.vue', 'src/knowledgeGaps/GapCreateForm.vue'] },
  });
  input.routeModules = { documents: ['src/pages/DocumentsPage.vue'], knowledgeGapDetail: ['src/pages/KnowledgeGapDetailPage.vue'],
    knowledgeGapFeedback: ['src/knowledgeGaps/GapFeedbackDialog.vue'] };
  const report = bundleReport(input); report.scenarios.login = report.scenarios.entry;
  checkIsolation(report);
  assert.deepEqual(report.scenarios.knowledgeGapDetail.incremental.files, ['gap.js']);
  assert.deepEqual(report.scenarios.knowledgeGapFeedback.incremental.files, ['feedback.js']);
  for (const id of ['src/knowledgeGaps/api.ts', 'src/knowledgeGaps/GapCreateForm.vue', 'src/knowledgeGaps/GapDetailWorkspace.vue',
    'src-vue/knowledgeGaps/GapFeedbackDialog.vue?vue&type=script&setup=true&lang.ts']) {
    for (const location of ['entry.js', 'list.js']) {
      const changed = structuredClone(report); changed.files[location].modules.push(id);
      const message = location === 'entry.js' && id.includes('GapFeedbackDialog.vue')
        ? /入口包含受保护页面实现/ : /提前包含知识问题/;
      assert.throws(() => checkIsolation(changed), message);
    }
  }
});
