/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.util;

/**
 * 文件下载失败时回吐的友好 HTML 提示页.
 *
 * <p>下载端点 (CSV 短链 {@code /redirect/download}、Skill 文件 {@code /api/files/{id}/download}、
 * SkillJob 报告 {@code /api/skill-jobs/.../download}) 找不到文件时, 若只回空 body 的 404/400,
 * 浏览器只能显示 "无法访问" 之类通用错误, 用户分不清是链接失效还是文件没了. 这里统一生成一段
 * 自包含 HTML, 浏览器直接渲染中文提示.
 *
 * <p><b>不泄露内部信息</b>: 提示文案全部是固定常量, 不把 service 抛出的 {@code FileNotOnDisk: /path}
 * 之类含磁盘路径的消息回给浏览器; 真实原因由各 controller 写 {@code log.warn}.
 *
 * <p>各 controller 用 {@link #html(String, String)} 拿 HTML 字符串, 再按自身返回类型包成
 * {@code ResponseEntity<byte[]>} (RedirectController) 或 {@code ResponseEntity<Resource>}
 * (包 {@code ByteArrayResource}, Skill 系列 controller).
 */
public final class DownloadErrorPage {

    private DownloadErrorPage() {}

    /** 组装 HTML 提示页 (UTF-8 字符串). title/desc 必须是固定常量, 不可拼用户输入. */
    public static String html(String title, String desc) {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<title>" + title + "</title>\n<style>\n"
                + "body{font-family:-apple-system,\"Segoe UI\",\"Microsoft YaHei\",sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f5f5f5}\n"
                + ".box{text-align:center;padding:48px 40px;background:#fff;border-radius:8px;"
                + "box-shadow:0 2px 12px rgba(0,0,0,.08);max-width:420px}\n"
                + ".icon{font-size:56px;margin-bottom:16px}\n"
                + ".title{font-size:20px;font-weight:600;color:#1f2329;margin-bottom:8px}\n"
                + ".desc{font-size:14px;color:#8f959e;line-height:1.6}\n"
                + "</style>\n</head>\n<body>\n<div class=\"box\">\n"
                + "  <div class=\"icon\">📄</div>\n"
                + "  <div class=\"title\">" + title + "</div>\n"
                + "  <div class=\"desc\">" + desc + "</div>\n"
                + "</div>\n</body>\n</html>";
    }

    /** 链接无效或已过期 (shortCode 不存在/已过期). */
    public static String linkInvalidOrExpired() {
        return html("链接无效或已过期", "该下载链接无效或已失效，请重新生成下载链接。");
    }

    /** 链接格式不正确 (解析 shortCode 拿不到有效参数). */
    public static String linkInvalid() {
        return html("链接无效", "该下载链接格式不正确，请重新生成下载链接。");
    }

    /** 文件不存在或已被删除 (磁盘找不到 / 记录不存在 / 非归属人 - 故意合并, 不区分). */
    public static String fileNotFound() {
        return html("文件不存在", "该下载链接对应的文件已被删除或不存在，请重新生成下载链接。");
    }

    /** 报告尚未生成 (执行记录无输出路径, 任务可能还没跑完). */
    public static String reportNotGenerated() {
        return html("报告尚未生成", "该执行记录暂无报告文件，请等待任务执行完成后再下载。");
    }
}
