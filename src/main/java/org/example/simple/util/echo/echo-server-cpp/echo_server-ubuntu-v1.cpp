// Multi-threaded TCP echo server built on non-blocking sockets and epoll.
// 基于非阻塞 socket 和 epoll 的多线程 TCP 回显服务器。
//
// Each worker owns an epoll instance and its own SO_REUSEPORT listening socket,
// so the kernel spreads connections across workers and a connection never moves
// between threads. That leaves the per-connection state lock-free.
// 每个 worker 拥有自己的 epoll 实例和 SO_REUSEPORT 监听 socket，由内核把连接
// 分配给各 worker，连接不会在线程间迁移，因此每个连接的状态都无需加锁。
//
// Overload has two layers of defence, because the descriptor table is shared by
// all workers and a process that actually runs out of descriptors cannot reliably
// reject anyone:
//   1. ConnectionLimit rejects clients before descriptors run out.
//   2. Worker::shedConnection() and pauseAccept() handle the cases the limit cannot
//      prevent, without spinning.
// See echo-server-optimize.md for the failures behind this design and the test results.
// 过载防护分两层，因为 fd 表由所有 worker 共享，进程一旦真的耗尽 fd，就无法
// 可靠地拒绝连接：
//   1. ConnectionLimit 在 fd 耗尽之前拒绝客户端。
//   2. Worker::shedConnection() 和 pauseAccept() 处理上限防不住的情况，且不会空转。
// 这样设计的原因和测试结果见 echo-server-optimize.md。

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <signal.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <string>
#include <system_error>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

constexpr uint16_t kDefaultPort = 19090;
constexpr size_t kReadBufferSize = 64 * 1024;
constexpr int kMaxEvents = 1024;
constexpr size_t kPreviewChars = 32;

// Stop reading from a client that sends faster than it drains its replies, so
// one slow reader cannot grow server memory without bound.
// 客户端发送速度超过接收回显的速度时，停止读取该连接，避免慢客户端让服务端内存无限增长。
constexpr size_t kHighWaterMark = 1024 * 1024;

// How long a worker that ran out of descriptors waits before trying accept again.
// Another worker may free one without this worker seeing any event, so a paused
// listener cannot simply wait to be told that a descriptor is available.
// worker 耗尽 fd 后，等待多久再重试 accept。
// 其他 worker 释放 fd 时，本 worker 可能收不到任何事件，所以暂停监听后不能只等通知。
constexpr int kAcceptRetryMs = 100;

// Descriptors the connection limit leaves free, giving:
//   max connections = soft RLIMIT_NOFILE - kReservedDescriptors
//                     - kDescriptorsPerWorker * workers
// Each worker holds a listener, an epoll, an eventfd and a spare, plus one
// just-accepted connection while rejecting it. The fixed part covers stdio and
// anything inherited from the parent.
// 连接数上限需要留出的 fd 数量，由此得出：
//   最大连接数 = RLIMIT_NOFILE 软上限 - kReservedDescriptors
//                - kDescriptorsPerWorker * worker 数
// 每个 worker 占用监听 socket、epoll、eventfd 和预留 fd 各 1 个，另加 1 个用于暂存
// 正在被拒绝的新连接。固定预留部分留给标准输入输出和从父进程继承的 fd。
constexpr long kDescriptorsPerWorker = 5;
constexpr long kReservedDescriptors = 16;

// Process-wide connection count, shared by all workers.
// 进程级连接计数，由所有 worker 共享。
//
// The kernel completes the TCP handshake before accept, so a client whose connection
// is never accepted believes it is connected and waits for an echo that never comes.
// Rejecting at this limit keeps the process from running out of descriptors, so
// excess clients are accepted and closed at once instead of being left waiting.
// 内核在 accept 之前就完成了 TCP 握手，所以连接即使没有被 accept，客户端也认为已
// 连上，并一直等待不会到来的回显。在此上限处拒绝连接，可以避免进程耗尽 fd，让多余
// 的客户端被 accept 后立即关闭，而不是一直等待。
class ConnectionLimit {
public:
    explicit ConnectionLimit(long max) : max_(max) {}

