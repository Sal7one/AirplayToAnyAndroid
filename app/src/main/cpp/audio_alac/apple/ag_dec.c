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

/*
	File:		ag_dec.c
	
	Contains:   Adaptive Golomb decode routines.

	Copyright:	(c) 2001-2011 Apple, Inc.
*/

#include "aglib.h"
#include "ALACBitUtilities.h"
#include "ALACAudioTypes.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if __GNUC__ && TARGET_OS_MAC
	#if __POWERPC__
		#include <ppc_intrinsics.h>
	#else
		#include <libkern/OSByteOrder.h>
	#endif
#endif

#define CODE_TO_LONG_MAXBITS	32
#define N_MAX_MEAN_CLAMP		0xffff
#define N_MEAN_CLAMP_VAL		0xffff
#define REPORT_VAL  40

#if __GNUC__
#define ALWAYS_INLINE		__attribute__((always_inline))
#else
#define ALWAYS_INLINE
#endif

/*	And on the subject of the CodeWarrior x86 compiler and inlining, I reworked a lot of this
	to help the compiler out.   In many cases this required manual inlining or a macro.  Sorry
	if it is ugly but the performance gains are well worth it.
	- WSK 5/19/04
*/

void set_standard_ag_params(AGParamRecPtr params, uint32_t fullwidth, uint32_t sectorwidth)
{
	/* Use
		fullwidth = sectorwidth = numOfSamples, for analog 1-dimensional type-short data,
		but use
		fullwidth = full image width, sectorwidth = sector (patch) width
		for such as image (2-dim.) data.
	*/
	set_ag_params( params, MB0, PB0, KB0, fullwidth, sectorwidth, MAX_RUN_DEFAULT );
}

void set_ag_params(AGParamRecPtr params, uint32_t m, uint32_t p, uint32_t k, uint32_t f, uint32_t s, uint32_t maxrun)
{
	params->mb = params->mb0 = m;
	params->pb = p;
	params->kb = k;
	params->wb = (1u<<params->kb)-1;
	params->qb = QB-params->pb; 
	params->fw = f;
	params->sw = s;
	params->maxrun = maxrun;
}

#if PRAGMA_MARK
#pragma mark -
#endif


// note: implementing this with some kind of "count leading zeros" assembly is a big performance win
static inline int32_t lead( int32_t m )
{
	long j;
	unsigned long c = (1ul << 31);

	for(j=0; j < 32; j++)
	{
		if((c & m) != 0)
			break;
		c >>= 1;
	}
	return (j);
}

#define arithmin(a, b) ((a) < (b) ? (a) : (b))

static inline int32_t ALWAYS_INLINE lg3a( int32_t x)
{
    int32_t result;

    x += 3;
    result = lead(x);

    return 31 - result;
}

// FossMirrorTV, 2026-10-16: preserve the Rice/Golomb algorithm but read only
// consumed bits through the checked reader; upstream's 4/5-byte lookahead was unbounded.
static uint32_t read_prefix(BitBuffer * bits)
{
    uint32_t prefix = 0;
    while (prefix < MAX_PREFIX_32 && !bits->error && BitBufferReadOne(bits)) ++prefix;
    return prefix;
}

static uint32_t dyn_get(BitBuffer * bits, uint32_t m, uint32_t k)
{
    uint32_t prefix = read_prefix(bits);
    if (prefix == MAX_PREFIX_16) return BitBufferRead(bits, 16);
    if (k == 0 || k > 16) { bits->error = 1; return 0; }
    uint32_t value = BitBufferRead(bits, (uint8_t)k);
    if (value < 2) {
        BitBufferRewind(bits, 1);
        return prefix * m;
    }
    return prefix * m + value - 1;
}

static uint32_t dyn_get_32bit(BitBuffer * bits, uint32_t m, uint32_t k, uint32_t maxbits)
{
    uint32_t prefix = read_prefix(bits);
    if (prefix == MAX_PREFIX_32) return BitBufferRead(bits, (uint8_t)maxbits);
    if (k == 0 || k > 14) { bits->error = 1; return 0; }
    if (k == 1) return prefix;
    uint32_t value = BitBufferRead(bits, (uint8_t)k);
    if (value < 2) {
        BitBufferRewind(bits, 1);
        return prefix * m;
    }
    return prefix * m + value - 1;
}

int32_t dyn_decomp( AGParamRecPtr params, BitBuffer * bitstream, int32_t * pc, int32_t numSamples, int32_t maxSize, uint32_t * outNumBits )
{

    int32_t			*outPtr = pc;
    uint32_t startPos;
    uint32_t		j, m, k, n, c, mz;
    int32_t			del, zmode;
    uint32_t 	mb;
    uint32_t	pb_local;
    uint32_t	kb_local;
    uint32_t	wb_local;
    int32_t				status;

	RequireAction( (bitstream != nil) && (pc != nil) && (outNumBits != nil), return kALAC_ParamError; );
	*outNumBits = 0;
    RequireAction(params != nil && params->kb >= 1 && params->kb <= 14 &&
                  numSamples > 0 && numSamples <= 4096 && maxSize >= 1 && maxSize <= 17,
                  return kALAC_ParamError;);
    pb_local = params->pb; kb_local = params->kb; wb_local = params->wb;

	startPos = BitBufferGetPosition(bitstream);

    mb = params->mb0;
    zmode = 0;

    c = 0;
	status = ALAC_noErr;

    while (c < numSamples)
    {
		// bail if we've run off the end of the buffer
    	RequireAction( !bitstream->error && BitBufferRemaining(bitstream) > 0, status = kALAC_ParamError; goto Exit; );

        m = (mb)>>QBSHIFT;
        k = lg3a(m);

        k = arithmin(k, kb_local);
        m = (1<<k)-1;
        
		n = dyn_get_32bit(bitstream, m, k, maxSize);
        RequireAction(!bitstream->error, status = kALAC_ParamError; goto Exit;);

        // least significant bit is sign bit
        {
        	uint32_t	ndecode = n + zmode;
            int32_t		multiplier = (- (ndecode&1));

            multiplier |= 1;
            del = ((ndecode+1) >> 1) * (multiplier);
        }

        *outPtr++ = del;

        c++;

        mb = pb_local*(n+zmode) + mb - ((pb_local*mb)>>QBSHIFT);

		// update mean tracking
		if (n > N_MAX_MEAN_CLAMP)
			mb = N_MEAN_CLAMP_VAL;

        zmode = 0;

        if (((mb << MMULSHIFT) < QB) && (c < numSamples))
        {
            zmode = 1;
            k = lead(mb) - BITOFF+((mb+MOFF)>>MDENSHIFT);
            RequireAction(k >= 1 && k <= 16, status = kALAC_ParamError; goto Exit;);
            mz = ((1u<<k)-1) & wb_local;

            n = dyn_get(bitstream, mz, k);

            RequireAction(!bitstream->error && n <= (uint32_t)numSamples - c, status = kALAC_ParamError; goto Exit; );

            for(j=0; j < n; j++)
            {
                *outPtr++ = 0;
                ++c;                    
            }

            if(n >= 65535)
            	zmode = 0;

            mb = 0;
        }
    }

Exit:
	*outNumBits = BitBufferGetPosition(bitstream) - startPos;
	RequireAction(!bitstream->error, status = kALAC_ParamError;);

    return status;
}
