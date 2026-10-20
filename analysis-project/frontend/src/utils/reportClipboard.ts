import { reportCopyOmittedSelector as omitted, reportCopyStyleProperties as styles } from './reportCopyStyles';
export { installReportSelectionCopy, withReportSelectionCopy } from './reportSelectionCopy';
export interface ReportClipboardContent { html: string; text: string }

function uniqueToken(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, '0')).join('');
}

/** Chart code runs only in an opaque sandbox, without access to app cookies or DOM. */
async function snapshotCharts(html: string): Promise<string> {
  const parsed = new DOMParser().parseFromString(html, 'text/html');
  const ownScript = Array.from(parsed.querySelectorAll('script')).map(script => script.textContent).join('\n');
  const hasChart = parsed.querySelector('canvas,[data-report-chart],[data-echarts-option],.echarts-container');
  if (!hasChart && (!/echarts\s*\.\s*init\s*\(/.test(ownScript) || parsed.querySelector('iframe'))) return html;
  parsed.querySelectorAll('*').forEach((element, index) => element.setAttribute('data-copy-node', String(index)));
  const frame = document.createElement('iframe');
  frame.sandbox.add('allow-scripts');
  frame.referrerPolicy = 'no-referrer';
  frame.style.cssText = 'position:fixed;left:-20000px;top:0;width:1200px;height:900px;border:0';
  const token = uniqueToken();
  const capture = document.createElement('script');
  capture.textContent = `window.addEventListener('load',function(){setTimeout(async function(){try{
    if(document.fonts)await document.fonts.ready;
    var pictures=[];
    document.querySelectorAll('canvas').forEach(function(canvas){
      var owner=canvas.closest('[_echarts_instance_]')||canvas.closest('[data-copy-node]')||canvas.parentElement;
      while(owner&&!owner.hasAttribute('data-copy-node'))owner=owner.parentElement;
      if(!owner)throw Error('无法定位图表');
      var chart=window.echarts&&window.echarts.getInstanceByDom(owner);
      var image=chart?chart.getDataURL({type:'png',pixelRatio:2,backgroundColor:'#ffffff'}):canvas.toDataURL('image/png');
      pictures.push({id:owner.getAttribute('data-copy-node'),src:image,width:owner.getBoundingClientRect().width,height:owner.getBoundingClientRect().height});
    });
    document.querySelectorAll('[_echarts_instance_]').forEach(function(owner){
      if(pictures.some(function(p){return p.id===owner.getAttribute('data-copy-node')}))return;
      var chart=window.echarts&&window.echarts.getInstanceByDom(owner);
      if(chart)pictures.push({id:owner.getAttribute('data-copy-node'),src:chart.getDataURL({type:'png',pixelRatio:2,backgroundColor:'#ffffff'}),width:owner.getBoundingClientRect().width,height:owner.getBoundingClientRect().height});
    });
    if(!pictures.length)throw Error('图表未能渲染');
    parent.postMessage({token:${JSON.stringify(token)},pictures:pictures},'*');
  }catch(e){parent.postMessage({token:${JSON.stringify(token)},error:true},'*')}},1200)});`;
  parsed.body.appendChild(capture);
  try {
    const pictures = await new Promise<Array<{ id: string; src: string; width: number; height: number }>>((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); window.removeEventListener('message', receive); };
      const receive = (event: MessageEvent) => {
        if (event.source !== frame.contentWindow || event.data?.token !== token) return;
        cleanup();
        if (event.data.error || !Array.isArray(event.data.pictures)) reject(new Error('图表渲染失败，未复制，请使用下载文件'));
        else resolve(event.data.pictures);
      };
      const timer = setTimeout(() => { cleanup(); reject(new Error('图表渲染超时，未复制，请重试')); }, 20000);
      window.addEventListener('message', receive);
      frame.srcdoc = '<!doctype html>' + parsed.documentElement.outerHTML;
      document.body.appendChild(frame);
    });
    for (const picture of pictures) {
      if (!/^\d+$/.test(picture.id) || !/^data:image\/png;base64,/.test(picture.src)) throw new Error('图表图片无效，无法完整复制');
      const owner = parsed.querySelector(`[data-copy-node="${picture.id}"]`);
      if (!owner) throw new Error('图表内容缺失，无法完整复制');
      const image = parsed.createElement('img');
      image.src = picture.src;
      image.alt = '报告图表';
      if (picture.width > 0) image.width = Math.ceil(picture.width);
      if (picture.height > 0) image.height = Math.ceil(picture.height);
      if (owner.tagName === 'CANVAS') owner.replaceWith(image);
      else owner.replaceChildren(image);
    }
    capture.remove();
    return '<!doctype html>' + parsed.documentElement.outerHTML;
  } finally { frame.remove(); }
}

