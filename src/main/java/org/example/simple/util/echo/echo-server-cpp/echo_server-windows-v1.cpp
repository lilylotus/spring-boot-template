// 基于非阻塞 socket 与 epoll 的多线程 TCP echo 服务端。
//
// 每个 worker 拥有独立的 epoll 实例和 SO_REUSEPORT 监听 socket，由内核在 worker
// 之间分配连接，连接不会在线程间迁移，因此每个连接的状态无需加锁。

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <signal.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
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

// 客户端发送速度超过其接收回显的速度时停止读取，避免单个慢读客户端让服务端内存无限增长。
constexpr size_t kHighWaterMark = 1024 * 1024;

// 文件描述符耗尽而暂停接入后，最长间隔多久重新尝试接入新连接。
constexpr std::chrono::milliseconds kAcceptRetryInterval(100);

std::mutex g_log_mutex;

void writeAll(int fd, const char* data, size_t len) {
    while (len > 0) {
        ssize_t n = write(fd, data, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return;  // 日志输出端故障不能导致服务端退出。
        }
        data += n;
        len -= static_cast<size_t>(n);
    }
}

// 以毫秒精度格式化当前本地时间。
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

// 收集日志行，每轮事件循环统一写出一次，避免每个请求一次 write 系统调用。
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

// 解码一个 UTF-8 序列并返回其字节长度；字节非法或被截断时返回 0。
// 超长编码、代理区码点以及超过 U+10FFFF 的值均视为非法。
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

// 拒绝控制字符、Unicode 行分隔符和双向文本控制符，它们可能伪造换行或在视觉上打乱日志行。
bool isSafeToLog(uint32_t code_point) {
    if (code_point < 0x20 || (code_point >= 0x7F && code_point <= 0x9F)) return false;
    if (code_point == 0x061C || code_point == 0x200E || code_point == 0x200F) return false;
    if (code_point >= 0x2028 && code_point <= 0x202E) return false;
    if (code_point >= 0x2066 && code_point <= 0x2069) return false;
    return true;
}

// 将请求内容渲染为 UTF-8 日志预览，按字符数截断，保证多字节字符不会被截成两半。
// 客户端数据不得注入控制字符或伪造日志行，其他字符一律显示为 '.'。
// 每次读取单独记录日志，因此跨两次读取的字符也显示为 '.'。
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

// SO_REUSEPORT 会让第二个服务端实例也能绑定同一端口并悄悄分走一部分流量。
// 普通 bind 仍会与这些 socket 冲突，因此先尝试一次普通 bind，端口已被占用时尽早失败。
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
    std::string pending;        // 已读取但尚未被 socket 发送缓冲区接收的字节。
    size_t pending_offset = 0;  // 已发送的前缀长度，避免每次部分发送都移动数据。
    uint32_t interest = EPOLLIN;
    bool peer_closed = false;
    uint64_t bytes_in = 0;
    uint64_t requests = 0;

    size_t pendingSize() const { return pending.size() - pending_offset; }
};

class Worker {
public:
    Worker(int id, int listen_fd, bool log_enabled)
        : listen_fd_(listen_fd), logger_(id, log_enabled), read_buffer_(kReadBufferSize) {
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
        if (spare_fd_ >= 0) close(spare_fd_);
        close(epoll_fd_);
    }

    Worker(const Worker&) = delete;
    Worker& operator=(const Worker&) = delete;

    void requestStop() {
        uint64_t one = 1;
        // 写入失败只说明计数器已非零，唤醒已在等待处理。
        (void)!write(wake_fd_, &one, sizeof(one));
    }

