/*
 * Copyright (c) 2011 Apple Inc. All rights reserved.
 *
 * @APPLE_APACHE_LICENSE_HEADER_START@
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * @APPLE_APACHE_LICENSE_HEADER_END@
 */

/*=============================================================================
    File:		ALACBitUtilities.c

	$NoKeywords: $
=============================================================================*/

#include <stdio.h>
#include "ALACBitUtilities.h"

// FossMirrorTV, 2026-10-16: checked reads/peeks/advances, no padded lookahead.
uint32_t BitBufferRemaining(const BitBuffer * bits)
{
    if (bits->error || bits->cur > bits->end || bits->bitIndex > 7) return 0;
    return (uint32_t)(bits->end - bits->cur) * 8 - bits->bitIndex;
}

void BitBufferInit(BitBuffer * bits, uint8_t * buffer, uint32_t byteSize)
{
    bits->cur = buffer;
    bits->end = buffer + byteSize;
    bits->bitIndex = 0;
    bits->byteSize = byteSize;
    bits->error = 0;
}

uint32_t BitBufferRead(BitBuffer * bits, uint8_t numBits)
{
    uint32_t result = 0;
    if (numBits > 32 || numBits > BitBufferRemaining(bits)) {
        bits->error = 1;
        return 0;
    }
    while (numBits != 0) {
        uint32_t take = MIN(numBits, 8 - bits->bitIndex);
        uint32_t value = (bits->cur[0] >> (8 - bits->bitIndex - take)) & ((1u << take) - 1);
        result = (result << take) | value;
        numBits -= take;
        bits->bitIndex += take;
        bits->cur += bits->bitIndex >> 3;
        bits->bitIndex &= 7;
    }
    return result;
}

uint8_t BitBufferReadSmall(BitBuffer * bits, uint8_t numBits)
{
    if (numBits > 8) { bits->error = 1; return 0; }
    return (uint8_t)BitBufferRead(bits, numBits);
}

uint8_t BitBufferReadOne(BitBuffer * bits) { return (uint8_t)BitBufferRead(bits, 1); }

uint32_t BitBufferPeek(BitBuffer * bits, uint8_t numBits)
{
    BitBuffer copy = *bits;
    uint32_t result = BitBufferRead(&copy, numBits);
    bits->error |= copy.error;
    return result;
}

uint32_t BitBufferPeekOne(BitBuffer * bits) { return BitBufferPeek(bits, 1); }

uint32_t BitBufferUnpackBERSize(BitBuffer * bits)
{
    uint32_t size = 0;
    for (uint32_t i = 0; i < 5; ++i) {
        uint8_t value = BitBufferReadSmall(bits, 8);
        if (bits->error || size > (UINT32_MAX >> 7)) { bits->error = 1; return 0; }
        size = (size << 7) | (value & 0x7f);
        if ((value & 0x80) == 0) return size;
    }
    bits->error = 1;
    return 0;
}

uint32_t BitBufferGetPosition(BitBuffer * bits)
{
    return (uint32_t)(bits->cur - (bits->end - bits->byteSize)) * 8 + bits->bitIndex;
}

void BitBufferAdvance(BitBuffer * bits, uint32_t numBits)
{
    if (numBits > BitBufferRemaining(bits)) { bits->error = 1; return; }
    bits->cur += (bits->bitIndex + numBits) >> 3;
    bits->bitIndex = (bits->bitIndex + numBits) & 7;
}

void BitBufferRewind(BitBuffer * bits, uint32_t numBits)
{
    uint32_t position = BitBufferGetPosition(bits);
    if (bits->error || numBits > position) { bits->error = 1; return; }
    position -= numBits;
    bits->cur = bits->end - bits->byteSize + (position >> 3);
    bits->bitIndex = position & 7;
}

void BitBufferWrite(BitBuffer * bits, uint32_t value, uint32_t numBits)
{
    if (numBits > 32 || numBits > BitBufferRemaining(bits)) { bits->error = 1; return; }
    while (numBits != 0) {
        uint32_t take = MIN(numBits, 8 - bits->bitIndex);
        uint32_t shift = 8 - bits->bitIndex - take;
        uint32_t mask = ((1u << take) - 1) << shift;
        bits->cur[0] = (uint8_t)((bits->cur[0] & ~mask) | (((value >> (numBits - take)) << shift) & mask));
        BitBufferAdvance(bits, take);
        numBits -= take;
    }
}

void BitBufferByteAlign(BitBuffer * bits, int32_t addZeros)
{
    if (bits->bitIndex != 0) {
        if (addZeros) BitBufferWrite(bits, 0, 8 - bits->bitIndex);
        else BitBufferAdvance(bits, 8 - bits->bitIndex);
    }
}

void BitBufferReset(BitBuffer * bits)
{
    bits->cur = bits->end - bits->byteSize;
    bits->bitIndex = 0;
    bits->error = 0;
}
