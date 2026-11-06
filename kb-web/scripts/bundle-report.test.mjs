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
  const input = fixture({ 'entry.js': { modules: ['list', 'detail', 'src/pages/AccountPage.tsx'] } });
  const report = bundleReport(input);
  report.scenarios.login = report.scenarios.entry;
  report.provenance = { node: 'same' };
  assert.throws(() => checkIsolation(report), /受保护页面/);
  assert.throws(() => compare(report, report, true), /未下降/);
  assert.throws(() => compare(report, { ...report, provenance: { node: 'different' } }), /基线条件不同/);
});
test('CLI 缺少分析文件时非零退出，不输出成功报告', () => {
  const result = spawnSync(process.execPath, ['scripts/bundle-report.mjs', '--analysis', 'missing-fixture-analysis'], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /包体分析失败/);
  assert.doesNotMatch(result.stdout, /报告已保存/);
});
