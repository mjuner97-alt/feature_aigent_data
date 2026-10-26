package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.HashSet;
import java.util.Set;

import static com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys.LONG_TASK_SCHEDULER_IP;

/** Fail closed before touching shared long-task execution state. No session cache. */
@Component
public class FlowSchedulerGuard {
    private static final Logger log = LoggerFactory.getLogger(FlowSchedulerGuard.class);
    private final AiChatRuntimeConfigMapper mapper;
    private final LocalAddresses addresses;
    private String lastDecision;

    @FunctionalInterface
    interface LocalAddresses { Set<String> get() throws Exception; }

    @Autowired
    public FlowSchedulerGuard(AiChatRuntimeConfigMapper mapper) {
        this(mapper, FlowSchedulerGuard::localAddresses);
    }

    FlowSchedulerGuard(AiChatRuntimeConfigMapper mapper, LocalAddresses addresses) {
        this.mapper = mapper;
        this.addresses = addresses;
    }

    public boolean isAllowed() {
        try {
            var entry = mapper.selectByConfigKey(LONG_TASK_SCHEDULER_IP);
            String configured = entry == null || entry.getConfigValue() == null
                    ? "" : entry.getConfigValue().trim();
            if (configured.isEmpty()) return decision(false, "scheduler IP is not configured");
            // Only IP literals: never resolve a hostname or accept a list/wildcard.
            if (!(configured.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
                    || (configured.contains(":") && configured.matches("[0-9a-fA-F:.]+")))) {
                return decision(false, "scheduler IP must be a single IP literal");
            }
            InetAddress target = InetAddress.getByName(configured);
            if (target.isLoopbackAddress() || target.isAnyLocalAddress() || target.isMulticastAddress()) {
                return decision(false, "scheduler IP must be a non-loopback interface address");
            }
            // Interface enumeration appends an IPv6 scope (e.g. %eth0). The configured
            // single IP identifies any local interface carrying that address.
            boolean allowed = addresses.get().stream()
                    .map(address -> address.split("%", 2)[0])
                    .anyMatch(target.getHostAddress()::equals);
            return decision(allowed, allowed ? "local IP matches " + configured : "local IP does not match " + configured);
        } catch (Exception e) {
            return decision(false, "configuration or local interface lookup failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private synchronized boolean decision(boolean allowed, String reason) {
        String state = allowed + ":" + reason;
        if (!state.equals(lastDecision)) {
            if (allowed) log.info("Long-task scheduler allowed: {}", reason);
            else log.warn("Long-task scheduler blocked: {}", reason);
            lastDecision = state;
        }
        return allowed;
    }

    private static Set<String> localAddresses() throws Exception {
        Set<String> result = new HashSet<>();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return result;
        while (interfaces.hasMoreElements()) {
            var network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            var entries = network.getInetAddresses();
            while (entries.hasMoreElements()) {
                var address = entries.nextElement();
                if (!address.isLoopbackAddress() && !address.isAnyLocalAddress() && !address.isMulticastAddress()) {
                    result.add(address.getHostAddress());
                }
            }
        }
        return result;
    }
}