/** Scripts are deliberately disabled: reports must never execute in the app's origin. */
async function renderReport(html: string, depth = 0): Promise<HTMLElement> {
  if (depth > 12) throw new Error('报告嵌套层数过多，无法完整复制');
  html = await snapshotCharts(html);
  const svgPrefix = `copy-${uniqueToken()}-`;
  const svgReference = (value: string) => value.replace(/url\(["']?#([^\s)"']+)["']?\)/g, (_, id) => `url(#${svgPrefix}${id})`);
  const frame = document.createElement('iframe');
  frame.setAttribute('sandbox', 'allow-same-origin');
  frame.style.cssText = 'position:fixed;left:-20000px;top:0;width:1200px;height:900px;border:0;pointer-events:none';
  frame.setAttribute('aria-hidden', 'true');
  try {
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('报告加载超时，请重试')), 15000);
      frame.onload = () => { clearTimeout(timer); resolve(); };
      frame.srcdoc = html;
      document.body.appendChild(frame);
    });
    const doc = frame.contentDocument;
    if (!doc?.body || !doc.defaultView) throw new Error('无法读取报告正文');
    const root = doc.getElementById('report') || doc.body;
    const copy = async (node: Node): Promise<Node | null> => {
      if (node.nodeType === Node.TEXT_NODE) return document.createTextNode(node.textContent || '');
      if (node.nodeType !== Node.ELEMENT_NODE) return null;
      const element = node as HTMLElement;
      if (element.matches(omitted)) return null;
      if (element.matches('object,embed,video,audio')) throw new Error('报告包含暂不支持复制的媒体内容，请使用下载文件');
      const computed = doc.defaultView!.getComputedStyle(element);
      if (computed.display === 'none' || computed.visibility === 'hidden') return null;
      if (element.tagName === 'IFRAME') {
        const source = element.getAttribute('srcdoc');
        if (!source) throw new Error('报告包含外部嵌入内容，无法完整复制');
        return renderReport(source, depth + 1);
      }
      // Dynamic charts have no rendered pixels with scripts disabled. Never silently lose them.
      if (element.matches('canvas,[data-echarts-option],.echarts-container,[data-report-chart-shell]') && !element.querySelector('img')) {
        throw new Error('报告包含动态图表，暂不能完整复制，请使用下载文件');
      }
      const clone = element.tagName === 'BODY' ? document.createElement('div') : element.cloneNode(false) as HTMLElement;
      for (const attribute of Array.from(clone.attributes)) {
        if (/^on/i.test(attribute.name) || ['class', 'contenteditable', 'style'].includes(attribute.name)) clone.removeAttribute(attribute.name);
        if (attribute.name === 'id') {
          if (element.namespaceURI === 'http://www.w3.org/2000/svg') clone.id = svgPrefix + attribute.value;
          else clone.removeAttribute('id');
        }
        if (element.namespaceURI === 'http://www.w3.org/2000/svg' && attribute.name !== 'id') {
          clone.setAttribute(attribute.name, ['href', 'xlink:href'].includes(attribute.name) && attribute.value.startsWith('#')
            ? '#' + svgPrefix + attribute.value.slice(1) : svgReference(attribute.value));
        }
        if (['href', 'src', 'xlink:href'].includes(attribute.name) && /^\s*(javascript|vbscript):/i.test(attribute.value)) clone.removeAttribute(attribute.name);
      }
      for (const property of styles) clone.style.setProperty(property, svgReference(computed.getPropertyValue(property)));
      if (element.tagName === 'IMG') {
        const image = element as HTMLImageElement;
        if (!image.complete || image.naturalWidth === 0) throw new Error('报告图片未能加载，无法完整复制');
        if (!image.src.startsWith('data:')) {
          try {
            const canvas = document.createElement('canvas');
            canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
            const context = canvas.getContext('2d');
            if (!context) throw new Error('图片转换失败');
            context.drawImage(image, 0, 0);
            (clone as HTMLImageElement).src = canvas.toDataURL('image/png');
            clone.removeAttribute('srcset');
          } catch { throw new Error('报告包含无法复制的外部图片，请使用下载文件'); }
        }
      }
      for (const child of Array.from(node.childNodes)) {
        const content = await copy(child);
        if (content) clone.appendChild(content);
      }
      return clone;
    };
    return await copy(root) as HTMLElement;
  } finally { frame.remove(); }
}

