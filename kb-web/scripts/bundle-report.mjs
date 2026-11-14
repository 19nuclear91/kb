import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, relative, isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gzipSync, brotliCompressSync, constants } from 'node:zlib';

export const routes = {
  login: ['src/pages/LoginPage.vue'], password: ['src/pages/ChangePasswordPage.vue'],
  sso: ['src/pages/SsoResultPage.vue'], documents: ['src/pages/DocumentsPage.vue'],
  detail: ['src/pages/DocumentDetailPage.vue'], account: ['src/pages/AccountPage.vue'],
  search: ['src/pages/SearchPage.vue'], favorites: ['src/pages/FavoritesPage.vue'],
  reviews: ['src/pages/ReviewsPage.vue'], notifications: ['src/pages/NotificationsPage.vue'],
  imports: ['src/pages/ImportsPage.vue'], importDetail: ['src/pages/ImportDetailPage.vue'],
  knowledgeGapList: ['src/pages/KnowledgeGapsPage.vue'], knowledgeGapNew: ['src/pages/KnowledgeGapNewPage.vue'],
  knowledgeGapDetail: ['src/pages/KnowledgeGapDetailPage.vue'], knowledgeGapFeedback: ['src/knowledgeGaps/GapFeedbackDialog.vue'],
  knowledgeGapQueue: ['src/admin/KnowledgeGapQueuePage.vue'], knowledgeGapReception: ['src/admin/KnowledgeGapReceptionPage.vue'],
  assistant: ['src/pages/AssistantPage.vue'], lifecycle: ['src/admin/lifecycle/LifecyclePage.vue'],
  users: ['src/admin/UsersPage.vue'], departments: ['src/admin/DepartmentsPage.vue'],
  roles: ['src/admin/RolesPage.vue'], spaces: ['src/admin/SpacesPage.vue'],
  spaceDetail: ['src/admin/SpaceDetailPage.vue'], adminDocuments: ['src/admin/DocumentsIndexPage.vue'],
  documentPanel: ['src/admin/DocumentPanelPage.vue'], embedding: ['src/admin/EmbeddingPage.vue'],
  deadLetters: ['src/admin/ParseDeadLettersPage.vue'], audits: ['src/admin/AuditsPage.vue'],
  aiUsage: ['src/admin/AiUsagePage.vue'], aiAudits: ['src/admin/AiAuditsPage.vue'],
  operations: ['src/admin/OperationsReportsPage.vue'], tags: ['src/admin/DocumentTagsPage.vue'],
};
export const compression = { gzipLevel: 9, brotliQuality: 11, basis: '逐文件压缩后求和，字节' };
const fail = message => { throw new Error(message); };
const digest = data => createHash('sha256').update(data).digest('hex');
// 并行树和切换后的树使用同一规则；SFC 子模块也属于对应源码模块。
const sourceId = id => id.replace(/^src-vue\//, 'src/').split('?')[0];

export function bundleReport({ manifest, analysis, html, readAsset, routeModules = routes }) {
  if (analysis.schema !== 1 || !analysis.chunks || !analysis.assets) fail('缺少有效模块归属分析');
  const chunks = analysis.chunks;
  const files = {};
  const read = file => {
    if (!analysis.assets[file]) fail(`缺少资源摘要: ${file}`);
    const data = readAsset(file);
    if (digest(data) !== analysis.assets[file]) fail(`资源与分析不一致: ${file}`);
    return data;
  };
  for (const [file, chunk] of Object.entries(chunks)) {
    if (!Array.isArray(chunk.modules) || chunk.modules.length === 0) fail(`缺少模块映射: ${file}`);
    const matches = Object.values(manifest).filter(item => item.file === file);
    if (matches.length !== 1) fail(`manifest 缺少或重复 chunk: ${file}`);
    const item = matches[0];
    for (const [field, manifestField] of [['imports', 'imports'], ['dynamicImports', 'dynamicImports'], ['css', 'css']]) {
      const expected = (item[manifestField] ?? []).map(key => field === 'css' ? key : manifest[key]?.file ?? fail(`缺少依赖: ${key}`)).sort();
      if (JSON.stringify([...chunk[field]].sort()) !== JSON.stringify(expected)) fail(`依赖映射不一致: ${file}/${field}`);
    }
    for (const dependency of [...chunk.imports, ...chunk.dynamicImports]) if (!chunks[dependency]) fail(`缺少 chunk: ${dependency}`);
  }
  for (const file of Object.keys(analysis.assets)) {
    const data = read(file);
    if (!/\.(js|css)$/.test(file)) continue;
    files[file] = { type: file.endsWith('.css') ? 'css' : 'js', raw: data.length,
      gzip: gzipSync(data, { level: compression.gzipLevel }).length,
      brotli: brotliCompressSync(data, { params: { [constants.BROTLI_PARAM_QUALITY]: compression.brotliQuality } }).length,
      sha256: analysis.assets[file], modules: (chunks[file]?.modules ?? []).filter(id => /^src(?:-vue)?\//.test(id)) };
  }
  const htmlAssets = [...html.matchAll(/<(script|link)\b[^>]*>/gi)].flatMap(([tag, kind]) => {
    if (kind.toLowerCase() === 'link' && !/rel=["'](?:stylesheet|modulepreload)["']/i.test(tag)) return [];
    const url = tag.match(/(?:src|href)=["']([^"']+)["']/i)?.[1];
    if (!url) return [];
    if (/^(?:https?:)?\/\//.test(url)) fail('不支持外部构建资源');
    return [url.replace(/^\/?(?:\.\/)?/, '')];
  });
  if (!htmlAssets.some(file => chunks[file]?.entry)) fail('HTML 缺少有效入口');
  const closure = roots => {
    const found = new Set();
    const visit = file => {
      if (found.has(file)) return;
      if (!files[file]) fail(`缺少 JS/CSS 资源: ${file}`);
      found.add(file);
      if (file.endsWith('.js') && !chunks[file]) fail(`缺少模块映射: ${file}`);
      for (const dependency of [...(chunks[file]?.imports ?? []), ...(chunks[file]?.css ?? [])]) visit(dependency);
    };
    roots.forEach(visit);
    return [...found].sort();
  };
  const entry = closure(htmlAssets);
  const total = names => Object.fromEntries(['js', 'css', 'all'].map(type => {
    const selected = names.filter(file => type === 'all' || files[file].type === type);
    return [type, { files: selected.length, ...Object.fromEntries(['raw', 'gzip', 'brotli'].map(metric => [metric, selected.reduce((sum, file) => sum + files[file][metric], 0)])) }];
  }));
  const scenario = names => {
    const incremental = names.filter(file => !entry.includes(file));
    return { files: names, total: total(names), incremental: { files: incremental, total: total(incremental) } };
  };
  const scenarios = { entry: scenario(entry) };
  for (const [name, modules] of Object.entries(routeModules)) {
    const owners = Object.entries(chunks).filter(([, chunk]) => modules.some(id => chunk.modules.some(module => sourceId(module) === sourceId(id)))).map(([file]) => file);
    if (owners.length === 0) fail(`缺少路由模块归属: ${name}`);
    scenarios[name] = scenario(closure([...entry, ...owners]));
  }
  return { schema: 1, compression, provenance: analysis.provenance, files, scenarios };
}

export function checkIsolation(report) {
  const modules = name => new Set(report.scenarios[name].files.flatMap(file => report.files[file].modules.map(sourceId)));
  const entry = modules('entry'), login = modules('login'), list = modules('documents');
  const protectedModules = Object.entries(routes).filter(([name]) => !['login', 'password', 'sso'].includes(name)).flatMap(([, ids]) => ids);
  if (protectedModules.some(id => entry.has(id))) fail('入口包含受保护页面实现');
  if (login.has('src/sso/BindingPanel.vue')) fail('认证入口包含账户绑定面板');
  for (const id of ['src/pages/DocumentDetailPage.vue', 'src/comments/CommentPanel.vue', 'src/reviews/VersionReviewPanel.vue', 'src/office/VersionPreview.vue']) {
    if (list.has(id)) fail(`列表包含详情专用模块: ${id}`);
  }
  if ([...entry, ...list].some(id => id.startsWith('src/imports/'))) fail('入口或文档列表提前包含导入实现');
  // 导航可使用极小的地址白名单助手；其余问题 API、表单、正文及反馈实现都必须留在异步业务集合。
  if ([...entry, ...login, ...list].some(id => id.startsWith('src/knowledgeGaps/') && id !== 'src/knowledgeGaps/routes.ts')) {
    fail('入口、登录或文档列表提前包含知识问题实现');
  }
  const adminPages = Object.values(routes).flat().filter(id => id.startsWith('src/admin/'));
  const userPages = Object.values(routes).flat().filter(id => id.startsWith('src/pages/') && !['login', 'password', 'sso'].some(name => routes[name].includes(id)));
  for (const [name, ids] of Object.entries(routes)) {
    if (!report.scenarios[name]) continue;
    const current = modules(name), opposite = ids.some(id => id.startsWith('src/admin/')) ? userPages : adminPages;
    if (opposite.some(id => current.has(id))) fail(`管理与普通页面交叉内联: ${name}`);
  }
}

export function compare(report, baseline, { requireReduction = false, allowLockChange = false, newScenarios = [], checkGrowth = false } = {}) {
  if (JSON.stringify(report.compression) !== JSON.stringify(baseline.compression)) fail('基线压缩条件不同');
  for (const key of ['lockSha256', 'node', 'npm', 'mode', 'publicFlags']) {
    if (key === 'lockSha256' && allowLockChange) continue;
    if (JSON.stringify(report.provenance[key]) !== JSON.stringify(baseline.provenance[key])) fail(`基线条件不同: ${key}`);
  }
  for (const name of Object.keys(baseline.scenarios)) {
    if (!report.scenarios[name]) fail(`候选缺少场景: ${name}`);
  }
  const comparison = Object.fromEntries(Object.entries(report.scenarios).map(([name, scenario]) => {
    const before = baseline.scenarios[name]?.total.all;
    if (!before && (!newScenarios.includes(name) || ['entry', 'login', 'documents'].includes(name))) fail(`基线缺少场景: ${name}`);
    return [name, Object.fromEntries(['files', 'raw', 'gzip', 'brotli'].map(metric => [metric, { baseline: before?.[metric] ?? null, candidate: scenario.total.all[metric], delta: before ? scenario.total.all[metric] - before[metric] : null }]))];
  }));
  if (requireReduction && ['entry', 'login', 'documents'].some(name => comparison[name].gzip.delta >= 0)) fail('入口/登录/列表累计 gzip 未下降');
  if (checkGrowth && ['entry', 'login', 'documents'].some(name => comparison[name].gzip.candidate * 10 > comparison[name].gzip.baseline * 11)) fail('入口/登录/列表累计 gzip 超过基线 10%');
  return comparison;
}

function main() {
  const args = process.argv.slice(2);
  const option = (name, fallback) => {
    const index = args.indexOf(name);
    if (index < 0) return fallback;
    if (!args[index + 1] || args[index + 1].startsWith('--')) fail(`缺少参数: ${name}`);
    return args[index + 1];
  };
  const dist = resolve(option('--dist', 'dist')), analysisDir = resolve(option('--analysis', '.bundle-analysis'));
  const json = path => JSON.parse(readFileSync(path, 'utf8'));
  const readAsset = file => {
    const target = resolve(dist, file), rel = relative(dist, target);
    if (rel.startsWith('..') || isAbsolute(rel)) fail('资源路径超出构建目录');
    return readFileSync(target);
  };
  const analysis = json(resolve(analysisDir, 'modules.json'));
  const entryHtml = option('--html', 'index.html'), html = readAsset(entryHtml);
  if (digest(html) !== analysis.assets[entryHtml]) fail('HTML 与分析不一致');
  const report = bundleReport({ manifest: json(resolve(analysisDir, 'manifest.json')), analysis, html: html.toString(), readAsset });
  if (args.includes('--check-isolation')) checkIsolation(report);
  const baseline = option('--baseline');
  const comparisonOptions = { requireReduction: args.includes('--check-reduction'), allowLockChange: args.includes('--allow-lock-change'),
    checkGrowth: args.includes('--check-growth'), newScenarios: option('--new-scenarios', '').split(',').filter(Boolean) };
  if (!baseline && (comparisonOptions.requireReduction || comparisonOptions.allowLockChange || comparisonOptions.checkGrowth || comparisonOptions.newScenarios.length)) fail('比较选项必须提供基线');
  if (baseline) {
    const before = json(resolve(baseline));
    report.comparison = compare(report, before, comparisonOptions);
    report.comparisonConditions = { ...comparisonOptions, baselineLockSha256: before.provenance.lockSha256, candidateLockSha256: report.provenance.lockSha256 };
  }
  const out = option('--out', resolve(analysisDir, 'report.json'));
  writeFileSync(resolve(out), JSON.stringify(report, null, 2) + '\n');
  console.table(Object.fromEntries(Object.entries(report.scenarios).map(([name, value]) => [name, value.total.all])));
  console.log(`报告已保存: ${out}`);
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(`包体分析失败: ${error.message}`); process.exitCode = 1; }
}
