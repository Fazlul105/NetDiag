#include "netdiag/probe.hpp"

#include <algorithm>
#include <array>
#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cstring>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/multinetwork.h>
#endif

namespace netdiag {
namespace {
using Clock = std::chrono::steady_clock;

class Socket final {
public:
    explicit Socket(int descriptor) noexcept : descriptor_(descriptor) {}
    ~Socket() { if (descriptor_ >= 0) ::close(descriptor_); }
    Socket(const Socket&) = delete;
    Socket& operator=(const Socket&) = delete;
    int get() const noexcept { return descriptor_; }
private:
    int descriptor_;
};

struct Address {
    sockaddr_storage storage{};
    socklen_t size = 0;
    int family = AF_UNSPEC;
    bool loopback = false;
};

bool parse_address(const Request& request, Address& address) {
    auto* v4 = reinterpret_cast<sockaddr_in*>(&address.storage);
    if (::inet_pton(AF_INET, request.address.c_str(), &v4->sin_addr) == 1) {
        v4->sin_family = AF_INET;
        v4->sin_port = htons(static_cast<std::uint16_t>(request.port));
        address.size = sizeof(*v4);
        address.family = AF_INET;
        address.loopback = (ntohl(v4->sin_addr.s_addr) >> 24) == 127;
        return true;
    }
    auto* v6 = reinterpret_cast<sockaddr_in6*>(&address.storage);
    if (::inet_pton(AF_INET6, request.address.c_str(), &v6->sin6_addr) == 1) {
        v6->sin6_family = AF_INET6;
        v6->sin6_port = htons(static_cast<std::uint16_t>(request.port));
        address.size = sizeof(*v6);
        address.family = AF_INET6;
        address.loopback = IN6_IS_ADDR_LOOPBACK(&v6->sin6_addr);
        return true;
    }
    return false;
}

Status error_status(int error) {
    if (error == ECONNREFUSED) return Status::Refused;
    if (error == ETIMEDOUT) return Status::Timeout;
    if (error == ENETUNREACH || error == EHOSTUNREACH || error == ENETDOWN) return Status::Unreachable;
    return Status::SystemError;
}

enum class Wait { Ready, Timeout, Cancelled, Error };
Wait wait_for(int descriptor, short events, Clock::time_point deadline, const Cancellation& cancellation) {
    while (true) {
        if (cancellation.cancelled()) return Wait::Cancelled;
        const auto remaining = deadline - Clock::now();
        if (remaining <= Clock::duration::zero()) return Wait::Timeout;
        // A short poll slice makes native cancellation observable without closing
        // a descriptor from another thread (which risks descriptor-reuse races).
        const auto ms = std::chrono::ceil<std::chrono::milliseconds>(remaining).count();
        pollfd item{descriptor, events, 0};
        const int ready = ::poll(&item, 1, static_cast<int>(std::min<decltype(ms)>(ms, 50)));
        if (cancellation.cancelled()) return Wait::Cancelled;
        if (ready < 0) {
            if (errno == EINTR) continue;
            return Wait::Error;
        }
        if (ready > 0) {
            if (item.revents & POLLNVAL) { errno = EBADF; return Wait::Error; }
            // POLLERR/POLLHUP are consumed by SO_ERROR or recv, preserving errno.
            return Wait::Ready;
        }
    }
}
} // namespace

const char* status_name(Status status) noexcept {
    switch (status) {
        case Status::Ok: return "OK";
        case Status::InvalidInput: return "INVALID_INPUT";
        case Status::Timeout: return "TIMEOUT";
        case Status::Cancelled: return "CANCELLED";
        case Status::Refused: return "REFUSED";
        case Status::Unreachable: return "UNREACHABLE";
        case Status::ProtocolError: return "PROTOCOL_ERROR";
        case Status::SystemError: return "SYSTEM_ERROR";
    }
    return "SYSTEM_ERROR";
}

Result probe(const Request& request, const Cancellation& cancellation) {
    if (request.port < 1 || request.port > 65535 || request.timeout_ms < 100 || request.timeout_ms > 10000 ||
        (request.protocol != Protocol::Tcp && request.protocol != Protocol::Udp) ||
        request.address.empty() || request.address.size() > 64 || request.address.find('\0') != std::string::npos) {
        return {Status::InvalidInput, 0, 0, "Use a numeric IP, port 1-65535, and timeout 100-10000 ms"};
    }
    Address address;
    if (!parse_address(request, address)) return {Status::InvalidInput, 0, 0, "Enter a numeric IPv4 or IPv6 address; DNS and zone IDs are not supported"};
    if (cancellation.cancelled()) return {Status::Cancelled, 0, 0, "Cancelled before socket creation"};

    const auto start = Clock::now();
    const auto deadline = start + std::chrono::milliseconds(request.timeout_ms);
    const auto result = [&](Status status, int error, const char* detail) -> Result {
        return {status, std::chrono::duration<double, std::milli>(Clock::now() - start).count(), error, detail};
    };
    const auto failure = [&](int error, const char* operation) -> Result {
        return result(error_status(error), error, operation);
    };
    const auto waited = [&](Wait outcome) -> Result {
        if (outcome == Wait::Cancelled) return result(Status::Cancelled, 0, "Cancelled");
        if (outcome == Wait::Timeout) return result(Status::Timeout, 0, "Deadline exceeded");
        return failure(errno, "poll failed");
    };

    const bool tcp = request.protocol == Protocol::Tcp;
    Socket socket(::socket(address.family, (tcp ? SOCK_STREAM : SOCK_DGRAM) | SOCK_NONBLOCK | SOCK_CLOEXEC, 0));
    if (socket.get() < 0) return failure(errno, "socket creation failed");
#ifdef __ANDROID__
    // Loopback is always device-local. Other probes use the Android network
    // selected when the request started, rather than silently switching networks.
    if (request.network_handle && !address.loopback &&
        android_setsocknetwork(static_cast<net_handle_t>(request.network_handle), socket.get()) != 0) {
        return failure(errno, "Binding to the selected Android network failed");
    }
#endif
    const auto connect_start = Clock::now();
    const int connected = ::connect(socket.get(), reinterpret_cast<const sockaddr*>(&address.storage), address.size);
    if (connected < 0) {
        const int error = errno;
        if (error != EINPROGRESS && error != EWOULDBLOCK && error != EINTR) return failure(error, "connect failed");
        const auto ready = wait_for(socket.get(), POLLOUT, deadline, cancellation);
        if (ready != Wait::Ready) return waited(ready);
        int connection_error = 0;
        socklen_t error_size = sizeof(connection_error);
        if (::getsockopt(socket.get(), SOL_SOCKET, SO_ERROR, &connection_error, &error_size) < 0) return failure(errno, "SO_ERROR query failed");
        if (connection_error) return failure(connection_error, "connect failed");
    }
    if (cancellation.cancelled()) return result(Status::Cancelled, 0, "Cancelled");
    if (tcp) {
        return {Status::Ok, std::chrono::duration<double, std::milli>(Clock::now() - connect_start).count(), 0,
                "TCP connection established; no application request was sent"};
    }

    // One bounded datagram with an identifier unique to this process/request.
    // A connected UDP socket filters other peers; exact payload matching rejects
    // malformed/oversized/stale replies rather than reporting them as valid RTT.
    static std::atomic<std::uint64_t> sequence{0};
    std::array<unsigned char, 32> payload{};
    std::memcpy(payload.data(), "NetDiag-UDP-v1", 14);
    const auto nonce = sequence.fetch_add(1, std::memory_order_relaxed);
    const auto tick = Clock::now().time_since_epoch().count();
    std::memcpy(payload.data() + 16, &nonce, sizeof(nonce));
    static_assert(sizeof(tick) <= 8);
    std::memcpy(payload.data() + 24, &tick, sizeof(tick));
    const auto send_start = Clock::now();
    while (true) {
        if (cancellation.cancelled()) return result(Status::Cancelled, 0, "Cancelled");
        const auto sent = ::send(socket.get(), payload.data(), payload.size(), 0);
        if (sent == static_cast<ssize_t>(payload.size())) break;
        if (sent >= 0) return result(Status::SystemError, EMSGSIZE, "Partial UDP datagram");
        if (errno == EINTR) { if (Clock::now() >= deadline) return result(Status::Timeout, 0, "Deadline exceeded"); continue; }
        if (errno != EAGAIN && errno != EWOULDBLOCK) return failure(errno, "UDP send failed");
        const auto ready = wait_for(socket.get(), POLLOUT, deadline, cancellation);
        if (ready != Wait::Ready) return waited(ready);
    }
    while (true) {
        const auto ready = wait_for(socket.get(), POLLIN, deadline, cancellation);
        if (ready != Wait::Ready) return waited(ready);
        std::array<unsigned char, 64> reply{};
        const auto received = ::recv(socket.get(), reply.data(), reply.size(), MSG_TRUNC);
        if (received < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
            return failure(errno, "UDP receive failed");
        }
        if (received != static_cast<ssize_t>(payload.size()) || !std::equal(payload.begin(), payload.end(), reply.begin())) {
            return result(Status::ProtocolError, 0, "Reply did not exactly match the UDP echo payload");
        }
        return {Status::Ok, std::chrono::duration<double, std::milli>(Clock::now() - send_start).count(), 0,
                "Matching UDP echo received; one datagram round trip"};
    }
}
} // namespace netdiag