    long max() const { return max_; }

    // The count can briefly pass max_ while a rejection is undone, by at most one per
    // worker. Those are the just-accepted connections kDescriptorsPerWorker allows
    // for. Relaxed ordering is enough because the counter guards no other data.
    // 撤销拒绝的过程中，计数可能短暂超过 max_，每个 worker 最多超过 1。这些正是
    // kDescriptorsPerWorker 已预留的新连接。该计数器不保护其他数据，relaxed 内存序就够了。
    bool tryAcquire() {
        if (active_.fetch_add(1, std::memory_order_relaxed) < max_) return true;
        release();
        return false;
    }

    void release() { active_.fetch_sub(1, std::memory_order_relaxed); }

private:
    const long max_;
    std::atomic<long> active_{0};
};

std::mutex g_log_mutex;

void writeAll(int fd, const char* data, size_t len) {
    while (len > 0) {
        ssize_t n = write(fd, data, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            // 日志输出目标出错时，不能让服务端跟着退出。
            return;  // A broken log destination must not take the server down.
        }
        data += n;
        len -= static_cast<size_t>(n);
    }
}

// Formats the current local time with millisecond precision.
// 格式化当前本地时间，精确到毫秒。
std::string timestamp() {
    using namespace std::chrono;
    auto now = system_clock::now();
    time_t secs = system_clock::to_time_t(now);
    auto ms = duration_cast<milliseconds>(now.time_since_epoch()).count() % 1000;
    tm local{};
    localtime_r(&secs, &local);
    char buf[32];
    size_t n = strftime(buf, sizeof(buf), "%F %T", &local);
    snprintf(buf + n, sizeof(buf) - n, ".%03d", static_cast<int>(ms));
    return buf;
}

// Collects log lines and writes them once per event-loop pass, which avoids one
// write syscall per request.
// 收集日志行，每轮事件循环写一次，避免每个请求都产生一次 write 系统调用。
class Logger {
public:
    Logger(int worker_id, bool enabled) : worker_id_(worker_id), enabled_(enabled) {}

    bool enabled() const { return enabled_; }

    void log(const std::string& message) {
        if (!enabled_) return;
        buffer_ += timestamp();
        buffer_ += " [worker-";
        buffer_ += std::to_string(worker_id_);
        buffer_ += "] ";
        buffer_ += message;
        buffer_ += '\n';
    }

    void flush() {
        if (buffer_.empty()) return;
        std::lock_guard<std::mutex> lock(g_log_mutex);
        writeAll(STDOUT_FILENO, buffer_.data(), buffer_.size());
        buffer_.clear();
    }

private:
    int worker_id_;
    bool enabled_;
    std::string buffer_;
};

// Decodes one UTF-8 sequence and returns its length, or 0 if the bytes are invalid
// or cut off. Overlong forms, surrogates and values past U+10FFFF count as invalid.
// 解码一个 UTF-8 序列并返回其长度；字节无效或被截断时返回 0。
// 过长编码、代理项和超过 U+10FFFF 的值都视为无效。
size_t decodeUtf8(const unsigned char* p, size_t available, uint32_t& code_point) {
    size_t length;
    uint32_t minimum;
    if (p[0] < 0x80) {
        code_point = p[0];
        return 1;
    } else if ((p[0] & 0xE0) == 0xC0) {
        length = 2;
        minimum = 0x80;
        code_point = p[0] & 0x1F;
    } else if ((p[0] & 0xF0) == 0xE0) {
        length = 3;
        minimum = 0x800;
        code_point = p[0] & 0x0F;
    } else if ((p[0] & 0xF8) == 0xF0) {
        length = 4;
        minimum = 0x10000;
        code_point = p[0] & 0x07;
    } else {
        return 0;
    }
    if (available < length) return 0;
    for (size_t i = 1; i < length; ++i) {
        if ((p[i] & 0xC0) != 0x80) return 0;
        code_point = (code_point << 6) | (p[i] & 0x3F);
    }
    if (code_point < minimum || code_point > 0x10FFFF) return 0;
    if (code_point >= 0xD800 && code_point <= 0xDFFF) return 0;
    return length;
}

