package com.agentscopea2a.v2.service;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * /ai/chat 请求级流式超时看门狗。
 *
 * <p>请求默认使用普通档超时；当模型实际派生 analyze_data 子 Agent 时，
 * 可在不中断当前请求的情况下升级为分析档超时。</p>
 *
 * <p>两个档位都从请求进入接口的时刻开始计算，升级分析档不会重新开始计时。</p>
 *
 * <p>设计目标：</p>
 * <ul>
 *     <li>请求级隔离：每个 /ai/chat 请求持有独立的看门狗实例，互不影响。</li>
 *     <li>支持档位升级：普通问答/简单问数/Skill 流程使用普通档；analyze_data 自由分析使用分析档。</li>
 *     <li>同一起点：两档超时均以请求进入接口的时间为基准，升级时只重新计算剩余时间，不重置起点。</li>
 *     <li>幂等终止：超时触发、正常结束、客户端断开、异常清理等路径最终只会执行一次终止动作。</li>
 * </ul>
 */
public final class ChatStreamTimeoutWatchdog {

    /** 存放在 AgentScope RuntimeContext 中的请求级看门狗键。 */
    public static final String RUNTIME_CONTEXT_KEY = "chatStreamTimeoutWatchdog";

    /** 当前超时档位在 RuntimeContext 中的记录键，主要用于日志和链路观测。 */
    public static final String PROFILE_CONTEXT_KEY = "chatExecutionProfile";

    /** 普通问答档和 analyze_data 自由分析档。 */
    public enum Profile { NORMAL, ANALYZE_DATA }

    /** 执行超时任务的共享调度器，由调用方传入，本对象不会负责关闭。 */
    private final ScheduledExecutorService scheduler;

    /**
     * 请求进入 /ai/chat 时记录的单调时钟时间，用于保证两档超时采用同一起点。
     *
     * <p>使用 {@link System#nanoTime()} 而不是 wall clock，是为了避免系统时间调整导致
     * 剩余时间计算出现跳跃或负数。该值只在构造时确定，后续不再变更。</p>
     */
    private final long startedAtNanos;

    /** 普通问答、简单问数和 Skill 流程的整体超时时间，单位毫秒。 */
    private final long normalTimeoutMs;

    /** analyze_data 自由分析流程的整体超时时间，单位毫秒。 */
    private final long analysisTimeoutMs;

    /**
     * 到达当前档位截止时间后执行的终止动作，例如发送超时事件并清理 SSE 请求。
     *
     * <p>该动作由调用方提供，看门狗只负责在正确的时机触发一次，不关心具体清理逻辑。</p>
     */
    private final Runnable terminalAction;

    /** 当前请求使用的超时档位，初始为普通档，最多升级一次。 */
    private final AtomicReference<Profile> profile = new AtomicReference<>(Profile.NORMAL);

    /** 当前生效的定时任务；升级档位或请求结束时会取消并清空。 */
    private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

    /**
     * 定时任务版本号，用于识别并忽略升级或取消之前遗留的旧回调。
     *
     * <p>每次重新调度（scheduleAt）或取消（cancel）都会递增 generation。定时任务回调触发时，
     * 会携带创建它时的 token；如果 token 与当前 generation 不一致，说明该任务已被更新或取消，
     * 直接忽略，避免旧任务误触发终止动作。</p>
     */
    private final AtomicLong generation = new AtomicLong();

    /**
     * 请求是否已经终止，保证超时动作或清理动作只生效一次。
     *
     * <p>所有可能终止请求的路径（超时触发、正常结束、客户端断开、异常清理）都会先尝试
     * CAS false -> true；只有成功的那一个路径会继续执行后续逻辑。</p>
     */
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    /**
     * 便捷构造方法，使用当前时间作为请求开始时间。
     *
     * @param scheduler        共享调度器，用于执行超时任务
     * @param normalTimeoutMs  普通档超时时间（毫秒）
     * @param analysisTimeoutMs 分析档超时时间（毫秒）
     * @param terminalAction   超时后执行的终止动作
     */
    public ChatStreamTimeoutWatchdog(ScheduledExecutorService scheduler, long normalTimeoutMs,
                                     long analysisTimeoutMs, Runnable terminalAction) {
        this(scheduler, System.nanoTime(), normalTimeoutMs, analysisTimeoutMs, terminalAction);
    }

