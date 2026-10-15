# Skill detail Markdown renderer

**Goal:** Fix Markdown rendering exclusively on the skill detail page.

**Approved design:** Add a frontend `SkillMarkdownRenderer` class using the existing `marked` dependency and a dedicated Vue component. Keep dark styling and the existing full HTML document preview. Leave `HtmlReportRenderer.java` and the shared `Markdown.vue` unchanged.

**Architecture:** Parse CommonMark/GFM with a dedicated synchronous parser. Render the resulting fragment in a styled component; delegate full HTML documents to the existing preview component. Fenced HTML remains source code, as required by Markdown syntax.

**Validation:** Cover ordered/nested lists, tables, task lists, images, reference links, fenced/inline code, emphasis, CRLF and empty input. Verify the component compiles and the production bundle builds without replacing existing static assets.

- [x] Add regression tests and observe failure before implementation.
- [x] Add the renderer class and dedicated component, and switch only SkillDetailPage.
- [x] Run tests, focused TypeScript checking and an isolated Vite build.
- [x] Review changes and confirm HtmlReportRenderer is unchanged.

**Results:** 9/9 regression tests passed. Renderer TypeScript check passed. Isolated Vite production build passed (existing dependency/chunk warnings). Full-project vue-tsc reports unrelated existing errors, including ActivityFeed styles, trace.ts argument types, and SkillDetailPage's existing approval description field. No generated static resources were overwritten.
