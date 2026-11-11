package com.agentscopea2a.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.agentscopea2a.v2.skillManager.service.FlowSchedulerGuard;

/** Globally gates scheduled callbacks with the process-level scheduler switch. */
@Aspect
@Component
public class ScheduledExecutionGuardAspect {
    private final FlowSchedulerGuard schedulerGuard;

    public ScheduledExecutionGuardAspect(FlowSchedulerGuard schedulerGuard) {
        this.schedulerGuard = schedulerGuard;
    }

    @Around("@annotation(scheduled)")
    public Object guardScheduledExecution(ProceedingJoinPoint joinPoint, Scheduled scheduled) throws Throwable {
        if (!schedulerGuard.isAllowed()) {
            return null;
        }
        return joinPoint.proceed();
    }
}
