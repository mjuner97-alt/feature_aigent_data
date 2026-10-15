import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import ts from 'typescript';
import { Marked } from 'marked';

const file = new URL('./SkillMarkdownRenderer.ts', import.meta.url);
let Renderer;
if (existsSync(file)) {
  const source = readFileSync(file, 'utf8').replace("import { Marked } from 'marked';", 'const Marked = globalThis.__skillTestMarked;');
  globalThis.__skillTestMarked = Marked;
  try {
    const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
    Renderer = (await import(`data:text/javascript;base64,${Buffer.from(js).toString('base64')}`)).SkillMarkdownRenderer;
  } finally {
    delete globalThis.__skillTestMarked;
  }
}

test('skill detail has a dedicated renderer', () => assert.equal(typeof Renderer, 'function'));
const render = text => new Renderer().render(text);

test('preserves ordered list start and nested list structure', () => {
  const html = render('3. first\n   - nested\n4. second');
  assert.match(html, /<ol start="3">/);
  assert.match(html, /<li>first\s*<ul>\s*<li>nested<\/li>\s*<\/ul>\s*<\/li>/);
});
test('supports GFM tables, task lists and strikethrough', () => {
  const html = render('| Name | Value |\n| :--- | ---: |\n| a | ~~old~~ |\n\n- [x] done\n- [ ] pending');
  assert.match(html, /<table>/);
  assert.match(html, /<del>old<\/del>/);
  assert.match(html, /type="checkbox"/);
  assert.match(html, /checked/);
});
test('renders images and reference links', () => {
  const html = render('![diagram](/diagram.png)\n\n[guide][ref]\n\n[ref]: https://example.com/guide');
  assert.match(html, /<img src="\/diagram.png" alt="diagram"/);
  assert.match(html, /<a href="https:\/\/example.com\/guide">guide<\/a>/);
});
test('keeps HTML fences and Markdown-looking code literal', () => {
  const html = render('~~~html\n<script>alert(1)</script>\n~~~\n\n`**literal**`');
  assert.match(html, /<code class="language-html">&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /<code>\*\*literal\*\*<\/code>/);
});
test('handles CRLF fences and language names with punctuation', () => {
  assert.match(render('```c++\r\nint main() {}\r\n```'), /<code class="language-c\+\+">int main\(\) \{\}/);
});
test('renders semantic headings, nested quotes and emphasis', () => {
  const html = render('Heading\n=======\n\n> outer\n>\n> > inner\n\n__bold__ and _italic_');
  assert.match(html, /<h1>Heading<\/h1>/);
  assert.equal((html.match(/<blockquote>/g) || []).length, 2);
  assert.match(html, /<strong>bold<\/strong> and <em>italic<\/em>/);
});
test('handles empty content and retains embedded HTML fragments', () => {
  assert.equal(render(''), '');
  assert.match(render('<details><summary>More</summary>Content</details>'), /<details><summary>More<\/summary>Content<\/details>/);
});

test('recognizes full HTML documents while keeping HTML examples as Markdown', () => {
  const renderer = new Renderer();
  assert.equal(renderer.isFullHtmlDocument('\uFEFF <!-- generated --> <?xml version="1.0"?>\n<!DOCTYPE html><html></html>'), true);
  assert.equal(renderer.isFullHtmlDocument('<HTML><body>preview</body></HTML>'), true);
  assert.equal(renderer.isFullHtmlDocument('```html\n<html></html>\n```'), false);
  assert.equal(renderer.isFullHtmlDocument('<details>fragment</details>'), false);
});
