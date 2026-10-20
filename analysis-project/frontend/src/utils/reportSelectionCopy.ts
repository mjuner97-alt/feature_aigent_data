import { reportCopyOmittedSelector, reportCopyStyleProperties } from './reportCopyStyles';

/** Self-contained: serialized into downloaded HTML as well as installed in editor frames. */
function bindSelectionCopy(doc: Document, properties: string[], omitted: string): void {
  const boundDoc = doc as Document & { __reportSelectionCopyBound?: boolean };
  if (boundDoc.__reportSelectionCopyBound) return;
  boundDoc.__reportSelectionCopyBound = true;
  doc.addEventListener('copy', event => {
    if (event.defaultPrevented || !event.clipboardData) return;
    if ((event.target as Element)?.closest?.('input,textarea')) return;
    const selection = doc.defaultView?.getSelection();
    if (!selection || selection.isCollapsed || !selection.rangeCount) return;
    try {
      const result = doc.createElement('div');
      const svgPrefix = 'selection-' + Math.random().toString(36).slice(2) + '-';
      const rewrite = (value: string) => value.replace(/url\(["']?#([^\s)"']+)["']?\)/g, (_, id) => `url(#${svgPrefix}${id})`);
      for (let index = 0; index < selection.rangeCount; index++) {
        const range = selection.getRangeAt(index);
        let root = range.commonAncestorContainer;
        if (root.nodeType === 3) root = root.parentNode!;
        // Retain the selected inline text's paragraph alignment/background context.
        while ((root as Element).parentElement && root !== doc.body && doc.defaultView!.getComputedStyle(root as Element).display.startsWith('inline')) {
          root = root.parentElement!;
        }
        // A cell selection needs its table/tr/tbody wrappers to paste as a table.
        const table = (root as Element).closest?.('table');
        if (table) root = table;
        const copy = (node: Node): Node | null => {
          if (!range.intersectsNode(node)) return null;
          if (node.nodeType === 3) {
            const start = range.startContainer === node ? range.startOffset : 0;
            const end = range.endContainer === node ? range.endOffset : (node.textContent || '').length;
            return end > start ? doc.createTextNode((node.textContent || '').slice(start, end)) : null;
          }
          if (node.nodeType !== 1) return null;
          const element = node as HTMLElement;
          if (element.matches(omitted)) return null;
          if (element.matches('iframe,object,embed,video,audio')) throw new Error('Use native copy for unsupported embedded selections');
          const computed = doc.defaultView!.getComputedStyle(element);
          if (computed.display === 'none' || computed.visibility === 'hidden') return null;
          if (element.tagName === 'CANVAS') {
            const image = doc.createElement('img');
            image.src = (element as HTMLCanvasElement).toDataURL('image/png');
            image.width = element.clientWidth; image.height = element.clientHeight;
            return image;
          }
          const clone = element.tagName === 'BODY' ? doc.createElement('div') : element.cloneNode(false) as HTMLElement;
          for (const attribute of Array.from(clone.attributes)) {
            if (/^on/i.test(attribute.name) || ['style', 'class', 'contenteditable', 'autofocus'].includes(attribute.name)) clone.removeAttribute(attribute.name);
            if (attribute.name === 'id') {
              if (element.namespaceURI === 'http://www.w3.org/2000/svg') clone.id = svgPrefix + attribute.value;
              else clone.removeAttribute('id');
            } else if (element.namespaceURI === 'http://www.w3.org/2000/svg') {
              clone.setAttribute(attribute.name, ['href', 'xlink:href'].includes(attribute.name) && attribute.value.startsWith('#')
                ? '#' + svgPrefix + attribute.value.slice(1) : rewrite(attribute.value));
            }
            if (['href', 'src', 'xlink:href'].includes(attribute.name) && /^\s*(javascript|vbscript):/i.test(attribute.value)) clone.removeAttribute(attribute.name);
          }
          for (const property of properties) clone.style.setProperty(property, rewrite(computed.getPropertyValue(property)));
          if (element.tagName === 'IMG') {
            const original = element as HTMLImageElement;
            if (!original.complete || !original.naturalWidth) throw new Error('Image not loaded');
            const image = clone as HTMLImageElement;
            image.removeAttribute('srcset');
            if (original.src.startsWith('data:')) image.src = original.src;
            else {
              const canvas = doc.createElement('canvas'); canvas.width = original.naturalWidth; canvas.height = original.naturalHeight;
              const context = canvas.getContext('2d'); if (!context) throw new Error('Image conversion unavailable');
              context.drawImage(original, 0, 0); image.src = canvas.toDataURL('image/png');
            }
          }
          for (const child of Array.from(element.childNodes)) {
            const selected = copy(child); if (selected) clone.appendChild(selected);
          }
          return clone;
        };
        const fragment = copy(root); if (fragment) result.appendChild(fragment);
      }
      if (!result.textContent && !result.querySelector('img,svg')) return;
      event.clipboardData.setData('text/html', `<html><head><meta charset="utf-8"></head><body>${result.innerHTML}</body></html>`);
      event.clipboardData.setData('text/plain', selection.toString());
      event.preventDefault();
    } catch {
      // Preserve the browser's native copy if any part cannot be safely serialized.
    }
  });
  const frames = new WeakSet<HTMLIFrameElement>();
  const bindFrame = (frame: HTMLIFrameElement) => {
    if (frames.has(frame)) return;
    frames.add(frame);
    const bind = () => {
      try { if (frame.contentDocument) bindSelectionCopy(frame.contentDocument, properties, omitted); } catch { /* Cross-origin frames retain native copy. */ }
    };
    frame.addEventListener('load', bind); bind();
  };
  doc.querySelectorAll('iframe').forEach(bindFrame);
  new MutationObserver(mutations => {
    for (const mutation of mutations) for (const added of Array.from(mutation.addedNodes)) {
      if (added.nodeType !== 1) continue;
      const element = added as Element;
      if (element.tagName === 'IFRAME') bindFrame(element as HTMLIFrameElement);
      element.querySelectorAll('iframe').forEach(bindFrame);
    }
  }).observe(doc.documentElement, { childList: true, subtree: true });
}

export function installReportSelectionCopy(doc: Document): void {
  bindSelectionCopy(doc, reportCopyStyleProperties, reportCopyOmittedSelector);
}

/** Append without reserializing embedded libraries, existing scripts, or nested srcdoc. */
export function withReportSelectionCopy(html: string): string {
  if (html.includes('<script data-report-selection-copy>')) return html;
  const script = `<script data-report-selection-copy>(${bindSelectionCopy.toString()})(document,${JSON.stringify(reportCopyStyleProperties)},${JSON.stringify(reportCopyOmittedSelector)});</script>`;
  let last = -1;
  // A library string or comment can contain "</body>"; it is not a document boundary.
  for (const match of html.matchAll(/<script\b[^>]*>[\s\S]*?<\/script\s*>|<style\b[^>]*>[\s\S]*?<\/style\s*>|<!--[\s\S]*?-->|<\/body\s*>/gi)) {
    if (/^<\/body/i.test(match[0])) last = match.index!;
  }
  return last >= 0 ? html.slice(0, last) + script + html.slice(last) : html + script;
}

export async function reportBlobWithSelectionCopy(blob: Blob): Promise<Blob> {
  return new Blob([withReportSelectionCopy(await blob.text())], { type: 'text/html;charset=utf-8' });
}
