package org.example.simple.util;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * 线程安全的静态雪花 ID 工具，使用固定的 41/5/5/12 位布局。
 * <p>
 * 可直接调用 {@link #nextId()}，首次调用自动使用节点组合 (0, 0) 初始化。
 * 多实例部署必须在首次发号前调用 {@link #initialize(int, int)} 配置不同的节点组合。
 * 同一 ID 空间内的每个生成器（包括不同 JVM 和类加载器）必须独占节点组合。
 * 本工具不持久化时间戳；重启或复用节点前，必须确保时钟已超过该节点历史生成的最大时间戳，
 * 并确保旧实例已停止。进程内唯一性不能代替部署系统的节点分配与时钟管理。
 * <p>
 * ID 包含时间与节点信息，可被预测，不适合作为密码、访问令牌或授权凭证。
 */
public final class SnowflakeUtils {

    /** 固定纪元，2025-01-01T00:00:00Z 对应的 Unix 毫秒数。 */
    public static final long EPOCH_MILLIS = 1735689600000L;

    /** 数据中心与工作节点编号的最大值，各占五位。 */
    public static final int MAX_NODE_ID = 31;

    /** 未显式配置时使用的数据中心编号，仅适用于独占默认节点组合的部署。 */
    public static final int DEFAULT_DATACENTER_ID = 0;

    /** 未显式配置时使用的工作节点编号。 */
    public static final int DEFAULT_WORKER_ID = 0;

    /** 十二位毫秒内序列的最大值。 */
    private static final int MAX_SEQUENCE = 4095;

    /** 四十一位相对时间戳可表示的最大毫秒数。 */
    private static final long MAX_TIMESTAMP_DELTA = (1L << 41) - 1;

    /** 容许等待恢复的最大时钟回拨毫秒数。 */
    private static final long MAX_ROLLBACK_MILLIS = 5;

    /** 单次时钟等待预算，使用单调时钟计时以免被系统回拨影响。 */
    private static final long WAIT_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    /** 每次等待暂停的纳秒数，避免持续忙等。 */
    private static final long WAIT_PARK_NANOS = TimeUnit.MICROSECONDS.toNanos(100);

    /** 安全发布的一次性生成器，初始化后不再替换。 */
    private static volatile Generator generator;

    private SnowflakeUtils() {
    }

    /**
     * 初始化本进程使用的节点组合，相同参数重复调用不会重置序列或时间戳。
     * 自动初始化与显式初始化中最先完成的配置固定生效，之后不能切换节点。
     *
     * @param datacenterId 数据中心编号，范围为 0 到 31
     * @param workerId 工作节点编号，范围为 0 到 31
     * @throws IllegalArgumentException 编号越界时抛出
     * @throws IllegalStateException 已使用不同节点组合初始化时抛出
     */
    public static synchronized void initialize(int datacenterId, int workerId) {
        validateNodes(datacenterId, workerId);
        if (generator == null) {
            generator = new Generator(datacenterId, workerId, System::currentTimeMillis);
        } else if (generator.datacenterId != datacenterId || generator.workerId != workerId) {
            throw new IllegalStateException("雪花生成器已初始化，不能更改节点配置");
        }
    }

    /**
     * 获取正数雪花 ID，同一生成器按临界区内生成顺序严格递增。
     * <p>
     * 未初始化时自动以节点组合 (0, 0) 初始化；已有显式配置时沿用该配置。
     * <p>
     * 每毫秒至多生成 4096 个 ID。序列耗尽或回拨不超过 5 毫秒时等待时钟恢复，
     * 单次等待预算为 100 毫秒（不含锁竞争及线程调度延迟）。
     *
     * @return 唯一的正数长整型 ID
     * @throws IllegalStateException 时钟越界、严重回拨、等待超时或等待被中断时抛出
     */
    public static long nextId() {
        Generator current = generator;
        if (current == null) {
            synchronized (SnowflakeUtils.class) {
                // 显式初始化可能已经抢先完成，必须在同一把锁内重新读取并沿用其配置。
                current = generator;
                if (current == null) {
                    current = new Generator(DEFAULT_DATACENTER_ID, DEFAULT_WORKER_ID, System::currentTimeMillis);
                    generator = current;
                }
            }
        }
        return current.nextId();
    }

    private static void validateNodes(int datacenterId, int workerId) {
        if (datacenterId < 0 || datacenterId > MAX_NODE_ID || workerId < 0 || workerId > MAX_NODE_ID) {
            throw new IllegalArgumentException("数据中心编号和工作节点编号必须在 0 到 31 之间");
        }
    }

    /** 支持注入时钟的内部生成器，所有可变状态均在同一监视器内更新。 */
    static final class Generator {

        /** 已分配的数据中心编号。 */
        private final int datacenterId;
        /** 已分配的工作节点编号。 */
        private final int workerId;
        /** 返回 Unix 毫秒时间的时钟。 */
        private final LongSupplier clock;
        /** 最近一次成功生成 ID 的时间戳，负值表示尚未生成。 */
        private long lastTimestamp = -1;
        /** 最近一次成功生成 ID 的毫秒内序列。 */
        private int sequence;

        Generator(int datacenterId, int workerId, LongSupplier clock) {
            validateNodes(datacenterId, workerId);
            this.datacenterId = datacenterId;
            this.workerId = workerId;
            this.clock = Objects.requireNonNull(clock, "时钟不能为 null");
        }

        synchronized long nextId() {
            long timestamp = readTimestamp();
            if (timestamp < lastTimestamp) {
                timestamp = awaitTimestamp(lastTimestamp);
            }
            int nextSequence = timestamp == lastTimestamp ? sequence + 1 : 0;
            if (nextSequence > MAX_SEQUENCE) {
                timestamp = awaitTimestamp(lastTimestamp + 1);
                nextSequence = 0;
            }
            long id = ((timestamp - EPOCH_MILLIS) << 22)
                | ((long) datacenterId << 17) | ((long) workerId << 12) | nextSequence;
            // 纪元首毫秒、零节点的首个组合为零，保留零值以确保公共契约始终返回正数。
            if (id == 0) {
                nextSequence = 1;
                id = 1;
            }
            // 等待或校验失败时不能提前改变状态，否则重试可能复用已经发出的序列。
            lastTimestamp = timestamp;
            sequence = nextSequence;
            return id;
        }

        private long readTimestamp() {
            long timestamp = clock.getAsLong();
            if (timestamp < EPOCH_MILLIS || timestamp > EPOCH_MILLIS + MAX_TIMESTAMP_DELTA) {
                throw new IllegalStateException("时钟超出雪花时间戳范围，当前毫秒=" + timestamp);
            }
            if (timestamp < lastTimestamp && lastTimestamp - timestamp > MAX_ROLLBACK_MILLIS) {
                throw new IllegalStateException("时钟回拨超过容忍范围，回拨毫秒数=" + (lastTimestamp - timestamp));
            }
            return timestamp;
        }

        private long awaitTimestamp(long target) {
            long started = System.nanoTime();
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("等待雪花时钟恢复时线程被中断");
                }
                long timestamp = readTimestamp();
                if (timestamp >= target) {
                    return timestamp;
                }
                if (System.nanoTime() - started >= WAIT_TIMEOUT_NANOS) {
                    throw new IllegalStateException("等待雪花时钟恢复超时，目标毫秒=" + target
                        + "，当前毫秒=" + timestamp);
                }
                LockSupport.parkNanos(WAIT_PARK_NANOS);
            }
        }
    }
}