// Rejects control characters, plus Unicode line separators and bidi controls,
// which can fake line breaks or visually reorder a log line.
// 拒绝控制字符，以及可能伪造换行或在视觉上打乱日志行的 Unicode 行分隔符和双向控制符。
bool isSafeToLog(uint32_t code_point) {
    if (code_point < 0x20 || (code_point >= 0x7F && code_point <= 0x9F)) return false;
    if (code_point == 0x061C || code_point == 0x200E || code_point == 0x200F) return false;
    if (code_point >= 0x2028 && code_point <= 0x202E) return false;
    if (code_point >= 0x2066 && code_point <= 0x2069) return false;
    return true;
}

// Renders payload for the log as UTF-8 text, limited by characters so that a
// multi-byte character is never cut in half. Client data must not inject control
// characters or fake log lines, so anything else is shown as '.'. A character
// split across two reads is also shown as '.' because each read is logged alone.
// 把数据渲染成用于日志的 UTF-8 文本，按字符数截断，不会把多字节字符切成两半。
// 客户端数据不能注入控制字符或伪造日志行，因此其他内容都显示为 '.'。
// 每次读取单独记录，所以被拆到两次读取里的字符也显示为 '.'。
std::string preview(const char* data, size_t len) {
    const unsigned char* bytes = reinterpret_cast<const unsigned char*>(data);
    std::string out;
    size_t pos = 0;
    for (size_t shown = 0; pos < len && shown < kPreviewChars; ++shown) {
        uint32_t code_point = 0;
        size_t length = decodeUtf8(bytes + pos, len - pos, code_point);
        if (length > 0 && isSafeToLog(code_point)) {
            out.append(data + pos, length);
            pos += length;
        } else {
            out += '.';
            pos += length > 0 ? length : 1;
        }
    }
    if (pos < len) out += "...";
    return out;
}

std::string peerName(const sockaddr_in& addr) {
    char ip[INET_ADDRSTRLEN] = {};
    inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip));
    return std::string(ip) + ":" + std::to_string(ntohs(addr.sin_port));
}

// SO_REUSEPORT would let a second copy of this server bind the same port and
// silently take a share of its traffic. A plain bind still conflicts with those
// sockets, so try one first to fail fast when the port is already served.
// SO_REUSEPORT 会让第二个服务端实例绑定同一端口，并悄悄分走一部分流量。
// 普通 bind 仍会和这些 socket 冲突，所以先试一次，端口已被占用时可以尽早失败。
void ensurePortAvailable(uint16_t port) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) throw std::system_error(errno, std::generic_category(), "socket");

    int on = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);
    int result = bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    int err = errno;
    close(fd);
    if (result < 0) {
        throw std::system_error(err, std::generic_category(),
                                "port " + std::to_string(port));
    }
}

int createListenSocket(uint16_t port) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) throw std::system_error(errno, std::generic_category(), "socket");

    int on = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));
    if (setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &on, sizeof(on)) < 0) {
        int err = errno;
        close(fd);
        throw std::system_error(err, std::generic_category(), "SO_REUSEPORT");
    }

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(port);
    if (bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0 ||
        listen(fd, SOMAXCONN) < 0) {
        int err = errno;
        close(fd);
        throw std::system_error(err, std::generic_category(), "bind/listen");
    }
    return fd;
}

