/** 使用原页标题，并去掉显示用的多级章节编号。 */
export function feedbackPageTitle(originalTitle?: string | null, matchedTitle?: string | null) {
  const title = originalTitle || matchedTitle || '';
  const withoutSectionNumber = title.replace(/^\s*[0-9]+(?:[.．。\-－–—][0-9]+)+[.．。、\s]*/, '').trim();
  return withoutSectionNumber || title || '未命名页面';
}
