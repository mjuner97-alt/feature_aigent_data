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
package com.agentscopea2a.v2.skillManager.scheduler;

/**
 * WriteMarkdownTool 写入成功后的回调接口。
 * SkillJobScheduler 实现此接口，在回调中通过 executionId 标记 md_file_written=true。
 *
 * <p>回调携带 executionId，让 Scheduler 能精确定位是哪条执行记录，
 * 而不是依赖 ConcurrentHashMap 按路径模糊匹配。
 */
public interface WriteCallback {
    void onMarkdownWritten(String filePath, Long executionId);
}