struct Connection {
    int fd = -1;
    std::string peer;
    // 已读取但尚未被 socket 接收的字节。
    std::string pending;        // Bytes read but not yet accepted by the socket.
    // 已发送的前缀长度，避免每次部分发送后都要擦除数据。
    size_t pending_offset = 0;  // Consumed prefix, to avoid erasing per partial send.
    uint32_t interest = EPOLLIN;
    bool peer_closed = false;
    uint64_t bytes_in = 0;
    uint64_t requests = 0;

    size_t pendingSize() const { return pending.size() - pending_offset; }
};

class Worker {
public:
    Worker(int id, int listen_fd, bool log_enabled, ConnectionLimit& limit)
        : listen_fd_(listen_fd),
          limit_(limit),
          logger_(id, log_enabled),
          read_buffer_(kReadBufferSize) {
        epoll_fd_ = epoll_create1(EPOLL_CLOEXEC);
        wake_fd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        spare_fd_ = open("/dev/null", O_RDONLY | O_CLOEXEC);
        if (epoll_fd_ < 0 || wake_fd_ < 0 || spare_fd_ < 0) {
            throw std::system_error(errno, std::generic_category(), "worker setup");
        }
        addToEpoll(listen_fd_, EPOLLIN);
        addToEpoll(wake_fd_, EPOLLIN);
    }

    ~Worker() {
        for (auto& entry : connections_) close(entry.first);
        close(listen_fd_);
        close(wake_fd_);
        close(spare_fd_);
        close(epoll_fd_);
    }

    Worker(const Worker&) = delete;
    Worker& operator=(const Worker&) = delete;

    void requestStop() {
        uint64_t one = 1;
        // Failure only means the counter is already non-zero, so a wake is pending.
        // 写入失败只说明计数器已经非零，也就是已有一次唤醒在等待处理。
        (void)!write(wake_fd_, &one, sizeof(one));
    }

