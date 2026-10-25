package io.github.numai5.data;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import io.github.numai5.provider.Provider;

/**
 * 供应商级请求调度器。
 *
 * <p>同一供应商的聊天 SSE、生图、图生图编辑、魔搭任务提交与每次轮询、模型列表请求
 * （含 HTTP 方法回退）共用这一处调度；CDN 图片下载不占供应商额度，走独立请求。
 *
 * <p>规则（见 {@link Defaults}）：
 * <ol>
 *   <li>同一供应商同时最多 {@code maxConcurrent} 个 HTTP 请求（默认 1）。</li>
 *   <li>两次实际发送之间至少间隔 {@code minIntervalSec} 秒（按供应商配置，默认 5 秒）。</li>
 *   <li>滚动 60 秒窗口内最多 {@code rpmLimit} 次实际发送（默认 6 次）。</li>
 *   <li>每个供应商最多 {@code maxQueue} 个等待任务（默认 20），入队时原子检查，超出立即失败。</li>
 *   <li>429 触发供应商冷却；额度耗尽 / 认证失败暂停该供应商调度，直到显式恢复。</li>
 *   <li>每次实际 HTTP 请求（含回退请求、魔搭轮询）单独消耗一次额度。</li>
 * </ol>
 *
 * <p>任务状态：QUEUED → GRANTED → RUNNING → COMPLETED / FAILED / CANCELLED。
 * 终态任务不再执行、不再重新入队、不重复触发终态清理。
 *
 * <p>时间统一用单调时钟（{@link Clock}），不依赖墙上时间；HTTP 日期的 Retry-After
 * 由 {@code ErrorMapper} 先换算成秒数再映射到单调时钟的冷却期限。
 *
 * <p>典型用法：
 * <pre>
 *   Entry entry = ProviderRateLimiter.submit(provider, RequestType.CHAT);
 *   Denial denial = ProviderRateLimiter.awaitTurn(entry);   // 阻塞直到获准或失败
 *   if (denial != null) { 显示 denial.message; return; }
 *   if (!ProviderRateLimiter.markRunning(entry)) { // 获准后被取消，不得发送
 *       ProviderRateLimiter.finish(entry, State.CANCELLED, null);
 *       return;
 *   }
 *   Call call = client.newCall(request);
 *   ProviderRateLimiter.bind(entry, call::cancel);
 *   try (Response r = call.execute()) { ... }
 *   finally { ProviderRateLimiter.finish(entry, State.COMPLETED, null); }
 * </pre>
 */
public final class ProviderRateLimiter {

    /** 请求类型：共享额度，取消互相隔离 */
    public enum RequestType {
        CHAT, IMAGE, MODEL_LIST
    }

    /** 任务状态；COMPLETED / FAILED / CANCELLED 为终态 */
    public enum State {
        QUEUED, GRANTED, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    /** 未获准发送的原因 */
    public enum Reason {
        QUEUE_FULL, COOLDOWN, QUOTA_EXHAUSTED, AUTH_FAILED, CANCELLED, WAIT_TIMEOUT
    }

    /** 上报给调度器的失败类别（由 api 层从 ErrorMapper.Kind 映射，避免 data→api 循环依赖） */
    public enum Failure {
        RATE_LIMIT, QUOTA, AUTH, OTHER
    }

