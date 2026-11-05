/** Keep mail HTML isolated while opening linked reports outside the preview. */
export function notificationPreview(content: string): string {
  const doc = new DOMParser().parseFromString(content, 'text/html');
  doc.querySelectorAll('a[href], area[href]').forEach(link => {
    link.setAttribute('target', '_blank');
    link.setAttribute('rel', 'noopener noreferrer');
  });
  const policy = doc.createElement('meta');
  policy.httpEquiv = 'Content-Security-Policy';
  policy.content = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'";
  doc.head.prepend(policy);
  return '<!doctype html>' + doc.documentElement.outerHTML;
}
