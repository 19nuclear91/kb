import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { isAbsolute, relative, resolve } from 'node:path';
import type { Plugin, ResolvedConfig } from 'vite';

const publicFlags = ['VITE_CONVERSATION_ENABLED', 'VITE_NOTIFICATION_POLL_MS', 'VITE_OFFICE_PREVIEW_ORIGIN', 'VITE_SEARCH_MAX_QUERY_CODEPOINTS'];
export function bundleAnalysis(): Plugin {
  let config: ResolvedConfig;
  return {
    name: 'kb-bundle-analysis',
    apply: 'build',
    enforce: 'post',
    configResolved(value) { config = value; },
    writeBundle(_, bundle) {
      const moduleId = (id: string) => {
        const clean = id.replace(/^\0/, '');
        const normalized = (isAbsolute(clean) ? relative(config.root, clean) : clean).replaceAll('\\', '/');
        if (normalized.startsWith('../') || /^[A-Za-z]:/.test(normalized)) throw new Error('分析模块不在项目目录内');
        return normalized;
      };
      const chunks = Object.fromEntries(Object.entries(bundle).filter(([, item]) => item.type === 'chunk').map(([file, item]) => {
        if (item.type !== 'chunk') throw new Error('无效 chunk');
        return [file, {
          entry: item.isEntry, dynamicEntry: item.isDynamicEntry,
          imports: item.imports, dynamicImports: item.dynamicImports,
          css: [...(item.viteMetadata?.importedCss ?? [])].sort(),
          modules: Object.entries(item.modules).filter(([, info]) => info.renderedLength > 0).map(([id]) => moduleId(id)).sort(),
        }];
      }));
      const git = (...args: string[]) => execFileSync('git', args, { cwd: config.root, encoding: 'utf8' }).trim();
      const digest = (value: Buffer) => createHash('sha256').update(value).digest('hex');
      const output = resolve(config.root, '.bundle-analysis');
      mkdirSync(output, { recursive: true });
      const manifestPath = resolve(config.root, config.build.outDir, '.vite/manifest.json');
      writeFileSync(resolve(output, 'manifest.json'), readFileSync(manifestPath));
      unlinkSync(manifestPath);
      writeFileSync(resolve(output, 'modules.json'), JSON.stringify({
        schema: 1, chunks,
        assets: Object.fromEntries(Object.keys(bundle).filter(file => file !== '.vite/manifest.json').sort().map(file => [file, digest(readFileSync(resolve(config.root, config.build.outDir, file)))])),
        provenance: {
          revision: git('rev-parse', 'HEAD'), worktree: git('status', '--short', '--untracked-files=all'),
          lockSha256: digest(readFileSync(resolve(config.root, 'package-lock.json'))), node: process.version,
          npm: process.env.npm_config_user_agent?.match(/npm\/([^ ]+)/)?.[1] ?? 'unknown',
          mode: config.mode, publicFlags: Object.fromEntries(publicFlags.map(key => [key, config.env[key] ?? null])),
        },
      }, null, 2) + '\n');
    },
  };
}
