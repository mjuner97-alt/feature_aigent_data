# Report selection copy implementation plan

Approved goal: Selecting report text/tables then Ctrl+C or the native Copy menu copies only the selected content with inline computed formatting. Keep existing full-report copy buttons.

- [x] Add a failing browser test for partial text, partial table, nested iframe, and native input copying.
- [x] Implement a self-contained synchronous copy-event handler, installed recursively into report frames. Fall back to native copy if selection cannot be serialized. Do not change selection or report content.
- [x] Inject the handler into preview/download HTML without reserializing original scripts or srcdoc; install it in the editor preview and public report page.
- [x] Verify real clipboard/paste, current copy-button tests, API tests, lint/type checks, and a build without disturbing existing static assets.

Boundaries: Browser selection does not span distinct iframe documents. No blanket Ctrl+C keyboard interception. No clipboard permission prompt; write rich HTML and plain text on the user-triggered copy event only.

Validation: 4 selection-copy browser tests pass (normal and minified script, partial text/cell, paragraph alignment/background, native input, copy-menu event, dynamic/reloaded iframe, downloaded HTML opened offline via file://). 4 full-report browser regression tests and 30 related unit/component/API tests pass. Strict TypeScript for shared utilities and focused lint pass; skillFlow.ts has an existing unused `_ignored` lint issue. Vite production build passes using the current configuration with only its destructive static-cleanup plugin omitted and output redirected to a temporary directory. Existing static assets were preserved.

Run clipboard browser suites sequentially since they share the OS clipboard: `node --test --test-concurrency=1 test/reportClipboard.browser.test.mjs test/reportSelectionCopy.browser.test.mjs`. Use installed Playwright or PLAYWRIGHT_MODULE and optionally CHROMIUM_PATH. Word/WPS desktop paste was not tested. Partial SVG selections that depend on definitions outside the selection retain browser limitations; supported scope is selected text and tables.