    void run() {
        epoll_event events[kMaxEvents];
        bool running = true;
        while (running) {
            // While accept is paused a timeout is needed so that an idle worker
            // still wakes up to retry.
            // 暂停 accept 期间需要设置超时，这样空闲的 worker 也能醒来重试。
            int count = epoll_wait(epoll_fd_, events, kMaxEvents, accept_paused_ ? kAcceptRetryMs : -1);
            if (count < 0) {
                if (errno == EINTR) continue;
                logger_.log(std::string("epoll_wait failed: ") + strerror(errno));
                break;
            }
            for (int i = 0; i < count; ++i) {
                int fd = events[i].data.fd;
                if (fd == wake_fd_) {
                    running = false;
                } else if (fd == listen_fd_) {
                    acceptAll();
                } else {
                    handleConnection(fd, events[i].events);
                }
            }
            // Checked on every pass, not only on timeout: a worker that keeps receiving
            // traffic may never see epoll_wait time out, and would then miss descriptors
            // freed by other workers.
            // 每轮都检查，而不是只在超时时检查：持续收到流量的 worker 可能一直等不到
            // epoll_wait 超时，从而错过其他 worker 释放的 fd。
            if (accept_paused_ && std::chrono::steady_clock::now() >= accept_retry_at_) {
                resumeAccept();
            }
            logger_.flush();
        }
        logger_.flush();
    }

private:
    void addToEpoll(int fd, uint32_t events) {
        epoll_event ev{};
        ev.events = events;
        ev.data.fd = fd;
        if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, fd, &ev) < 0) {
            throw std::system_error(errno, std::generic_category(), "epoll_ctl add");
        }
    }

    void acceptAll() {
        for (;;) {
            sockaddr_in addr{};
            socklen_t addr_len = sizeof(addr);
            int fd = accept4(listen_fd_, reinterpret_cast<sockaddr*>(&addr), &addr_len,
                             SOCK_NONBLOCK | SOCK_CLOEXEC);
            if (fd < 0) {
                if (errno == EINTR || errno == ECONNABORTED) continue;
                if (errno == EMFILE || errno == ENFILE) {
                    // accept reports EMFILE even when the queue is empty, so only
                    // keep looping while there are connections left to shed.
                    // 队列为空时 accept 也会报 EMFILE，所以只有还能丢弃连接时才继续循环。
                    if (shedConnection()) continue;
                    return;
                }
                if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    logger_.log(std::string("accept failed: ") + strerror(errno));
                }
                return;
            }

            // Over the limit: close right away so the client fails fast. The client sees
            // a successful connect followed by a disconnect.
            // 超过上限：立即关闭，让客户端尽快失败。客户端会看到连接成功后马上被断开。
            if (!limit_.tryAcquire()) {
                close(fd);
                logger_.log("connection limit reached: rejected a connection from " +
                            peerName(addr));
                continue;
            }

            // Echo traffic is small and latency sensitive, so skip Nagle batching.
            // 回显流量小且对延迟敏感，所以关闭 Nagle 算法的合并发送。
            int on = 1;
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof(on));

            epoll_event ev{};
            ev.events = EPOLLIN;
            ev.data.fd = fd;
            if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, fd, &ev) < 0) {
                logger_.log("rejected " + peerName(addr) + ": " + strerror(errno));
                close(fd);
                limit_.release();
                continue;
            }

            Connection& conn = connections_[fd];
            conn.fd = fd;
            conn.peer = peerName(addr);
            logger_.log("connect " + conn.peer + " fd=" + std::to_string(fd));
        }
    }

    // The connection limit normally prevents this, but the system-wide table can
    // still fill up (ENFILE), as can this process if it inherited many descriptors.
    // When out of descriptors the pending connection stays in the accept queue
    // and level-triggered epoll would spin on it. Temporarily release a reserved
    // descriptor so the connection can be accepted and closed.
    // 连接数上限通常能避免走到这里，但系统级 fd 表仍可能占满（ENFILE），进程继承了
    // 大量 fd 时也可能耗尽。fd 耗尽时，待处理的连接留在 accept 队列里，水平触发的
    // epoll 会一直报告并造成空转。此时临时释放预留 fd，用它 accept 连接后立即关闭。
    //
    // The spare alone is not reliable, because the descriptor table is shared:
    //   1. This worker closes its spare, which frees one descriptor number.
    //   2. Another worker's accept takes that number first.
    //   3. This worker's accept still fails with EMFILE and the spare cannot reopen,
    //      so spare_fd_ becomes -1 and the connection stays queued.
    // Without a pause, level-triggered epoll then keeps reporting the listener and
    // the thread spins at full CPU, so stop polling the listener in that case.
    // Returns true if a connection was shed.
    // 只靠预留 fd 并不可靠，因为 fd 表是共享的：
    //   1. 本 worker 关闭预留 fd，腾出一个 fd 号。
    //   2. 其他 worker 的 accept 抢先拿走这个 fd 号。
    //   3. 本 worker 的 accept 仍然报 EMFILE，预留 fd 也打不开，于是 spare_fd_ 变成 -1，
    //      连接继续留在队列里。
    // 如果不暂停，水平触发的 epoll 会持续报告监听 socket，线程空转占满 CPU，
    // 所以这种情况下要停止监听。
    // 成功丢弃一个连接时返回 true。
    bool shedConnection() {
        // 预留 fd 可能已经丢失，原因见上文。
        if (spare_fd_ >= 0) close(spare_fd_);  // May already be lost, as described above.
        int fd = accept4(listen_fd_, nullptr, nullptr, SOCK_CLOEXEC);
        int accept_errno = errno;
        bool shed = fd >= 0;
        if (shed) {
            close(fd);
            logger_.log("out of file descriptors: rejected a connection");
        }
        // 每次调用都会重新尝试打开。
        spare_fd_ = open("/dev/null", O_RDONLY | O_CLOEXEC);  // Retried on every call.
        // Any other failure, such as an empty queue, cannot spin, so needs no pause.
        // 其他失败（例如队列为空）不会造成空转，无需暂停。
        if (!shed && (accept_errno == EMFILE || accept_errno == ENFILE)) pauseAccept();
        return shed;
    }

    void setListenInterest(uint32_t events) {
        epoll_event ev{};
        ev.events = events;
        ev.data.fd = listen_fd_;
        if (epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, listen_fd_, &ev) < 0) {
            logger_.log(std::string("epoll_ctl listen failed: ") + strerror(errno));
        }
    }

    // Stops listener events while keeping it registered. Queued connections stay in
    // the kernel until accept is retried by resumeAccept().
    // 停止接收监听事件，但保留 epoll 注册。排队的连接留在内核中，
    // 等 resumeAccept() 之后再重试 accept。
    void pauseAccept() {
        setListenInterest(0);
        accept_paused_ = true;
        accept_retry_at_ = std::chrono::steady_clock::now() +
                           std::chrono::milliseconds(kAcceptRetryMs);
    }

    // Called on the retry timer or when this worker closes a connection. If descriptors
    // are still exhausted, the next accept simply pauses again.
    // 重试时间到达或本 worker 关闭连接时调用。如果 fd 仍然耗尽，
    // 下一次 accept 会再次暂停。
    void resumeAccept() {
        if (!accept_paused_) return;
        setListenInterest(EPOLLIN);
        accept_paused_ = false;
    }

    void handleConnection(int fd, uint32_t events) {
        auto it = connections_.find(fd);
        if (it == connections_.end()) return;
        Connection& conn = it->second;

        if (events & EPOLLERR) {
            closeConnection(conn, "socket error");
            return;
        }
        if ((events & EPOLLOUT) && !flushPending(conn)) return;
        if ((events & (EPOLLIN | EPOLLHUP)) && !conn.peer_closed && !readOnce(conn)) return;
        finishEvent(conn);
    }

    // Performs one read per event rather than draining the socket. The socket is
    // level-triggered, so leftover data fires again on the next pass, and a busy
    // client cannot starve the other connections on this worker.
    // Returns false if the connection was closed.
    // 每个事件只读一次，而不是把 socket 读空。socket 是水平触发的，剩余数据会在下一轮
    // 再次触发，这样繁忙的客户端不会饿死本 worker 上的其他连接。
    // 连接已关闭时返回 false。
    bool readOnce(Connection& conn) {
        ssize_t n = recv(conn.fd, read_buffer_.data(), read_buffer_.size(), 0);
        if (n == 0) {
            // 已排队的回显仍会继续发送。
            conn.peer_closed = true;  // Queued replies are still delivered.
            return true;
        }
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return true;
            closeConnection(conn, strerror(errno));
            return false;
        }

        size_t len = static_cast<size_t>(n);
        conn.bytes_in += len;
        ++conn.requests;

        size_t sent = 0;
        if (conn.pendingSize() == 0) {
            ssize_t written = send(conn.fd, read_buffer_.data(), len, MSG_NOSIGNAL);
            if (written >= 0) {
                sent = static_cast<size_t>(written);
            } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                closeConnection(conn, strerror(errno));
                return false;
            }
        }
        if (sent < len) conn.pending.append(read_buffer_.data() + sent, len - sent);

        if (logger_.enabled()) {
            logger_.log("request " + conn.peer + " bytes=" + std::to_string(len) +
                        " echoed=" + std::to_string(sent) +
                        " queued=" + std::to_string(conn.pendingSize()) +
                        " data=\"" + preview(read_buffer_.data(), len) + "\"");
        }
        return true;
    }

    // Returns false if the connection was closed.
    // 连接已关闭时返回 false。
    bool flushPending(Connection& conn) {
        while (conn.pendingSize() > 0) {
            ssize_t written = send(conn.fd, conn.pending.data() + conn.pending_offset,
                                   conn.pendingSize(), MSG_NOSIGNAL);
            if (written < 0) {
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    // A queue that never fully drains would otherwise keep the
                    // consumed prefix forever and defeat the high-water limit.
                    // 否则，一直没能发完的队列会永远保留已发送的前缀，使高水位限制失效。
                    if (conn.pending_offset >= kHighWaterMark) {
                        conn.pending.erase(0, conn.pending_offset);
                        conn.pending_offset = 0;
                    }
                    return true;
                }
                closeConnection(conn, strerror(errno));
                return false;
            }
            conn.pending_offset += static_cast<size_t>(written);
        }
        conn.pending.clear();
        conn.pending_offset = 0;
        return true;
    }

    // Closes a finished connection, or updates epoll to match the buffer state.
    // 关闭已结束的连接，或更新 epoll 使其与缓冲区状态一致。
    void finishEvent(Connection& conn) {
        size_t queued = conn.pendingSize();
        if (conn.peer_closed && queued == 0) {
            closeConnection(conn, "client closed");
            return;
        }

        uint32_t interest = 0;
        if (!conn.peer_closed && queued < kHighWaterMark) interest |= EPOLLIN;
        if (queued > 0) interest |= EPOLLOUT;

        // Only touch epoll on a state change: this syscall would otherwise run
        // once per request.
        // 只在状态变化时调用 epoll_ctl，否则每个请求都会多一次系统调用。
        if (interest != conn.interest) {
            epoll_event ev{};
            ev.events = interest;
            ev.data.fd = conn.fd;
            if (epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, conn.fd, &ev) < 0) {
                closeConnection(conn, strerror(errno));
                return;
            }
            conn.interest = interest;
        }
    }

    void closeConnection(Connection& conn, const char* reason) {
        logger_.log("close " + conn.peer + " requests=" + std::to_string(conn.requests) +
                    " bytes=" + std::to_string(conn.bytes_in) + " reason=" + reason);
        int fd = conn.fd;
        // 关闭 fd 时也会将其从 epoll 集合中移除。
        close(fd);  // Also removes the descriptor from the epoll set.
        connections_.erase(fd);
        // 每个已接受的连接都在这里归还名额。
        limit_.release();  // Every accepted connection gives its slot back here.
        // fd 已释放，无需等待重试时间。
        resumeAccept();    // A descriptor is free again, so no need to wait for the timer.
    }

    int listen_fd_;
    ConnectionLimit& limit_;
    int epoll_fd_ = -1;
    int wake_fd_ = -1;
    int spare_fd_ = -1;
    bool accept_paused_ = false;
    std::chrono::steady_clock::time_point accept_retry_at_;
    Logger logger_;
    // 由所有连接共用：只有本线程会使用。
    std::vector<char> read_buffer_;  // Shared by connections: only this thread uses it.
    std::unordered_map<int, Connection> connections_;
};

