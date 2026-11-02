// 存在问题
// Multi-threaded TCP echo server built on non-blocking sockets and epoll.
//
// Each worker owns an epoll instance and its own SO_REUSEPORT listening socket,
// so the kernel spreads connections across workers and a connection never moves
// between threads. That leaves the per-connection state lock-free.

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

// Stop reading from a client that sends faster than it drains its replies, so
// one slow reader cannot grow server memory without bound.
constexpr size_t kHighWaterMark = 1024 * 1024;

std::mutex g_log_mutex;

void writeAll(int fd, const char* data, size_t len) {
    while (len > 0) {
        ssize_t n = write(fd, data, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return;  // A broken log destination must not take the server down.
        }
        data += n;
        len -= static_cast<size_t>(n);
    }
}

// Formats the current local time with millisecond precision.
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
    std::string pending;        // Bytes read but not yet accepted by the socket.
    size_t pending_offset = 0;  // Consumed prefix, to avoid erasing per partial send.
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
        close(spare_fd_);
        close(epoll_fd_);
    }

    Worker(const Worker&) = delete;
    Worker& operator=(const Worker&) = delete;

    void requestStop() {
        uint64_t one = 1;
        // Failure only means the counter is already non-zero, so a wake is pending.
        (void)!write(wake_fd_, &one, sizeof(one));
    }

    void run() {
        epoll_event events[kMaxEvents];
        bool running = true;
        while (running) {
            int count = epoll_wait(epoll_fd_, events, kMaxEvents, -1);
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
                    if (shedConnection()) continue;
                    return;
                }
                if (errno != EAGAIN && errno != EWOULDBLOCK) {
                    logger_.log(std::string("accept failed: ") + strerror(errno));
                }
                return;
            }

            // Echo traffic is small and latency sensitive, so skip Nagle batching.
            int on = 1;
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, sizeof(on));

            epoll_event ev{};
            ev.events = EPOLLIN;
            ev.data.fd = fd;
            if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, fd, &ev) < 0) {
                logger_.log("rejected " + peerName(addr) + ": " + strerror(errno));
                close(fd);
                continue;
            }

            Connection& conn = connections_[fd];
            conn.fd = fd;
            conn.peer = peerName(addr);
            logger_.log("connect " + conn.peer + " fd=" + std::to_string(fd));
        }
    }

    // When out of descriptors the pending connection stays in the accept queue
    // and level-triggered epoll would spin on it. Temporarily release a reserved
    // descriptor so the connection can be accepted and closed.
    // Returns true if a connection was shed.
    bool shedConnection() {
        close(spare_fd_);
        int fd = accept4(listen_fd_, nullptr, nullptr, SOCK_CLOEXEC);
        bool shed = fd >= 0;
        if (shed) {
            close(fd);
            logger_.log("out of file descriptors: rejected a connection");
        }
        spare_fd_ = open("/dev/null", O_RDONLY | O_CLOEXEC);
        return shed;
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
    bool readOnce(Connection& conn) {
        ssize_t n = recv(conn.fd, read_buffer_.data(), read_buffer_.size(), 0);
        if (n == 0) {
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
    bool flushPending(Connection& conn) {
        while (conn.pendingSize() > 0) {
            ssize_t written = send(conn.fd, conn.pending.data() + conn.pending_offset,
                                   conn.pendingSize(), MSG_NOSIGNAL);
            if (written < 0) {
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    // A queue that never fully drains would otherwise keep the
                    // consumed prefix forever and defeat the high-water limit.
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
        close(fd);  // Also removes the descriptor from the epoll set.
        connections_.erase(fd);
    }

    int listen_fd_;
    int epoll_fd_ = -1;
    int wake_fd_ = -1;
    int spare_fd_ = -1;
    Logger logger_;
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

    // Block signals before starting threads so they all inherit the mask and
    // only the sigwait below receives shutdown signals.
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGINT);
    sigaddset(&signals, SIGTERM);
    pthread_sigmask(SIG_BLOCK, &signals, nullptr);

    std::vector<std::unique_ptr<Worker>> workers;
    std::vector<std::thread> threads;
    try {
        // Bind on the main thread so a busy port is a clean startup failure.
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
        fprintf(stderr, "Startup failed: %s\n", e.what());
        return 1;
    }

    for (auto& worker : workers) {
        threads.emplace_back([w = worker.get()] { w->run(); });
    }

    printf("Echo server listening on 0.0.0.0:%ld with %ld worker(s)\n", port, worker_count);
    fflush(stdout);

    int received = 0;
    sigwait(&signals, &received);

    printf("Received %s, shutting down\n", strsignal(received));
    fflush(stdout);
    for (auto& worker : workers) worker->requestStop();
    for (auto& thread : threads) thread.join();
    return 0;
}
