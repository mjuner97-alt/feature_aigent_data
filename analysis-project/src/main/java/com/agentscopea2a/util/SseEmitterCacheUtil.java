package com.agentscopea2a.util;


import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.TimeUnit;


@Component
public class SseEmitterCacheUtil {

    private static final Logger log = LoggerFactory.getLogger("commonLog");
    private static final Cache<String, SseEmitter> cache =  CacheBuilder.newBuilder()
                .maximumSize(100000)
                .expireAfterAccess(60, TimeUnit.MINUTES)
                .removalListener(notification -> {
                    SseEmitter emitter = (SseEmitter) notification.getValue();
                    if (emitter != null) {
                        emitter.complete();
                        log.info("SseEmitter completed and evicted, chatId: {}", notification.getKey());
                    }
                })
                .build();


    public static void put(String chatId, SseEmitter emitter) {
        emitter.onCompletion(() -> remove(chatId));
        emitter.onTimeout(() -> remove(chatId));
        emitter.onError(e -> remove(chatId));
        cache.put(chatId, emitter);
        log.info("SseEmitter put, chatId: {}", chatId);
    }

    public SseEmitter get(String chatId) {
        SseEmitter emitter = cache.asMap().get(chatId);
        if (emitter == null) {
            log.warn("SseEmitter not found, chatId: {}", chatId);
        }
        return emitter;
    }

    public static void remove(String chatId) {
        cache.invalidate(chatId);
        log.info("SseEmitter removed, chatId: {}", chatId);
    }
}