void usage(const char* program) {
    fprintf(stderr,
            "Usage: %s [-p port] [-w workers] [-q]\n"
            "  -p port     listen port (default %u)\n"
            "  -w workers  worker threads (default: CPU count)\n"
            "  -q          disable connection and request logs\n",
            program, kDefaultPort);
}

// Derives the connection limit from the descriptor limit, or returns 0 if that
// leaves no room for connections. Uses the soft limit because that is the one accept
// hits, so raising it with `ulimit -n` raises the connection limit too.
// 根据 fd 上限计算连接数上限；没有余量留给连接时返回 0。使用软上限是因为 accept
// 触发的正是软上限，所以用 `ulimit -n` 调高后，连接数上限也会随之提高。
long computeMaxConnections(long worker_count) {
    rlimit limit{};
    if (getrlimit(RLIMIT_NOFILE, &limit) < 0) {
        throw std::system_error(errno, std::generic_category(), "getrlimit");
    }
    long descriptors = limit.rlim_cur > static_cast<rlim_t>(LONG_MAX)
                           ? LONG_MAX
                           : static_cast<long>(limit.rlim_cur);
    long reserved = kReservedDescriptors + kDescriptorsPerWorker * worker_count;
    return descriptors > reserved ? descriptors - reserved : 0;
}

