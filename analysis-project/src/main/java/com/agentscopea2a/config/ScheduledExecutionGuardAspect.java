package com.agentscopea2a.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Globally gates scheduled callbacks with the process-level scheduler switch. */
@Aspect
@Component
public class ScheduledExecutionGuardAspect {
    private static final Logger log = LoggerFactory.getLogger(ScheduledExecutionGuardAspect.class);

    private final boolean enabled;

    public ScheduledExecutionGuardAspect(@Value("${scheduler.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Around("@annotation(scheduled)")
    public Object guardScheduledExecution(ProceedingJoinPoint joinPoint, Scheduled scheduled) throws Throwable {
        if (!enabled) {
            log.debug("Scheduled task skipped because scheduler.enabled=false: {}", joinPoint.getSignature());
            return null;
        }
        return joinPoint.proceed();
    }
}
