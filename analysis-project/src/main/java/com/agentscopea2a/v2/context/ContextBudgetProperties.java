package com.agentscopea2a.v2.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Global model-input budget for both chat endpoints. */
@ConfigurationProperties(prefix = "harness.a2a.context-budget")
public class ContextBudgetProperties {
    private boolean enabled = true;
    private int maxInputTokens = 50000;
    private int reserveOutputTokens = 8000;
    private double warnRatio = 0.80d;
    private double hardRatio = 1.00d;
    private int maxLatestToolTokens = 8000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxInputTokens() { return maxInputTokens; }
    public void setMaxInputTokens(int maxInputTokens) { this.maxInputTokens = maxInputTokens; }
    public int getReserveOutputTokens() { return reserveOutputTokens; }
    public void setReserveOutputTokens(int reserveOutputTokens) { this.reserveOutputTokens = reserveOutputTokens; }
    public double getWarnRatio() { return warnRatio; }
    public void setWarnRatio(double warnRatio) { this.warnRatio = warnRatio; }
    public double getHardRatio() { return hardRatio; }
    public void setHardRatio(double hardRatio) { this.hardRatio = hardRatio; }
    public int getMaxLatestToolTokens() { return maxLatestToolTokens; }
    public void setMaxLatestToolTokens(int maxLatestToolTokens) { this.maxLatestToolTokens = maxLatestToolTokens; }
}