    /**
     * 默认参数集中管理处。
     * 目前设置界面只开放「请求最小间隔」（{@link Provider#minIntervalSec}），
     * 其余参数集中在这里，避免散落到各个调用类。
     */
    public static final class Defaults {
        /**
         * 同一供应商最大并发 HTTP 请求数。
         *
         * 取 2 而不是 1 是刻意保留「后台绘画 + 新会话聊天」这个既有用法：
         * 聊天 SSE 会一直占着许可直到整段回复结束，若总并发只有 1，后台生图的轮询会被
         * 挤到轮询超时，用户就再也拿不到那张图。这里放开到「聊天 1 + 生图 1」，
         * 同一类型仍然串行（同一时刻不会有 2 个聊天或 2 个生图）。
         * 想更保守可以改成 1，但会牺牲并行能力。
         */
        public static final int MAX_CONCURRENT = 2;
        /** 同一类型的并发上限：保证同一时刻不会有两个聊天或两个生图同时在跑 */
        public static final int MAX_CONCURRENT_PER_TYPE = 1;
        /** 最小实际发送间隔（秒），Provider.minIntervalSec 为 0 时使用该默认值 */
        public static final int MIN_INTERVAL_SEC = 5;
        /** 滚动窗口内的最大发送次数 */
        public static final int RPM_LIMIT = 6;
        /** 滚动窗口长度（毫秒） */
        public static final long RPM_WINDOW_MS = 60000L;
        /** 最多等待任务数（不含正在执行的任务） */
        public static final int MAX_QUEUE = 20;
        /** 自动重试次数：0，429 与 5xx 都不允许自动重发 */
        public static final int AUTO_RETRY = 0;
        /** 429 未给出 Retry-After 时的本地冷却秒数 */
        public static final int DEFAULT_COOLDOWN_SEC = 30;
        /** 冷却上限秒数，防止上游给出离谱的等待时间 */
        public static final int MAX_COOLDOWN_SEC = 600;
        /** 单次聊天操作的等待预算 */
        public static final long CHAT_WAIT_MS = 120000L;
        /** 单次生图操作的等待预算 */
        public static final long IMAGE_WAIT_MS = 300000L;
        /** 单次模型列表请求的等待预算 */
        public static final long MODEL_LIST_WAIT_MS = 30000L;

        private Defaults() {
        }
    }

    /** 时间源：生产用单调时钟；单元测试可替换成虚拟时钟，见 {@link #setClockForTest} */
    public interface Clock {
        long nowMs();

        void sleepMs(long millis) throws InterruptedException;
    }

