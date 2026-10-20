# Report content copy implementation plan

Goal: Add 复制内容 beside existing report actions for long tasks and independent tasks.

Approved design: Fetch through existing authenticated report endpoints. Render a temporary isolated document, flatten nested srcdoc reports, inline computed typography and table styles, and write HTML plus plain text. Exclude navigation and controls. Fail visibly rather than report a successful plain-text downgrade. Never run report scripts in the application origin.

- [x] Add browser tests for complete nested content, inline styles, removed controls, and rich clipboard payloads; observe failure.
- [x] Implement shared report clipboard utility and authenticated API entry points.
- [x] Wire buttons with pending states and success/error messages to both details and independent execution center.
- [x] Run browser tests, related existing tests, and production build. Record limitations for dynamic charts and destination editors.

Validation: 4 Chromium browser tests passed, including real OS clipboard write/paste on localhost and intranet HTTP, nested ECharts PNG capture, SVG references, full text order, merged cells, typography, table backgrounds/borders, and explicit failure on missing external frames. 27 related API/component tests passed. Production Vite build passed. New utility strict TypeScript check and utility/drawer ESLint checks passed.

Review fixes: replaced secure-context-only randomUUID with getRandomValues; SVG IDs and references receive unique prefixes; HTTP uses a rich copy-event fallback. Chart scripts run in an opaque sandbox. Navigation and controls are excluded. Unsupported media, inaccessible images, or failed charts produce errors instead of silently dropping content.

Limits: real Word/WPS applications were not tested; validation used Chromium contenteditable paste. Destination applications can filter unsupported formatting. Dynamic charts are copied as static PNG images. Full-project vue-tsc has existing errors in ActivityFeed/trace/other unrelated components; SkillJobListPage has two existing unused-function lint errors.

Run browser tests with an installed Playwright package (or PLAYWRIGHT_MODULE pointing to the bundled package) and optionally CHROMIUM_PATH pointing to an installed Chromium executable: `node --test test/reportClipboard.browser.test.mjs` from frontend/.
