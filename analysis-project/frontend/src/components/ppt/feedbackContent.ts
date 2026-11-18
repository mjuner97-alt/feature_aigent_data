
/** 反馈富文本编解码与附件索引转换，兼容历史纯文字反馈。 */
const prefix = 'ppt-feedback-html:v1\n';
const imageSlot = 'data-feedback-image';
const tags = new Set(['div', 'p', 'br', 'b', 'strong', 'i', 'em', 'u', 'ul', 'ol', 'li',
  'table', 'tbody', 'thead', 'tfoot', 'tr', 'td', 'th', 'img']);
const imageSource = /^data:image\/(?:png|jpeg|gif|webp);base64,[a-z0-9+/=]+$/i;

/** 清理反馈富文本中的不支持标签，并解析允许的图片占位。 */
export function cleanFeedbackHtml(html: string, allowImages = true, resolveImage?: (index: number) => string | null | undefined): string {
  const source = new DOMParser().parseFromString(html, 'text/html');
  const output = document.createElement('div');

  /** 递归复制受支持的富文本节点及安全属性。 */
  function append(node: Node, parent: HTMLElement) {
    if (node.nodeType === Node.TEXT_NODE) {
      parent.append(document.createTextNode(node.textContent ?? ''));
      return;
    }
    if (!(node instanceof Element)) return;
    const tag = node.localName;
    if (['script', 'style', 'iframe', 'object', 'svg'].includes(tag)) return;
    if (!tags.has(tag)) {
      node.childNodes.forEach(child => append(child, parent));
      return;
    }
    const element = document.createElement(tag);
    if (tag === 'img') {
      const slot = node.getAttribute(imageSlot);
      if (slot !== null && /^[0-2]$/.test(slot)) {
        if (!resolveImage && !allowImages) return;
        const src = resolveImage?.(Number(slot));
        if (src === null) return;
        element.setAttribute(imageSlot, slot);
        if (src) element.setAttribute('src', src);
      } else {
        const src = node.getAttribute('src') ?? '';
        if (!allowImages || !imageSource.test(src)) return;
        element.setAttribute('src', src);
      }
      element.setAttribute('alt', '反馈图片');
    }
    if (tag === 'td' || tag === 'th') {
      for (const attribute of ['colspan', 'rowspan']) {
        const span = node.getAttribute(attribute) ?? '';
        if (/^[1-9]\d{0,2}$/.test(span)) element.setAttribute(attribute, span);
      }
    }
    node.childNodes.forEach(child => append(child, element));
    parent.append(element);
  }

  source.body.childNodes.forEach(node => append(node, output));
  return output.innerHTML;
}

/** 将清理后的富文本编码为带版本标记的反馈说明。 */
export function encodeFeedback(html: string): string {
  return prefix + cleanFeedbackHtml(html, false, () => undefined);
}

/** 兼容解码纯文字与富文本反馈，并解析图片占位。 */
export function decodeFeedback(value: string, resolveImage?: (index: number) => string | null): string {
  if (value.startsWith(prefix)) return cleanFeedbackHtml(value.slice(prefix.length), true, resolveImage);
  const plain = document.createElement('div');
  plain.textContent = value;
  return plain.innerHTML;
}


/** 尚未提交的反馈说明和本次新增截图文件。 */
export interface FeedbackDraft {
  explanation: string;
  files: File[];
  baseRevision: string;
}

/** 解包反馈说明与附件索引，生成图片访问地址。 */
export function unpackFeedback(value: string, attachmentUrl: (name: string) => string) {
  const marker = 'ppt-feedback-files:v1\n';
  if (value.startsWith(marker)) {
    try {
      const data = JSON.parse(value.slice(marker.length));
      if (typeof data.explanation === 'string' && Array.isArray(data.files)) {
        const used = new Set<number>();
        const names = (data.files as unknown[]).map(name =>
          typeof name === 'string' && /^[0-9a-f-]{36}\.(png|jpg)$/.test(name) ? name : null);
        const html = decodeFeedback(data.explanation, index => {
          const name = names[index];
          if (!name) return null;
          used.add(index);
          return attachmentUrl(name);
        });
        return { html, files: names.flatMap((name, index) => name && !used.has(index) ? [name] : []) };
      }
    } catch { /* 附件索引解析失败时按原始反馈说明展示。 */ }
  }
  return { html: decodeFeedback(value), files: [] as string[] };
}