export async function prepareReportClipboard(html: string): Promise<ReportClipboardContent> {
  const validate = (source: string, depth = 0) => {
    if (depth > 12) throw new Error('报告嵌套层数过多，无法完整复制');
    const doc = new DOMParser().parseFromString(source, 'text/html');
    for (const frame of Array.from(doc.querySelectorAll('iframe'))) {
      if (!frame.hasAttribute('srcdoc')) throw new Error('报告包含外部嵌入内容，无法完整复制');
      validate(frame.getAttribute('srcdoc')!, depth + 1);
    }
  };
  validate(html);
  const content = await renderReport(html);
  const host = document.createElement('div');
  host.style.cssText = 'position:fixed;left:-20000px;top:0;width:1200px';
  host.appendChild(content);
  document.body.appendChild(host);
  try {
    const text = content.innerText.trim();
    if (!text && !content.querySelector('img,svg')) throw new Error('报告正文为空，无法复制');
    return { html: `<html><head><meta charset="utf-8"></head><body>${content.outerHTML}</body></html>`, text };
  } finally { host.remove(); }
}

/** Call synchronously from a click; promise-valued items retain browser user activation. */
export async function writeReportClipboard(content: Promise<ReportClipboardContent>): Promise<void> {
  // Attach rejection handling even when clipboard access is unavailable.
  const html = content.then(value => new Blob([value.html], { type: 'text/html' }));
  const text = content.then(value => new Blob([value.text], { type: 'text/plain' }));
  void html.catch(() => {}); void text.catch(() => {});
  if (!navigator.clipboard?.write || typeof ClipboardItem === 'undefined') {
    return writeLegacyClipboard(await content);
  }
  try {
    await navigator.clipboard.write([new ClipboardItem({ 'text/html': html, 'text/plain': text })]);
  } catch (error) {
    // Prefer a specific content preparation error over a generic clipboard rejection.
    await content;
    throw new Error(error instanceof DOMException && error.name === 'NotAllowedError'
      ? '剪贴板访问被拒绝，请允许浏览器访问剪贴板后重试'
      : '复制失败，请重试');
  }
}

/** Supports intranet HTTP deployments without silently reducing the copy to plain text. */
function writeLegacyClipboard(content: ReportClipboardContent): void {
  const selection = window.getSelection();
  const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange()) : [];
  const active = document.activeElement as HTMLElement | null;
  const host = document.createElement('div');
  host.contentEditable = 'true';
  host.style.cssText = 'position:fixed;left:-20000px;top:0';
  host.textContent = content.text || '报告';
  document.body.appendChild(host);
  let handled = false;
  const onCopy = (event: ClipboardEvent) => {
    if (!event.clipboardData) return;
    event.clipboardData.setData('text/html', content.html);
    event.clipboardData.setData('text/plain', content.text);
    event.preventDefault(); event.stopImmediatePropagation(); handled = true;
  };
  document.addEventListener('copy', onCopy, true);
  try {
    const range = document.createRange(); range.selectNodeContents(host);
    selection?.removeAllRanges(); selection?.addRange(range);
    if (!document.execCommand('copy') || !handled) throw new Error('浏览器未允许复制，请检查剪贴板权限后重试');
  } finally {
    document.removeEventListener('copy', onCopy, true);
    host.remove(); selection?.removeAllRanges();
    for (const range of ranges) selection?.addRange(range);
    active?.focus({ preventScroll: true });
  }
}

export function copyReportContent(load: () => Promise<string>): Promise<void> {
  return writeReportClipboard(load().then(prepareReportClipboard));
}
