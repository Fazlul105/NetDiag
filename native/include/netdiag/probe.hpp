#pragma once

#include <atomic>
#include <cstdint>
#include <string>

namespace netdiag {
enum class Protocol { Tcp = 0, Udp = 1 };
enum class Status { Ok, InvalidInput, Timeout, Cancelled, Refused, Unreachable, ProtocolError, SystemError };

struct Cancellation {
    std::atomic<bool> requested{false};
    void cancel() noexcept { requested.store(true, std::memory_order_relaxed); }
    bool cancelled() const noexcept { return requested.load(std::memory_order_relaxed); }
};

struct Request {
    Protocol protocol;
    std::string address; // Numeric IPv4/IPv6 only: no unbounded DNS operation.
    int port;
    int timeout_ms;
    std::uint64_t network_handle = 0; // Android network binding; unused on Linux.
};

struct Result {
    Status status;
    double elapsed_ms;
    int system_error;
    std::string detail;
};

const char* status_name(Status status) noexcept;
Result probe(const Request& request, const Cancellation& cancellation);
} // namespace netdiag
