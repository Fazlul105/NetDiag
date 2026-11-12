#include "netdiag/probe.hpp"

#include <arpa/inet.h>
#include <chrono>
#include <dirent.h>
#include <functional>
#include <iostream>
#include <map>
#include <poll.h>
#include <stdexcept>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

namespace {
void expect(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }

class Peer {
public:
    Peer(bool tcp, bool echo = false, bool malformed = false, int family = AF_INET) : family_(family) {
        fd_ = ::socket(family, (tcp ? SOCK_STREAM : SOCK_DGRAM) | SOCK_CLOEXEC, 0);
        expect(fd_ >= 0, "test socket creation failed");
        sockaddr_storage address{};
        socklen_t size;
        if (family == AF_INET) {
            auto* v4 = reinterpret_cast<sockaddr_in*>(&address);
            v4->sin_family = AF_INET;
            v4->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            size = sizeof(*v4);
        } else {
            auto* v6 = reinterpret_cast<sockaddr_in6*>(&address);
            v6->sin6_family = AF_INET6;
            v6->sin6_addr = in6addr_loopback;
            size = sizeof(*v6);
        }
        if (::bind(fd_, reinterpret_cast<sockaddr*>(&address), size) != 0 ||
            (tcp && ::listen(fd_, 16) != 0) || ::getsockname(fd_, reinterpret_cast<sockaddr*>(&address), &size) != 0) {
            ::close(fd_); fd_ = -1; throw std::runtime_error("test bind/listen failed");
        }
        port = family == AF_INET ? ntohs(reinterpret_cast<sockaddr_in*>(&address)->sin_port)
                                 : ntohs(reinterpret_cast<sockaddr_in6*>(&address)->sin6_port);
        if (echo) {
            worker_ = std::thread([this, malformed] {
                while (!stop_.load()) {
                    pollfd item{fd_, POLLIN, 0};
                    if (::poll(&item, 1, 20) <= 0) continue;
                    char buffer[512];
                    sockaddr_storage remote{};
                    socklen_t size = sizeof(remote);
                    const auto received = ::recvfrom(fd_, buffer, sizeof(buffer), 0, reinterpret_cast<sockaddr*>(&remote), &size);
                    if (received <= 0) continue;
                    if (malformed) buffer[0] ^= 0x7f;
                    ::sendto(fd_, buffer, static_cast<std::size_t>(received), 0, reinterpret_cast<sockaddr*>(&remote), size);
                }
            });
        }
    }
    ~Peer() { stop_.store(true); if (worker_.joinable()) worker_.join(); if (fd_ >= 0) ::close(fd_); }
    Peer(const Peer&) = delete;
    Peer& operator=(const Peer&) = delete;
    netdiag::Request request(netdiag::Protocol protocol, int timeout = 500) const {
        return {protocol, family_ == AF_INET ? "127.0.0.1" : "::1", port, timeout};
    }
    int port = 0;
private:
    int fd_ = -1;
    int family_;
    std::atomic<bool> stop_{false};
    std::thread worker_;
};

int descriptors() {
    DIR* directory = ::opendir("/proc/self/fd");
    expect(directory != nullptr, "cannot inspect own descriptors");
    int count = 0;
    while (auto* entry = ::readdir(directory)) if (entry->d_name[0] != '.') ++count;
    ::closedir(directory);
    return count;
}

void invalid_input() {
    netdiag::Cancellation token;
    for (const auto& request : {netdiag::Request{netdiag::Protocol::Tcp, "example.invalid", 80, 500},
                                {netdiag::Protocol::Tcp, "127.0.0.1", 0, 500},
                                {netdiag::Protocol::Udp, "127.0.0.1", 65536, 500},
                                {netdiag::Protocol::Tcp, "127.0.0.1", 80, 99},
                                {netdiag::Protocol::Udp, "::1", 80, 10001}}) {
        expect(netdiag::probe(request, token).status == netdiag::Status::InvalidInput, "invalid request was accepted");
    }
}
void tcp_connect() {
    Peer peer(true);
    netdiag::Cancellation token;
    const auto result = netdiag::probe(peer.request(netdiag::Protocol::Tcp), token);
    expect(result.status == netdiag::Status::Ok && result.elapsed_ms >= 0, "TCP connection failed");
}
void tcp_refused() {
    // Reserve a dynamically allocated TCP port without listening. A TCP connect
    // to it deterministically receives refusal, without a port-allocation race.
    const int fd = ::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    expect(fd >= 0, "socket failed");
    sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    expect(::bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0, "bind failed");
    socklen_t size = sizeof(address);
    expect(::getsockname(fd, reinterpret_cast<sockaddr*>(&address), &size) == 0, "getsockname failed");
    netdiag::Cancellation token;
    const auto result = netdiag::probe({netdiag::Protocol::Tcp, "127.0.0.1", ntohs(address.sin_port), 500}, token);
    ::close(fd);
    expect(result.status == netdiag::Status::Refused && result.system_error != 0, "TCP SO_ERROR did not report refusal");
}
void udp_echo() {
    Peer peer(false, true);
    netdiag::Cancellation token;
    const auto result = netdiag::probe(peer.request(netdiag::Protocol::Udp), token);
    expect(result.status == netdiag::Status::Ok && result.elapsed_ms >= 0, "UDP echo failed");
}
void udp_timeout() {
    Peer peer(false); // Bound but deliberately silent; no external network needed.
    netdiag::Cancellation token;
    const auto result = netdiag::probe(peer.request(netdiag::Protocol::Udp, 150), token);
    expect(result.status == netdiag::Status::Timeout, "silent UDP peer did not time out");
    expect(result.elapsed_ms >= 140 && result.elapsed_ms < 2000, "timeout did not respect its deadline");
}
void udp_malformed() {
    Peer peer(false, true, true);
    netdiag::Cancellation token;
    expect(netdiag::probe(peer.request(netdiag::Protocol::Udp), token).status == netdiag::Status::ProtocolError,
           "corrupt echo was accepted");
}
void cancelled_before() {
    netdiag::Cancellation token; token.cancel();
    expect(netdiag::probe({netdiag::Protocol::Tcp, "127.0.0.1", 7777, 500}, token).status == netdiag::Status::Cancelled,
           "pre-cancelled request ran");
}
void cancelled_during() {
    Peer peer(false);
    netdiag::Cancellation token;
    std::thread cancel([&token] { std::this_thread::sleep_for(std::chrono::milliseconds(30)); token.cancel(); });
    const auto result = netdiag::probe(peer.request(netdiag::Protocol::Udp, 3000), token);
    cancel.join();
    expect(result.status == netdiag::Status::Cancelled && result.elapsed_ms < 1500, "active cancellation did not interrupt poll");
}
void fd_cleanup() {
    Peer silent(false);
    Peer malformed(false, true, true);
    const int before = descriptors();
    for (int i = 0; i < 25; ++i) {
        netdiag::Cancellation token;
        expect(netdiag::probe(silent.request(netdiag::Protocol::Udp, 100), token).status == netdiag::Status::Timeout, "timeout failed");
        expect(netdiag::probe(malformed.request(netdiag::Protocol::Udp), token).status == netdiag::Status::ProtocolError, "malformed probe failed");
    }
    expect(descriptors() == before, "socket descriptor leak after timeout/protocol-error paths");
}
void ipv6() {
    Peer tcp(true, false, false, AF_INET6);
    Peer udp(false, true, false, AF_INET6);
    netdiag::Cancellation token;
    expect(netdiag::probe(tcp.request(netdiag::Protocol::Tcp), token).status == netdiag::Status::Ok, "IPv6 TCP failed");
    expect(netdiag::probe(udp.request(netdiag::Protocol::Udp), token).status == netdiag::Status::Ok, "IPv6 UDP failed");
}
} // namespace

int main(int argc, char** argv) {
    const std::map<std::string, std::function<void()>> cases{
        {"invalid_input", invalid_input}, {"tcp_connect", tcp_connect}, {"tcp_refused", tcp_refused},
        {"udp_echo", udp_echo}, {"udp_timeout", udp_timeout}, {"udp_malformed", udp_malformed},
        {"cancelled_before", cancelled_before}, {"cancelled_during", cancelled_during},
        {"fd_cleanup", fd_cleanup}, {"ipv6", ipv6}};
    int failures = 0;
    if (argc > 1 && !cases.count(argv[1])) { std::cerr << "Unknown test\n"; return 2; }
    for (const auto& [name, test] : cases) {
        if (argc > 1 && name != argv[1]) continue;
        try { test(); std::cout << "PASS " << name << '\n'; }
        catch (const std::exception& error) { ++failures; std::cerr << "FAIL " << name << ": " << error.what() << '\n'; }
    }
    return failures ? 1 : 0;
}