    /**
     * 完整构造方法，允许调用方显式传入请求开始时间。
     *
     * <p>显式传入 startedAtNanos 的场景：调用方在进入 /ai/chat 时已经记录过入口时间，
     * 希望看门狗直接复用该时间，避免在构造看门狗时重新采样导致几毫秒误差。</p>
     *
     * @param scheduler        共享调度器，用于执行超时任务
     * @param startedAtNanos   请求进入接口时的单调时钟时间（纳秒）
     * @param normalTimeoutMs  普通档超时时间（毫秒），最小为 1
     * @param analysisTimeoutMs 分析档超时时间（毫秒），不得小于普通档
     * @param terminalAction   超时后执行的终止动作
     */
    public ChatStreamTimeoutWatchdog(ScheduledExecutorService scheduler, long startedAtNanos,
                                     long normalTimeoutMs, long analysisTimeoutMs, Runnable terminalAction) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.startedAtNanos = startedAtNanos;
        // 普通档至少 1ms，避免传入 0 或负数导致立即超时。
        this.normalTimeoutMs = Math.max(1L, normalTimeoutMs);
        // 分析档不得小于普通档，保证升级后超时时间不会反而缩短。
        this.analysisTimeoutMs = Math.max(this.normalTimeoutMs, analysisTimeoutMs);
        this.terminalAction = Objects.requireNonNull(terminalAction, "terminalAction");
    }

    /**
     * 启动普通档看门狗；重复调用不会重复创建定时任务。
     *
     * <p>幂等性说明：</p>
     * <ul>
     *     <li>如果请求已经终止（terminated=true），直接返回，不创建任务。</li>
     *     <li>如果已经存在生效的定时任务（future != null），直接返回，不重复调度。</li>
     * </ul>
     *
     * <p>方法使用 synchronized 修饰，与 {@link #upgradeToAnalysis()}、{@link #cancel()} 互斥，
     * 避免并发调用时出现重复调度或状态不一致。</p>
     */
    public synchronized void scheduleNormal() {
        if (terminated.get() || future.get() != null) return;
        scheduleAt(normalTimeoutMs);
    }

    /**
     * 将当前请求升级为自由分析档。
     * 取消普通档任务后，按照请求原始开始时间重新计算分析档剩余时间。
     *
     * <p>升级条件：</p>
     * <ul>
     *     <li>请求尚未终止（terminated=false）。</li>
     *     <li>当前档位仍为 NORMAL，且 CAS 成功。由于 profile 只能从 NORMAL 升级到 ANALYZE_DATA 一次，
     *         重复升级会返回 false。</li>
     * </ul>
     *
     * <p>升级动作：</p>
     * <ol>
     *     <li>取消当前普通档定时任务。</li>
     *     <li>以 analysisTimeoutMs 为新的截止时间重新调度；scheduleAt 会扣除从 startedAtNanos 至今
     *         已经消耗的时间，因此不会重置计时起点。</li>
     * </ol>
     *
     * @return true 表示升级成功；false 表示请求已终止或已经处于分析档
     */
    public synchronized boolean upgradeToAnalysis() {
        if (terminated.get() || !profile.compareAndSet(Profile.NORMAL, Profile.ANALYZE_DATA)) {
            return false;
        }
        cancelFuture();
        scheduleAt(analysisTimeoutMs);
        return true;
    }

    /**
     * 正常结束、客户端断开或异常清理时取消看门狗，避免后续误触发。
     *
     * <p>使用 CAS 保证只有一个调用方能够成功将 terminated 置为 true。成功后：</p>
     * <ol>
     *     <li>递增 generation，使所有已经提交但尚未执行的旧回调失效。</li>
     *     <li>取消当前定时任务。</li>
     * </ol>
     *
     * <p>注意：cancel() 不会执行 terminalAction，因为这是正常清理路径，不是超时路径。</p>
     */
    public synchronized void cancel() {
        if (!terminated.compareAndSet(false, true)) return;
        generation.incrementAndGet();
        cancelFuture();
    }

    /** 返回当前超时档位，主要用于日志和链路观测。 */
    public Profile profile() {
        return profile.get();
    }

    /** 返回分析档超时时间（毫秒）。 */
    public long analysisTimeoutMs() {
        return analysisTimeoutMs;
    }

    /** 返回当前档位实际生效的超时时间（毫秒）。 */
    public long activeTimeoutMs() {
        return profile.get() == Profile.ANALYZE_DATA ? analysisTimeoutMs : normalTimeoutMs;
    }

    /**
     * 返回容器级别应使用的最大超时时间。
     *
     * <p>某些外层容器或网关需要设置一个统一的超时时间，不能低于看门狗可能使用的最大档位，
     * 否则容器会先于看门狗终止请求，导致看门狗的超时事件无法正常发出。因此这里返回两档中的较大值。</p>
     */
    public long containerTimeoutMs() {
        return Math.max(normalTimeoutMs, analysisTimeoutMs);
    }

    /**
     * 以请求入口时间为基准，调度当前档位的超时任务。
     *
     * <p>核心逻辑：</p>
     * <ol>
     *     <li>计算从 startedAtNanos 到现在已经消耗的时间 elapsedNanos。</li>
     *     <li>用 timeoutFromStartMs 对应的纳秒数减去 elapsedNanos，得到剩余时间 remainingNanos。</li>
     *     <li>如果剩余时间已经小于等于 0，说明已经超时，使用 0 让调度器尽快执行。</li>
     *     <li>递增 generation 并生成 token，供回调 fireIfCurrent 判断自己是否仍然有效。</li>
     *     <li>提交定时任务，并记录到 future 中，便于后续取消。</li>
     * </ol>
     *
     * <p>注意：本方法没有加 synchronized，因为它只在已经持有锁的 scheduleNormal() 和
     * upgradeToAnalysis() 中调用。generation 和 future 都是并发安全类型，即使单独调用也不会破坏
     * 原子性，但为了状态一致性，仍建议在锁内调用。</p>
     *
     * @param timeoutFromStartMs 从请求开始算起的超时时间（毫秒）
     */
    private void scheduleAt(long timeoutFromStartMs) {
        // 截止时间以请求入口为基准，因此这里扣除创建看门狗之前已经消耗的时间。
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutFromStartMs) - elapsedNanos;
        // 每次调度都生成新的 token，旧回调会因为 generation 不匹配而被忽略。
        long token = generation.incrementAndGet();
        ScheduledFuture<?> scheduled = scheduler.schedule(
                () -> fireIfCurrent(token), Math.max(0L, remainingNanos), TimeUnit.NANOSECONDS);
        future.set(scheduled);
    }

    /**
     * 定时任务回调入口：只有当前有效的任务才会触发终止动作。
     *
     * <p>方法使用 synchronized 修饰，与 upgradeToAnalysis()、cancel() 互斥，避免以下竞态：</p>
     * <ul>
     *     <li>定时任务已经触发，但调用方同时正在升级档位或取消请求。</li>
     *     <li>旧回调与新回调同时执行，导致重复触发 terminalAction。</li>
     * </ul>
     *
     * <p>判断逻辑：</p>
     * <ol>
     *     <li>如果 generation 与 token 不一致，说明该任务已被升级或取消替换，直接忽略。</li>
     *     <li>尝试 CAS terminated false -> true；如果失败，说明请求已经终止，直接忽略。</li>
     *     <li>两者都通过后，执行 terminalAction，且只会执行一次。</li>
     * </ol>
     *
     * @param token 创建该定时任务时生成的版本号
     */
    private synchronized void fireIfCurrent(long token) {
        // generation 用于丢弃升级或取消之前遗留的旧回调；同步锁避免竞态误杀请求。
        if (generation.get() != token || !terminated.compareAndSet(false, true)) return;
        terminalAction.run();
    }

    /**
     * 取消当前生效的定时任务，并将 future 清空。
     *
     * <p>使用 getAndSet(null) 保证即使多个线程同时调用，也只有一个线程能拿到非空 future 并取消，
     * 其他线程拿到 null 后直接返回。cancel(false) 表示不中断正在执行的任务；如果任务已经在执行，
     * 由 fireIfCurrent 中的 generation/terminated 判断来决定是否真正触发终止动作。</p>
     */
    private void cancelFuture() {
        ScheduledFuture<?> current = future.getAndSet(null);
        if (current != null) current.cancel(false);
    }
}