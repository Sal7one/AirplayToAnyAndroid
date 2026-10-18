#include "jni/native_handles.h"

#include <atomic>
#include <chrono>
#include <future>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

using namespace std::chrono_literals;

namespace {

void check(bool condition) { if (!condition) throw std::runtime_error("Native handle assertion failed"); }

template <typename Error, typename Operation>
void rejects(Operation&& operation) {
    try { operation(); } catch (const Error&) { return; }
    throw std::runtime_error("Expected native exception");
}

struct Resource {
    static std::atomic<int> live;
    int value;
    explicit Resource(int input = 0) : value(input) {
        if (input < 0) throw std::invalid_argument("bad input");
        if (input == 13) throw std::bad_alloc();
        ++live;
    }
    ~Resource() { --live; }
};
std::atomic<int> Resource::live{0};
using Handles = fossmirror::jni::NativeHandles<Resource, 0x46>;

void valuesAndTags() {
    Handles first;
    fossmirror::jni::NativeHandles<Resource, 0x41> second;
    const auto a = first.create(7);
    const auto b = second.create(8);
    check(a > 0 && b > 0 && a != b);
    check(first.with(a, [](Resource& resource) { return resource.value; }) == 7);
    for (const auto invalid : {int64_t{0}, int64_t{-1}, std::numeric_limits<int64_t>::max(), b, a + 256}) {
        rejects<std::logic_error>([&] { first.with(invalid, [](Resource&) { }); });
        first.close(invalid);
    }
    first.close(a);
    first.close(a);
    rejects<std::logic_error>([&] { first.with(a, [](Resource&) { }); });
    check(second.with(b, [](Resource& resource) { return resource.value; }) == 8);
    second.close(b);
    check(Resource::live == 0);
}

void limitsAndFailedConstruction() {
    rejects<std::invalid_argument>([] { Handles invalid(0); });
    rejects<std::invalid_argument>([] { Handles invalid(65); });
    Handles handles(2);
    rejects<std::invalid_argument>([&] { handles.create(-1); });
    rejects<std::bad_alloc>([&] { handles.create(13); });
    check(Resource::live == 0);
    const auto a = handles.create(1);
    const auto b = handles.create(2);
    rejects<std::logic_error>([&] { handles.create(3); });
    handles.close(a);
    const auto c = handles.create(3);
    check(c > b && c != a);
    rejects<std::logic_error>([&] { handles.with(a, [](Resource&) { }); });
    rejects<std::invalid_argument>([&] {
        handles.with(c, [](Resource&) { throw std::invalid_argument("operation failed"); });
    });
    handles.close(b);
    handles.close(c);
    check(Resource::live == 0);
}

void operationsAreSerialized() {
    Handles handles;
    const auto handle = handles.create();
    std::vector<std::thread> workers;
    for (int worker = 0; worker < 8; ++worker) {
        workers.emplace_back([&] {
            for (int iteration = 0; iteration < 1000; ++iteration)
                handles.with(handle, [](Resource& resource) { ++resource.value; });
        });
    }
    for (auto& worker : workers) worker.join();
    check(handles.with(handle, [](Resource& resource) { return resource.value; }) == 8000);
    handles.close(handle);
    check(Resource::live == 0);
}

void concurrentCloseWaitsWithoutBlockingOtherSessions() {
    Handles handles;
    const auto handle = handles.create(1);
    const auto other = handles.create(2);
    std::promise<void> entered;
    std::promise<void> release;
    auto released = release.get_future().share();
    auto operation = std::async(std::launch::async, [&] {
        handles.with(handle, [&](Resource& resource) { entered.set_value(); released.wait(); check(resource.value == 1); });
    });
    entered.get_future().wait();
    auto close1 = std::async(std::launch::async, [&] { handles.close(handle); });
    auto close2 = std::async(std::launch::async, [&] { handles.close(handle); });
    const bool firstWaited = close1.wait_for(20ms) == std::future_status::timeout;
    const bool secondWaited = close2.wait_for(20ms) == std::future_status::timeout;
    auto unrelated = std::async(std::launch::async, [&] {
        return handles.with(other, [](Resource& resource) { return resource.value; });
    });
    const bool unrelatedReady = unrelated.wait_for(1s) == std::future_status::ready;
    release.set_value();
    operation.get(); close1.get(); close2.get();
    check(firstWaited && secondWaited && unrelatedReady && unrelated.get() == 2);
    rejects<std::logic_error>([&] { handles.with(handle, [](Resource&) { }); });
    check(Resource::live == 1);
    handles.close(other);
    check(Resource::live == 0);
}

void staleHandlesNeverBecomeNewSessions() {
    Handles handles;
    std::atomic<int64_t> current{0};
    std::atomic<bool> stop{false};
    std::atomic<int> failures{0};
    std::vector<std::thread> workers;
    for (int i = 0; i < 4; ++i) workers.emplace_back([&] {
        while (!stop) {
            try { handles.with(current.load(), [](Resource& resource) { ++resource.value; }); }
            catch (const std::logic_error&) { }
            catch (...) { ++failures; }
        }
    });
    int64_t previous = 0;
    for (int iteration = 0; iteration < 2000; ++iteration) {
        const auto handle = handles.create();
        check(handle > previous);
        previous = handle;
        current = handle;
        handles.close(handle);
    }
    stop = true;
    for (auto& worker : workers) worker.join();
    check(failures == 0 && Resource::live == 0);
}

}  // namespace

int main() {
    valuesAndTags();
    limitsAndFailedConstruction();
    operationsAreSerialized();
    concurrentCloseWaitsWithoutBlockingOtherSessions();
    staleHandlesNeverBecomeNewSessions();
    std::cout << "Native handle ownership, limits, tags, exception safety and close races passed\n";
}
