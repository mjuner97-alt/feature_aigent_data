/** Keep the provider's hash route and encode our callback as one redirect_url value. */
export function buildThirdPartyLoginUrl(loginUrl: string, origin: string) {
  const url = new URL(loginUrl);
  const callback = new URL('/login', origin).href;
  if (url.hash) {
    const fragment = url.hash.slice(1);
    const separator = fragment.indexOf('?');
    const route = separator >= 0 ? fragment.slice(0, separator) : fragment;
    const params = new URLSearchParams(separator >= 0 ? fragment.slice(separator + 1) : '');
    params.set('redirect_url', callback);
    url.hash = `${route}?${params.toString()}`;
  } else {
    url.searchParams.set('redirect_url', callback);
  }
  return url.href;
}

/** Normalize third-party identity returned to any business route into the login handler. */
export function thirdPartyCallbackLocation(fullPath: string) {
  const url = new URL(fullPath, 'http://local.invalid');
  const fragment = url.hash.slice(1);
  const separator = fragment.indexOf('?');
  const hashPrefix = separator >= 0 ? fragment.slice(0, separator) : '';
  const hashParams = new URLSearchParams(separator >= 0 ? fragment.slice(separator + 1) : fragment);
  const userId = url.searchParams.get('userId') ?? hashParams.get('userId');
  if (userId === null) return null;
  url.searchParams.delete('userId');
  url.searchParams.delete('token');
  if (hashParams.has('userId') || hashParams.has('token')) {
    hashParams.delete('userId');
    hashParams.delete('token');
    const rest = hashParams.toString();
    url.hash = hashPrefix + (hashPrefix && rest ? '?' : '') + rest;
  }
  const query: Record<string, string> = url.pathname === '/login'
    ? Object.fromEntries(url.searchParams)
    : { returnTo: url.pathname + url.search + url.hash };
  query.userId = userId;
  return { path: '/login', query, replace: true };
}