    private static final Clock REAL_CLOCK = new Clock() {
        @Override
        public long nowMs() {
            return System.nanoTime() / 1000000L;
        }

        @Override
        public void sleepMs(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    private static volatile Clock clock = REAL_CLOCK;

    /** 最近一次 awaitTurn 在队列里等的毫秒数（按线程隔离，调用方可读取） */
    private static final ThreadLocal<Long> LAST_WAIT_MS = new ThreadLocal<>();

    /** 每个供应商一把锁（一把锁 + 一条队列 + 一份额度账本） */
    private static final Map<String, Gate> GATES = new HashMap<>();

    private ProviderRateLimiter() {
    }

    // ===== 测试专用 =====

    /** 仅供单元测试：替换时间源与等待实现；传入 null 恢复真实时钟 */
    public static void setClockForTest(Clock testClock) {
        clock = testClock == null ? REAL_CLOCK : testClock;
    }

    /** 仅供单元测试：清空所有供应商的队列、冷却与暂停状态 */
    public static void resetForTest() {
        synchronized (GATES) {
            GATES.clear();
        }
    }

    // ===== 数据结构 =====

    static final class Gate {
        final Object mutex = new Object();
        /** 等待中的任务（FIFO） */
        final ArrayDeque<Entry> waiting = new ArrayDeque<>();
        /** 已获许可的任务（GRANTED / RUNNING） */
        final ArrayDeque<Entry> active = new ArrayDeque<>();
        /** 滚动窗口内的发送时刻（单调毫秒） */
        final ArrayDeque<Long> sends = new ArrayDeque<>();
        long lastSendMs = Long.MIN_VALUE / 2;
        long seq;
        long cooldownUntilMs;
        String cooldownMessage;
        Reason pausedReason;
        String pausedMessage;
        int runningCount;
        /** 各类型的取消计数：取消后调用方可据此停止后续请求（如魔搭轮询） */
        final long[] cancelEpoch = new long[RequestType.values().length];
    }

    /** 取消句柄：OkHttp 的 Call::cancel 即可满足 */
    public interface Cancellable {
        void cancel();
    }

    /** 一个调度任务 */
    public static final class Entry {
        public final String id;
        public final RequestType type;
        /** 入队时刻（单调毫秒） */
        public final long enqueuedAtMs;
        public final long waitBudgetMs;

        final Gate gate;
        final Provider provider;
        /** 任务自身状态；不同请求互不影响 */
        volatile State state = State.QUEUED;
        volatile boolean cancelRequested;
        /** 终态只处理一次（释放许可也走这里） */
        boolean terminal;
        boolean permitHeld;
        /** 未获准 / 被取消的原因 */
        volatile Denial denial;
        /** 绑定的 HTTP 调用，用于取消活动请求 */
        volatile Cancellable cancellable;

        Entry(Gate gate, Provider provider, RequestType type, long waitBudgetMs, long seq) {
            this.gate = gate;
            this.provider = provider;
            this.type = type;
            this.waitBudgetMs = waitBudgetMs > 0 ? waitBudgetMs : Defaults.CHAT_WAIT_MS;
            this.enqueuedAtMs = clock.nowMs();
            this.id = (provider == null ? "?" : provider.rateKey()) + "#" + type + "-" + seq;
        }

        public State state() {
            return state;
        }

        public boolean isTerminal() {
            return state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED;
        }

        public Denial denial() {
            return denial;
        }

        /** 是否已被请求取消（执行中的任务由调用方 finish 收尾） */
        public boolean isCancelRequested() {
            return cancelRequested;
        }
    }

    /** 未获准发送的原因，message 可直接展示给用户 */
    public static final class Denial {
        public final Reason reason;
        public final String message;
        public final int retryAfterSeconds;

        Denial(Reason reason, String message, int retryAfterSeconds) {
            this.reason = reason;
            this.message = message;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        public String toString() {
            return reason + ": " + message;
        }
    }

    /** 供应商调度状态快照（供设置页展示） */
    public static final class Status {
        public final int queued;
        public final int running;
        public final long cooldownRemainingMs;
        public final Reason pausedReason;
        public final String pausedMessage;

        Status(int queued, int running, long cooldownRemainingMs, Reason pausedReason,
               String pausedMessage) {
            this.queued = queued;
            this.running = running;
            this.cooldownRemainingMs = cooldownRemainingMs;
            this.pausedReason = pausedReason;
            this.pausedMessage = pausedMessage;
        }

        public boolean isPaused() {
            return pausedReason != null;
        }

        public boolean isCoolingDown() {
            return cooldownRemainingMs > 0;
        }
    }

    // ===== 对外 API =====

    public static Gate gateFor(Provider provider) {
        String key = provider == null ? "default" : provider.rateKey();
        synchronized (GATES) {
            Gate gate = GATES.get(key);
            if (gate == null) {
                gate = new Gate();
                GATES.put(key, gate);
            }
            return gate;
        }
    }

    /** 入队（使用该请求类型的默认等待预算） */
    public static Entry submit(Provider provider, RequestType type) {
        return submit(provider, type, waitBudgetOf(type));
    }

    /**
     * 入队，原子检查队列容量与供应商暂停状态。
     * 队列已满或被暂停时返回的任务直接是 FAILED 终态，{@link #awaitTurn} 会立刻返回原因，
     * 不会静默丢弃，也不会无限排队。
     */
    public static Entry submit(Provider provider, RequestType type, long waitBudgetMs) {
        Gate gate = gateFor(provider);
        synchronized (gate.mutex) {
            Entry entry = new Entry(gate, provider, type, waitBudgetMs, ++gate.seq);
            if (gate.pausedReason != null) {
                entry.state = State.FAILED;
                entry.terminal = true;
                entry.denial = new Denial(gate.pausedReason,
                        gate.pausedMessage != null ? gate.pausedMessage : pausedText(gate.pausedReason), 0);
                return entry;
            }
            int waiting = countWaiting(gate);
            int limit = Defaults.MAX_QUEUE;
            if (waiting >= limit) {
                entry.state = State.FAILED;
                entry.terminal = true;
                entry.denial = new Denial(Reason.QUEUE_FULL,
                        "排队已满：该供应商同时最多 " + limit + " 个等待任务，请稍后再试", 0);
                return entry;
            }
            gate.waiting.addLast(entry);
            return entry;
        }
    }

    /**
     * 等到轮到自己，并满足冷却、并发、最小间隔与 RPM 额度。
     *
     * @return null 表示已获准发送；非 null 表示未获准（已转为终态），调用方不得发送请求
     */
    public static Denial awaitTurn(Entry entry) {
        if (entry == null) return null;
        Gate gate = entry.gate;
        long deadline = entry.enqueuedAtMs + entry.waitBudgetMs;
        while (true) {
            long now = clock.nowMs();
            synchronized (gate.mutex) {
                if (entry.terminal || entry.state == State.CANCELLED) {
                    return entry.denial != null ? entry.denial
                            : new Denial(Reason.CANCELLED, "请求已取消", 0);
                }
                if (entry.cancelRequested) {
                    return stop(entry, Reason.CANCELLED, "请求已取消", 0);
                }
                if (gate.pausedReason != null) {
                    return stop(entry, gate.pausedReason,
                            gate.pausedMessage != null ? gate.pausedMessage
                                    : pausedText(gate.pausedReason), 0);
                }
                long cooldownLeft = gate.cooldownUntilMs - now;
                if (cooldownLeft > 0) {
                    if (!isHead(gate, entry)) {
                        // 队列里排在后面：继续等，不占并发许可
                    } else if (cooldownLeft > deadline - now) {
                        // 冷却时间已超过本次操作等待预算：直接失败，不无限等待
                        return stop(entry, Reason.COOLDOWN,
                                "供应商限流冷却中，预计还需 " + ceilSeconds(cooldownLeft)
                                        + " 秒恢复，已超过本次请求的等待上限", ceilSeconds(cooldownLeft));
                    }
                } else if (isHead(gate, entry) && gate.runningCount < maxConcurrent()
                        && runningOfType(gate, entry.type) < Defaults.MAX_CONCURRENT_PER_TYPE) {
                    long intervalLeft = gate.lastSendMs + minIntervalMs(entry) - now;
                    if (intervalLeft <= 0) {
                        pruneWindow(gate, now);
                        if (gate.sends.size() < rpmLimit()) {
                            grant(entry, gate, now);
                            return null;
                        }
                    }
                }
                if (now >= deadline) {
                    return stop(entry, Reason.WAIT_TIMEOUT,
                            "等待发送超时：该供应商队列繁忙或被限流，请稍后重试", 0);
                }
            }
            try {
                clock.sleepMs(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                synchronized (gate.mutex) {
                    return stop(entry, Reason.CANCELLED, "等待被中断，请求已取消", 0);
                }
            }
        }
    }

    /**
     * 获准后、真正发送前的状态转换。
     *
     * @return false 表示获准后被取消，调用方不得发送请求，应 {@link #finish} 收尾
     */
    public static boolean markRunning(Entry entry) {
        if (entry == null) return false;
        Gate gate = entry.gate;
        synchronized (gate.mutex) {
            if (entry.terminal) return false;
            if (entry.cancelRequested) return false;
            if (entry.state != State.GRANTED) return false;
            entry.state = State.RUNNING;
            return true;
        }
    }

    /** 绑定 HTTP 调用；若此前已请求取消，立即 cancel，避免继续发送 */
    public static void bind(Entry entry, Cancellable cancellable) {
        if (entry == null) return;
        entry.cancellable = cancellable;
        if (entry.cancelRequested && cancellable != null) {
            cancellable.cancel();
        }
    }

    /** 任务结束（成功、失败、取消都调用；重复调用只生效一次） */
    public static void finish(Entry entry, State terminalState, Denial denial) {
        if (entry == null) return;
        Gate gate = entry.gate;
        synchronized (gate.mutex) {
            if (entry.terminal) return;
            entry.terminal = true;
            entry.state = terminalState;
            if (denial != null) entry.denial = denial;
            releasePermit(entry, gate);
            gate.waiting.remove(entry);
            gate.active.remove(entry);
            entry.cancellable = null;
        }
    }

    /** 取消单个任务：排队中立刻移出队列；执行中立刻取消其 HTTP 调用 */
    public static void cancel(Entry entry) {
        if (entry == null) return;
        Gate gate = entry.gate;
        Cancellable cancellable;
        synchronized (gate.mutex) {
            if (entry.terminal) return;
            entry.cancelRequested = true;
            cancellable = entry.cancellable;
            if (entry.state == State.QUEUED) {
                entry.terminal = true;
                entry.state = State.CANCELLED;
                entry.denial = new Denial(Reason.CANCELLED, "请求已取消", 0);
                gate.waiting.remove(entry);
            }
        }
        if (cancellable != null) {
            cancellable.cancel();
        }
    }

    /**
     * 取消某类型的所有任务（排队 + 正在执行），其他类型不受影响。
     *
     * @return 被取消的任务数
     */
    public static int cancelCategory(Provider provider, RequestType type) {
        if (provider == null) return 0;
        Gate gate = gateFor(provider);
        java.util.List<Entry> targets = new java.util.ArrayList<>();
        synchronized (gate.mutex) {
            for (Entry e : gate.waiting) {
                if (type == null || e.type == type) targets.add(e);
            }
            for (Entry e : gate.active) {
                if (type == null || e.type == type) targets.add(e);
            }
        }
        for (Entry e : targets) {
            cancel(e);
        }
        synchronized (gate.mutex) {
            if (type == null) {
                for (int i = 0; i < gate.cancelEpoch.length; i++) gate.cancelEpoch[i]++;
            } else {
                gate.cancelEpoch[type.ordinal()]++;
            }
        }
        return targets.size();
    }

    /**
     * 该供应商该类型的取消计数。调用方在长时间循环（如魔搭轮询）开始前记下它，
     * 每轮比较一次：一旦用户取消，后续轮询不再发起新的 HTTP 请求。
     */
    /**
     * 最近一次 {@link #awaitTurn} 因为排队/限流等待了多久（毫秒）。
     * 供魔搭轮询把「排队等待」从自己的总时限里扣除，避免排队把任务挤成超时。
     */
    public static long lastWaitMs() {
        Long value = LAST_WAIT_MS.get();
        return value == null ? 0L : value;
    }

    public static long cancelEpoch(Provider provider, RequestType type) {
        if (provider == null || type == null) return 0;
        Gate gate = gateFor(provider);
        synchronized (gate.mutex) {
            return gate.cancelEpoch[type.ordinal()];
        }
    }

    /** 当前排队名次：1 表示下一个；0 表示未排队（执行中、已取消或不在队列） */
    public static int positionOf(Entry entry) {
        if (entry == null) return 0;
        Gate gate = entry.gate;
        synchronized (gate.mutex) {
            if (entry.terminal || entry.state != State.QUEUED) return 0;
            int pos = 0;
            for (Entry current : gate.waiting) {
                if (current.terminal || current.cancelRequested) continue;
                pos++;
                if (current == entry) return pos;
            }
            return 0;
        }
    }

    /** 供应商调度状态快照 */
    public static Status statusOf(Provider provider) {
        Gate gate = gateFor(provider);
        synchronized (gate.mutex) {
            long now = clock.nowMs();
            return new Status(countWaiting(gate), gate.runningCount,
                    Math.max(0L, gate.cooldownUntilMs - now), gate.pausedReason, gate.pausedMessage);
        }
    }

    /**
     * 上报一次 HTTP 失败，按类别决定是否冷却或暂停。
     *
     * @param message 给用户看的原因（写入冷却/暂停提示）
     */
    public static void onFailure(Provider provider, Failure kind, int retryAfterSeconds, String message) {
        if (provider == null || kind == null) return;
        Gate gate = gateFor(provider);
        if (kind == Failure.OTHER) return;
        synchronized (gate.mutex) {
            if (kind == Failure.RATE_LIMIT) {
                int seconds = retryAfterSeconds > 0 ? retryAfterSeconds : Defaults.DEFAULT_COOLDOWN_SEC;
                if (seconds > Defaults.MAX_COOLDOWN_SEC) seconds = Defaults.MAX_COOLDOWN_SEC;
                long until = clock.nowMs() + seconds * 1000L;
                if (until > gate.cooldownUntilMs) {
                    gate.cooldownUntilMs = until;
                    gate.cooldownMessage = message != null ? message
                            : "上游限流，冷却 " + seconds + " 秒";
                }
            } else if (kind == Failure.QUOTA) {
                gate.pausedReason = Reason.QUOTA_EXHAUSTED;
                gate.pausedMessage = message != null ? message : pausedText(Reason.QUOTA_EXHAUSTED);
            } else if (kind == Failure.AUTH) {
                gate.pausedReason = Reason.AUTH_FAILED;
                gate.pausedMessage = message != null ? message : pausedText(Reason.AUTH_FAILED);
            }
        }
    }

    /** 清除冷却（例如用户确认上游已恢复） */
    public static void clearCooldown(Provider provider) {
        if (provider == null) return;
        Gate gate = gateFor(provider);
        synchronized (gate.mutex) {
            gate.cooldownUntilMs = 0L;
            gate.cooldownMessage = null;
        }
    }

    /**
     * 恢复被暂停的供应商（额度充值、Key 修正后由用户操作触发）。
     * 同时清除冷却，避免恢复后继续被旧的 Retry-After 拦住。
     */
    public static void resume(Provider provider) {
        if (provider == null) return;
        Gate gate = gateFor(provider);
        synchronized (gate.mutex) {
            gate.pausedReason = null;
            gate.pausedMessage = null;
            gate.cooldownUntilMs = 0L;
            gate.cooldownMessage = null;
        }
    }

    // ===== 内部实现 =====

    private static void grant(Entry entry, Gate gate, long now) {
        entry.state = State.GRANTED;
        entry.permitHeld = true;
        gate.runningCount++;
        // 记录本次排队等了多久：长任务（魔搭轮询）据此把等待时间从自己的超时预算里扣掉
        LAST_WAIT_MS.set(now - entry.enqueuedAtMs);
        gate.waiting.remove(entry);
        gate.active.addLast(entry);
        gate.lastSendMs = now;
        // 保守登记：即使随后在发送前被取消，也保留这条记录，避免后续请求突破 RPM
        gate.sends.addLast(now);
    }

    private static void releasePermit(Entry entry, Gate gate) {
        if (!entry.permitHeld) return;
        entry.permitHeld = false;
        if (gate.runningCount > 0) gate.runningCount--;
    }

    private static Denial stop(Entry entry, Reason reason, String message, int retryAfterSeconds) {
        Denial denial = new Denial(reason, message, retryAfterSeconds);
        finish(entry, State.FAILED, denial);
        return denial;
    }

    private static boolean isHead(Gate gate, Entry entry) {
        for (Entry current : gate.waiting) {
            if (current.terminal || current.cancelRequested) continue;
            return current == entry;
        }
        return false;
    }

    private static int countWaiting(Gate gate) {
        int count = 0;
        for (Entry current : gate.waiting) {
            if (current.terminal || current.cancelRequested) continue;
            count++;
        }
        return count;
    }

    private static void pruneWindow(Gate gate, long now) {
        while (!gate.sends.isEmpty() && now - gate.sends.peekFirst() >= Defaults.RPM_WINDOW_MS) {
            gate.sends.pollFirst();
        }
    }

    private static int maxConcurrent() {
        return Defaults.MAX_CONCURRENT;
    }

    private static int runningOfType(Gate gate, RequestType type) {
        int count = 0;
        for (Entry e : gate.active) {
            if (e.type == type && !e.terminal) count++;
        }
        return count;
    }

    private static int rpmLimit() {
        return Defaults.RPM_LIMIT;
    }

    /**
     * 该任务适用的最小发送间隔：优先取供应商配置，未配置（0）时用默认值。
     * 每次判定都重新读取，设置改动立即生效。
     */
    private static long minIntervalMs(Entry entry) {
        int configured = entry != null && entry.provider != null ? entry.provider.minIntervalSec : 0;
        int seconds = configured > 0 ? configured : Defaults.MIN_INTERVAL_SEC;
        return seconds * 1000L;
    }

    private static long waitBudgetOf(RequestType type) {
        if (type == RequestType.IMAGE) return Defaults.IMAGE_WAIT_MS;
        if (type == RequestType.MODEL_LIST) return Defaults.MODEL_LIST_WAIT_MS;
        return Defaults.CHAT_WAIT_MS;
    }

    private static int ceilSeconds(long millis) {
        return (int) Math.max(1L, (millis + 999L) / 1000L);
    }

    private static String pausedText(Reason reason) {
        if (reason == Reason.QUOTA_EXHAUSTED) {
            return "该供应商额度不足，已暂停自动调度；请充值或更换供应商后在设置中保存以恢复";
        }
        if (reason == Reason.AUTH_FAILED) {
            return "该供应商认证失败，已暂停自动调度；请修正 Key 后在设置中保存以恢复";
        }
        return "该供应商已暂停调度";
    }
}
