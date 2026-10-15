import { Marked } from 'marked';

/** Skill 详情专用 Markdown 解析器，与报告渲染和全局 Markdown 组件隔离。 */
export class SkillMarkdownRenderer {
  private readonly parser = new Marked({ gfm: true, breaks: false, async: false });

  render(markdown: string): string {
    return this.parser.parse(markdown, { async: false });
  }

  isFullHtmlDocument(text: string): boolean {
    const start = text.replace(/^(?:\s|<!--[\s\S]*?-->|<\?[\s\S]*?\?>)*/, '');
    return /^(?:<!doctype\b|<html\b)/i.test(start);
  }
}
