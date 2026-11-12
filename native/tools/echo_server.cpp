#include <arpa/inet.h>
#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <iostream>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

namespace { volatile std::sig_atomic_t stopped = 0; void stop(int) { stopped = 1; } }

int main(int argc, char** argv) {
    if (argc != 3) { std::cerr << "Usage: netdiag_echo_server <bind-ipv4> <port>\n"; return 2; }
    char* end = nullptr;
    const long port = std::strtol(argv[2], &end, 10);
    sockaddr_in local{}; local.sin_family = AF_INET;
    if (!end || *end || port < 1 || port > 65535 || ::inet_pton(AF_INET, argv[1], &local.sin_addr) != 1) return 2;
    local.sin_port = htons(static_cast<unsigned short>(port));
    const int tcp = ::socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    const int udp = ::socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (tcp < 0 || udp < 0) { if (tcp >= 0) ::close(tcp); if (udp >= 0) ::close(udp); return 1; }
    const int enabled = 1;
    ::setsockopt(tcp, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
    if (::bind(tcp, reinterpret_cast<sockaddr*>(&local), sizeof(local)) ||
        ::bind(udp, reinterpret_cast<sockaddr*>(&local), sizeof(local)) || ::listen(tcp, 8)) {
        std::cerr << "bind/listen failed: " << errno << '\n'; ::close(tcp); ::close(udp); return 1;
    }
    std::signal(SIGINT, stop); std::signal(SIGTERM, stop);
    std::cout << "TCP listener + UDP echo at " << argv[1] << ':' << port << std::endl;
    while (!stopped) {
        pollfd sockets[]{{tcp, POLLIN, 0}, {udp, POLLIN, 0}};
        if (::poll(sockets, 2, 100) <= 0) continue;
        if (sockets[0].revents & POLLIN) { const int client = ::accept(tcp, nullptr, nullptr); if (client >= 0) ::close(client); }
        if (sockets[1].revents & POLLIN) {
            char bytes[2048]; sockaddr_storage peer{}; socklen_t size = sizeof(peer);
            const auto count = ::recvfrom(udp, bytes, sizeof(bytes), MSG_TRUNC, reinterpret_cast<sockaddr*>(&peer), &size);
            if (count >= 0 && count <= static_cast<ssize_t>(sizeof(bytes)))
                ::sendto(udp, bytes, static_cast<std::size_t>(count), 0, reinterpret_cast<sockaddr*>(&peer), size);
        }
    }
    ::close(tcp); ::close(udp);
}
