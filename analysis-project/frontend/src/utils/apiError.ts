/**
 * 统一的失败响应解析工具。
 *
 * 后端错误响应体约定(见 SkillExceptionHandler / skill.ts):
 *  - 业务异常:SkillExceptionHandler 统一返回 JSON {"message":"..."}(中文/前缀码)
 *  - Spring 默认错误体:application.properties 配置 server.error.include-message=always
 *    后带 message 字段;否则只有 {"error":"Internal Server Error"} 这种通用串。
 *
 * 所有 api/*.ts 在 res.ok=false 时调用 apiError(res, fallback) 生成可读 Error,
 * 避免只拼 res.statusText 导致"所有错误都只弹 Internal Server Error"。
 */

/**
 * 从失败响应里尽量提取具体错误消息。
 * 优先 JSON body 的 message,其次 error,再退到原始文本(HTML/纯文本错误页)。
 * 解析失败返回空串,调用方自行回退到状态码。
 */
export async function apiErrorDetail(res: Response): Promise<string> {
  let detail = '';
  try {
    const ct = res.headers.get('content-type') || '';
    if (ct.includes('application/json')) {
      const body = await res.json();
      const candidate = body && (body.message || body.detail || body.error || body.reason);
      if (typeof candidate === 'string') detail = candidate;
      else if (candidate != null) detail = JSON.stringify(candidate);
    } else {
      const text = (await res.text()).trim();
      // 代理和网关有时把 JSON 错误体标成 text/plain。
      if (text && text.length < 2000) {
        try {
          const body = JSON.parse(text);
          const candidate = body && (body.message || body.detail || body.error || body.reason);
          detail = typeof candidate === 'string' ? candidate : text;
        } catch {
          // HTML 500 页面通常很长且对用户无帮助，短纯文本则保留。
          if (!/^\s*<!doctype|^\s*<html/i.test(text)) detail = text;
        }
      }
    }
  } catch {
    /* 非 JSON / 已读过的 body,忽略 */
  }
  return detail;
}

/**
 * 生成带可读消息的 Error:有 detail 时 `${fallback}:${detail}`,
 * 否则 `${fallback}(HTTP <status>)`,确保调用方总能得到一段可展示文案。
 */
export async function apiError(res: Response, fallback: string): Promise<Error> {
  const detail = await apiErrorDetail(res);
  if (detail) return new Error(`${fallback}:${detail}`);
  return new Error(`${fallback}(HTTP ${res.status})`);
}
