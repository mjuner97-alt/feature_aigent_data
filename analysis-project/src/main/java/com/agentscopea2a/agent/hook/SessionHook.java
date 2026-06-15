package com.agentscopea2a.agent.hook;

import cn.hutool.core.util.ObjectUtil;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

public class SessionHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {

        if (event instanceof PreActingEvent) {
            ToolUseBlock toolUse = ((PreActingEvent) event).getToolUse();
            Map<String, Object> originalInput = toolUse.getInput();

            Map<String, Object> newMap = new HashMap<>();
            if (ObjectUtil.isNotNull(originalInput)) {
                for (Map.Entry<String, Object> entry : originalInput.entrySet()) {
                    String key = entry.getKey();
                    Object valueObj = entry.getValue();
                    String value = valueObj != null ? valueObj.toString() : "";
                    value = value.replaceAll("regex", " "); // 替换你的实际正则
                    newMap.put(key, value);
                }
            }

            // 如果需要创建新的 ToolUseBlock
            ToolUseBlock useBlock = ToolUseBlock.builder()
                    .name(toolUse.getName())
                    .content(toolUse.getContent())
                    .metadata(toolUse.getMetadata())
                    .input(newMap)
                    .build();

            System.out.println(newMap);
        }
        return Mono.just(event);
    }
}
