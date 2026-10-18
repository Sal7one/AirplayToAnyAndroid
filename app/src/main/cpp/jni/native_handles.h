// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <utility>

namespace fossmirror::jni {

// IDs are never pointers or reused. Tags keep handles from separate libraries distinct.
template <typename Resource, uint8_t Tag>
class NativeHandles {
    static_assert(Tag != 0);
    struct Entry {
        std::mutex mutex;
        std::unique_ptr<Resource> resource;

        template <typename... Args>
        explicit Entry(Args&&... args)
            : resource(std::make_unique<Resource>(std::forward<Args>(args)...)) {}
    };

public:
    using Handle = int64_t;
    static constexpr size_t kMaxInstances = 64;

    explicit NativeHandles(size_t capacity = kMaxInstances) : capacity_(capacity) {
        if (capacity == 0 || capacity > kMaxInstances)
            throw std::invalid_argument("Invalid native session capacity");
    }

    template <typename... Args>
    Handle create(Args&&... args) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (entries_.size() >= capacity_) throw std::logic_error("Native session limit reached");
        constexpr auto maxSequence = static_cast<uint64_t>(std::numeric_limits<Handle>::max()) >> 8;
        if (sequence_ == maxSequence) throw std::logic_error("Native handle space exhausted");
        const auto handle = static_cast<Handle>((++sequence_ << 8) | Tag);
        entries_.emplace(handle, std::make_shared<Entry>(std::forward<Args>(args)...));
        return handle;
    }

    template <typename Operation>
    decltype(auto) with(Handle handle, Operation&& operation) {
        auto entry = lookup(handle);
        std::lock_guard<std::mutex> lock(entry->mutex);
        if (!entry->resource) throw std::logic_error("Native session is closed");
        return std::forward<Operation>(operation)(*entry->resource);
    }

    void close(Handle handle) {
        std::shared_ptr<Entry> entry;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto found = entries_.find(handle);
            if (found == entries_.end()) return;
            entry = found->second;
        }
        // Do not hold the registry lock while waiting for an in-flight operation.
        {
            std::lock_guard<std::mutex> lock(entry->mutex);
            entry->resource.reset();
        }
        // Concurrent closers still find the entry and wait for resource destruction.
        std::lock_guard<std::mutex> lock(mutex_);
        entries_.erase(handle);
    }

private:
    std::shared_ptr<Entry> lookup(Handle handle) {
        if (handle <= 0 || (static_cast<uint64_t>(handle) & 0xff) != Tag)
            throw std::logic_error("Invalid native session handle");
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = entries_.find(handle);
        if (found == entries_.end()) throw std::logic_error("Native session is closed");
        return found->second;
    }

    std::mutex mutex_;
    std::unordered_map<Handle, std::shared_ptr<Entry>> entries_;
    uint64_t sequence_ = 0;
    const size_t capacity_;
};

}  // namespace fossmirror::jni