    void run() {
        epoll_event events[kMaxEvents];
        bool running = true;
        while (running) {
            // 暂停接入期间使用有限超时，以便其他 worker 释放 fd 后能及时恢复接入。
            int timeout = accept_paused_ ? static_cast<int>(kAcceptRetryInterval.count()) : -1;
            int count = epoll_wait(epoll_fd_, events, kMaxEvents, timeout);
            if (count < 0) {
                if (errno == EINTR) continue;
                logger_.log(std::string("epoll_wait 失败: ") + strerror(errno));
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
            // 持续有连接事件时 epoll_wait 不会超时，因此按暂停时长判断是否恢复接入。
            if (accept_paused_ &&
                std::chrono::steady_clock::now() - paused_at_ >= kAcceptRetryInterval) {
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
                    // 队列为空时 accept 同样会报告 EMFILE，因此只有确实拒绝了连接才继续循环。
                    if (shedConnection()) continue;
                    // 无法拒绝时监听 fd 会持续可读，水平触发下 epoll 会空转，必须暂停关注。
                    pauseAccept();
                    return;
                }
                if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    logger_.log(std::string("accept 失败: ") + strerror(errno));
                }
                return;
            }

            // echo 流量小且对延迟敏感，关闭 Nagle 合并。
            int on = 1;
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof(on));
            // 开启 TCP keepalive，异常消失的客户端最终会被探测出来并释放 fd。
            setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &on, sizeof(on));

            epoll_event ev{};
            ev.events = EPOLLIN;
            ev.data.fd = fd;
            if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, fd, &ev) < 0) {
                logger_.log("拒绝连接 " + peerName(addr) + ": " + strerror(errno));
                close(fd);
                continue;
            }

            Connection& conn = connections_[fd];
            conn.fd = fd;
            conn.peer = peerName(addr);
            logger_.log("建立连接 " + conn.peer + " fd=" + std::to_string(fd));
        }
    }

    // fd 耗尽时待接入连接会留在 accept 队列中。临时释放预留 fd，接入一个连接后立即关闭，
    // 让客户端尽快感知被拒绝。fd 表由进程内所有线程共享，空出的 fd 可能被其他 worker
    // 抢走，因此预留 fd 可能丢失，下次使用前需先补开。
    // 成功拒绝一个连接时返回 true。
    bool shedConnection() {
        if (spare_fd_ < 0) {
            spare_fd_ = open("/dev/null", O_RDONLY | O_CLOEXEC);
            if (spare_fd_ < 0) return false;
        }
        close(spare_fd_);
        int fd = accept4(listen_fd_, nullptr, nullptr, SOCK_CLOEXEC);
        bool shed = fd >= 0;
        if (shed) {
            close(fd);
            logger_.log("文件描述符耗尽: 已拒绝一个连接");
        }
        spare_fd_ = open("/dev/null", O_RDONLY | O_CLOEXEC);
        return shed;
    }

    // 停止关注监听 fd（保留注册，事件置 0），避免 fd 耗尽时水平触发导致空转。
    void pauseAccept() {
        if (accept_paused_) return;
        epoll_event ev{};
        ev.data.fd = listen_fd_;
        if (epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, listen_fd_, &ev) < 0) {
            logger_.log(std::string("暂停接入失败: ") + strerror(errno));
            return;
        }
        accept_paused_ = true;
        paused_at_ = std::chrono::steady_clock::now();
        logger_.log("文件描述符耗尽: 暂停接入新连接");
    }

    // 恢复关注监听 fd；若 fd 仍然耗尽，下一轮会再次拒绝或暂停。
    void resumeAccept() {
        if (!accept_paused_) return;
        epoll_event ev{};
        ev.events = EPOLLIN;
        ev.data.fd = listen_fd_;
        if (epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, listen_fd_, &ev) < 0) {
            logger_.log(std::string("恢复接入失败: ") + strerror(errno));
            paused_at_ = std::chrono::steady_clock::now();  // 保持暂停，稍后重试。
            return;
        }
        accept_paused_ = false;
        logger_.log("恢复接入新连接");
    }

    void handleConnection(int fd, uint32_t events) {
        auto it = connections_.find(fd);
        if (it == connections_.end()) return;
        Connection& conn = it->second;

        if (events & EPOLLERR) {
            closeConnection(conn, "套接字错误");
            return;
        }
        if ((events & EPOLLOUT) && !flushPending(conn)) return;
        if ((events & (EPOLLIN | EPOLLHUP)) && !conn.peer_closed && !readOnce(conn)) return;
        finishEvent(conn);
    }

    // 每个事件只读一次而不是读空 socket。socket 为水平触发，剩余数据会在下一轮再次
    // 触发，繁忙的客户端也就无法饿死本 worker 上的其他连接。
    // 连接被关闭时返回 false。
    bool readOnce(Connection& conn) {
        ssize_t n = recv(conn.fd, read_buffer_.data(), read_buffer_.size(), 0);
        if (n == 0) {
            conn.peer_closed = true;  // 已排队的回显仍会发送完毕。
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
            logger_.log("请求 " + conn.peer + " bytes=" + std::to_string(len) +
                        " echoed=" + std::to_string(sent) +
                        " queued=" + std::to_string(conn.pendingSize()) +
                        " data=\"" + preview(read_buffer_.data(), len) + "\"");
        }
        return true;
    }

    // 连接被关闭时返回 false。
    bool flushPending(Connection& conn) {
        while (conn.pendingSize() > 0) {
            ssize_t written = send(conn.fd, conn.pending.data() + conn.pending_offset,
                                   conn.pendingSize(), MSG_NOSIGNAL);
            if (written < 0) {
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    // 若队列始终发不空，已发送前缀会一直保留并使高水位限制失效，因此在此压缩。
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

    // 关闭已结束的连接，或按缓冲区状态更新 epoll 关注的事件。
    void finishEvent(Connection& conn) {
        size_t queued = conn.pendingSize();
        if (conn.peer_closed && queued == 0) {
            closeConnection(conn, "客户端关闭");
            return;
        }

        uint32_t interest = 0;
        if (!conn.peer_closed && queued < kHighWaterMark) interest |= EPOLLIN;
        if (queued > 0) interest |= EPOLLOUT;

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
        logger_.log("关闭连接 " + conn.peer + " requests=" + std::to_string(conn.requests) +
                    " bytes=" + std::to_string(conn.bytes_in) + " reason=" + reason);
        int fd = conn.fd;
        close(fd);  // 关闭 fd 同时会将其移出 epoll 集合。
        connections_.erase(fd);
        // 刚释放了一个 fd，若之前因 fd 耗尽暂停接入则立即恢复。
        resumeAccept();
    }

    int listen_fd_;
    int epoll_fd_ = -1;
    int wake_fd_ = -1;
    int spare_fd_ = -1;
    bool accept_paused_ = false;
    std::chrono::steady_clock::time_point paused_at_{};
    Logger logger_;
    std::vector<char> read_buffer_;  // 由本 worker 的所有连接共用，只有本线程访问。
    std::unordered_map<int, Connection> connections_;
};

void usage(const char* program) {
    fprintf(stderr,
            "用法: %s [-p 端口] [-w worker数] [-q]\n"
            "  -p 端口     监听端口（默认 %u）\n"
            "  -w worker数 worker 线程数（默认 CPU 核数，最大 1024）\n"
            "  -q          关闭连接与请求日志（压测时建议开启）\n"
            "  -h          打印本说明\n",
            program, kDefaultPort);
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
    // 日志写入已关闭的管道时不能让进程被 SIGPIPE 杀死；在创建线程前设置，对所有线程生效。
    signal(SIGPIPE, SIG_IGN);

    long port = kDefaultPort;
    long worker_count = static_cast<long>(std::thread::hardware_concurrency());
    if (worker_count < 1) worker_count = 1;
    bool log_enabled = true;

    int opt;
    while ((opt = getopt(argc, argv, "p:w:qh")) != -1) {
        switch (opt) {
            case 'p':
                if (!parsePositive(optarg, 65535, port)) {
                    fprintf(stderr, "端口无效: %s\n", optarg);
                    return 2;
                }
                break;
            case 'w':
                if (!parsePositive(optarg, 1024, worker_count)) {
                    fprintf(stderr, "worker 数无效: %s\n", optarg);
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

    // 在启动线程前屏蔽退出信号，使所有线程继承该掩码，只有下方的 sigwait 接收退出信号。
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGINT);
    sigaddset(&signals, SIGTERM);
    pthread_sigmask(SIG_BLOCK, &signals, nullptr);

    std::vector<std::unique_ptr<Worker>> workers;
    std::vector<std::thread> threads;
    try {
        // 在主线程中绑定，使端口被占用时启动阶段即干净地失败。
        ensurePortAvailable(static_cast<uint16_t>(port));
        for (long i = 0; i < worker_count; ++i) {
            int listen_fd = createListenSocket(static_cast<uint16_t>(port));
            try {
                workers.push_back(
                    std::make_unique<Worker>(static_cast<int>(i), listen_fd, log_enabled));
            } catch (...) {
                close(listen_fd);
                throw;
            }
        }
    } catch (const std::system_error& e) {
        fprintf(stderr, "启动失败: %s\n", e.what());
        return 1;
    }

    for (auto& worker : workers) {
        threads.emplace_back([w = worker.get()] { w->run(); });
    }

    printf("Echo 服务端已监听 0.0.0.0:%ld，worker 数 %ld\n", port, worker_count);
    fflush(stdout);

    int received = 0;
    sigwait(&signals, &received);

    printf("收到信号 %s，正在关闭\n", strsignal(received));
    fflush(stdout);
    for (auto& worker : workers) worker->requestStop();
    for (auto& thread : threads) thread.join();
    return 0;
}