bool parsePositive(const char* text, long max, long& out) {
    char* end = nullptr;
    errno = 0;
    long value = strtol(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0' || value < 1 || value > max) return false;
    out = value;
    return true;
}

}  // namespace

int main(int argc, char* argv[]) {
    long port = kDefaultPort;
    long worker_count = static_cast<long>(std::thread::hardware_concurrency());
    if (worker_count < 1) worker_count = 1;
    bool log_enabled = true;

    int opt;
    while ((opt = getopt(argc, argv, "p:w:qh")) != -1) {
        switch (opt) {
            case 'p':
                if (!parsePositive(optarg, 65535, port)) {
                    fprintf(stderr, "Invalid port: %s\n", optarg);
                    return 2;
                }
                break;
            case 'w':
                if (!parsePositive(optarg, 1024, worker_count)) {
                    fprintf(stderr, "Invalid worker count: %s\n", optarg);
                    return 2;
                }
                break;
            case 'q':
                log_enabled = false;
                break;
            case 'h':
                usage(argv[0]);
                return 0;
            default:
                usage(argv[0]);
                return 2;
        }
    }
    if (optind != argc) {
        usage(argv[0]);
        return 2;
    }

    // Socket writes use MSG_NOSIGNAL, but log lines go through write() to stdout. If
    // stdout is a closed pipe (./echo_server | head), SIGPIPE would kill the server on
    // the next log line. Ignored, that write just fails and writeAll() drops the line.
    // socket 写入使用了 MSG_NOSIGNAL，但日志是通过 write() 写到 stdout 的。如果 stdout
    // 是已关闭的管道（./echo_server | head），下一行日志就会触发 SIGPIPE 杀掉服务端。
    // 忽略该信号后，这次写入只会失败，writeAll() 会丢弃这行日志。
    signal(SIGPIPE, SIG_IGN);

    // Block signals before starting threads so they all inherit the mask and
    // only the sigwait below receives shutdown signals.
    // 在启动线程前屏蔽信号，让所有线程继承该掩码，只由下面的 sigwait 接收退出信号。
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGINT);
    sigaddset(&signals, SIGTERM);
    pthread_sigmask(SIG_BLOCK, &signals, nullptr);

    std::unique_ptr<ConnectionLimit> limit;
    std::vector<std::unique_ptr<Worker>> workers;
    std::vector<std::thread> threads;
    try {
        long max_connections = computeMaxConnections(worker_count);
        if (max_connections == 0) {
            fprintf(stderr, "Startup failed: open file limit too low for %ld worker(s)\n",
                    worker_count);
            return 1;
        }
        limit = std::make_unique<ConnectionLimit>(max_connections);

        // Bind on the main thread so a busy port is a clean startup failure.
        // 在主线程上绑定端口，这样端口被占用时可以干净地启动失败。
        ensurePortAvailable(static_cast<uint16_t>(port));
        for (long i = 0; i < worker_count; ++i) {
            int listen_fd = createListenSocket(static_cast<uint16_t>(port));
            try {
                workers.push_back(
                    std::make_unique<Worker>(static_cast<int>(i), listen_fd, log_enabled, *limit));
            } catch (...) {
                close(listen_fd);
                throw;
            }
        }
    } catch (const std::system_error& e) {
        fprintf(stderr, "Startup failed: %s\n", e.what());
        return 1;
    }

    for (auto& worker : workers) {
        threads.emplace_back([w = worker.get()] { w->run(); });
    }

    printf("Echo server listening on 0.0.0.0:%ld with %ld worker(s), max %ld connections\n", port,
           worker_count, limit->max());
    fflush(stdout);

    int received = 0;
    sigwait(&signals, &received);

    printf("Received %s, shutting down\n", strsignal(received));
    fflush(stdout);
    for (auto& worker : workers) worker->requestStop();
    for (auto& thread : threads) thread.join();
    return 0;
}
