import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, relative, isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gzipSync, brotliCompressSync, constants } from 'node:zlib';

export const routes = {
  login: ['src/pages/LoginPage.tsx', 'src/pages/AuthPages.tsx'],
  password: ['src/pages/ChangePasswordPage.tsx', 'src/pages/AuthPages.tsx'],
  sso: ['src/pages/SsoResultPage.tsx'],
  documents: ['src/pages/DocumentsPage.tsx', 'src/pages/Documents.tsx'],
  detail: ['src/pages/DocumentDetailPage.tsx', 'src/pages/Documents.tsx'],
  account: ['src/pages/AccountPage.tsx', 'src/pages/AuthPages.tsx'],
  search: ['src/pages/Search.tsx'], favorites: ['src/pages/Favorites.tsx'],
  reviews: ['src/pages/Reviews.tsx'], notifications: ['src/pages/Notifications.tsx'],
  assistant: ['src/pages/Assistant.tsx'],
  users: ['src/admin/UsersPage.tsx'], departments: ['src/admin/DepartmentsPage.tsx'],
  roles: ['src/admin/RolesPage.tsx'], spaces: ['src/admin/SpacesPage.tsx'],
  spaceDetail: ['src/admin/SpaceDetailPage.tsx'], adminDocuments: ['src/admin/DocumentsIndexPage.tsx'],
  documentPanel: ['src/admin/DocumentPanelPage.tsx'], embedding: ['src/admin/EmbeddingPage.tsx'],
  deadLetters: ['src/admin/ParseDeadLettersPage.tsx'], audits: ['src/admin/AuditsPage.tsx'],
  aiUsage: ['src/admin/AiUsagePages.tsx'], aiAudits: ['src/admin/AiUsagePages.tsx'],
  operations: ['src/admin/OperationsReportsPage.tsx'], tags: ['src/admin/DocumentTaxonomy.tsx'],
};
export const compression = { gzipLevel: 9, brotliQuality: 11, basis: '逐文件压缩后求和，字节' };
const fail = message => { throw new Error(message); };
const digest = data => createHash('sha256').update(data).digest('hex');

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
      sha256: analysis.assets[file], modules: (chunks[file]?.modules ?? []).filter(id => id.startsWith('src/')) };
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
    const owners = Object.entries(chunks).filter(([, chunk]) => modules.some(id => chunk.modules.includes(id))).map(([file]) => file);
    if (owners.length === 0) fail(`缺少路由模块归属: ${name}`);
    scenarios[name] = scenario(closure([...entry, ...owners]));
  }
  return { schema: 1, compression, provenance: analysis.provenance, files, scenarios };
}

export function checkIsolation(report) {
  const modules = name => new Set(report.scenarios[name].files.flatMap(file => report.files[file].modules));
  const entry = modules('entry'), login = modules('login'), list = modules('documents');
  const protectedModules = Object.entries(routes).filter(([name]) => !['login', 'password', 'sso'].includes(name)).flatMap(([, ids]) => ids);
  if (protectedModules.some(id => entry.has(id))) fail('入口包含受保护页面实现');
  if (login.has('src/sso/BindingPanel.tsx')) fail('认证入口包含账户绑定面板');
  for (const id of ['src/pages/DocumentDetailPage.tsx', 'src/comments/CommentPanel.tsx', 'src/reviews/VersionReviewPanel.tsx', 'src/office/VersionPreview.tsx']) {
    if (list.has(id)) fail(`列表包含详情专用模块: ${id}`);
  }
}

export function compare(report, baseline, requireReduction = false) {
  if (JSON.stringify(report.compression) !== JSON.stringify(baseline.compression)) fail('基线压缩条件不同');
  for (const key of ['lockSha256', 'node', 'npm', 'mode', 'publicFlags']) {
    if (JSON.stringify(report.provenance[key]) !== JSON.stringify(baseline.provenance[key])) fail(`基线条件不同: ${key}`);
  }
  const comparison = Object.fromEntries(Object.entries(report.scenarios).map(([name, scenario]) => {
    const before = baseline.scenarios[name]?.total.all ?? fail(`基线缺少场景: ${name}`);
    return [name, Object.fromEntries(['files', 'raw', 'gzip', 'brotli'].map(metric => [metric, { baseline: before[metric], candidate: scenario.total.all[metric], delta: scenario.total.all[metric] - before[metric] }]))];
  }));
  if (requireReduction && ['entry', 'login', 'documents'].some(name => comparison[name].gzip.delta >= 0)) fail('入口/登录/列表累计 gzip 未下降');
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
  const html = readAsset('index.html');
  if (digest(html) !== analysis.assets['index.html']) fail('HTML 与分析不一致');
  const report = bundleReport({ manifest: json(resolve(analysisDir, 'manifest.json')), analysis, html: html.toString(), readAsset });
  if (args.includes('--check-isolation')) checkIsolation(report);
  const baseline = option('--baseline');
  if (args.includes('--check-reduction') && !baseline) fail('比较下降必须提供基线');
  if (baseline) report.comparison = compare(report, json(resolve(baseline)), args.includes('--check-reduction'));
  const out = option('--out', '.bundle-analysis/report.json');
  writeFileSync(resolve(out), JSON.stringify(report, null, 2) + '\n');
  console.table(Object.fromEntries(Object.entries(report.scenarios).map(([name, value]) => [name, value.total.all])));
  console.log(`报告已保存: ${out}`);
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(`包体分析失败: ${error.message}`); process.exitCode = 1; }
}
