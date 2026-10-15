import { build } from 'esbuild';
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { parse, compileScript } from '@vue/compiler-sfc';

const require = createRequire(import.meta.url);

/** Compile real TS/Vue modules with the frontend's existing build dependencies. */
export async function loadModule(url, mocks = {}) {
  const result = await build({
    entryPoints: [url.pathname.replace(/^\/([A-Za-z]:)/, '$1')],
    bundle: true, write: false, platform: 'node', format: 'cjs',
    external: ['vue', ...Object.keys(mocks)],
    plugins: [{
      name: 'vue-test',
      setup(build) {
        build.onLoad({ filter: /\.vue$/ }, async ({ path }) => {
          const { descriptor } = parse(await readFile(path, 'utf8'));
          const script = compileScript(descriptor, { id: path, inlineTemplate: true });
          return { contents: script.content, loader: 'ts' };
        });
      },
    }],
  });
  const module = { exports: {} };
  const testRequire = name => name in mocks ? mocks[name] : require(name);
  new Function('require', 'module', 'exports', result.outputFiles[0].text)(testRequire, module, module.exports);
  return module.exports;
}
