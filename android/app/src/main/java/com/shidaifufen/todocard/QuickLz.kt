package com.shidaifufen.todocard

/**
 * QuickLZ 1.5 level-3 编码器 —— 与桌面版 quicklz_payload.py 逐字节一致。
 *
 * 固件 v157+ 按 2048 字节一块接收，块头：
 *   header[0] = 0x7C(存储) / 0x7D(压缩) | 0x02(9 字节头)
 *   3 字节头：header[1]=总长 header[2]=原始长
 *   9 字节头：header[1..4]=总长 LE32 header[5..8]=原始长 LE32
 */
object QuickLz {

    const val BLOCK_SIZE = 2048
    private const val HASH_VALUES = 4096
    private const val POINTERS = 16

    private fun hash(fetch: Int): Int = ((fetch ushr 12) xor fetch) and (HASH_VALUES - 1)

    /** 单块编码（1..2048 字节） */
    fun encodeBlock(source: ByteArray): ByteArray {
        require(source.isNotEmpty() && source.size <= BLOCK_SIZE) { "block 1..2048" }
        val core = ByteArray(source.size + 400)
        val hashOffsets = IntArray(HASH_VALUES * POINTERS) { -1 }
        val hashCounters = IntArray(HASH_VALUES)

        val lastByte = source.size - 1
        val lastMatchStart = lastByte - 6 - 4
        var srcOff = 0
        var ctrlOff = 0
        var tgtOff = 4
        var ctrl = 0x80000000.toInt()

        fun writeLe(offset: Int, value: Int, count: Int) {
            for (i in 0 until count) {
                core[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
            }
        }

        fun readLe(offset: Int, count: Int): Int {
            var v = 0
            for (i in 0 until count) if (offset + i < source.size) v = v or ((source[offset + i].toInt() and 0xFF) shl (i * 8))
            return v
        }

        fun flushCtrl() {
            val value = ((ctrl ushr 1) or 0x80000000.toInt())
            writeLe(ctrlOff, value, 4)
        }

        while (srcOff <= lastMatchStart) {
            if (ctrl and 1 != 0) {
                flushCtrl()
                ctrlOff = tgtOff
                tgtOff += 4
                ctrl = 0x80000000.toInt()
            }

            val fetch = readLe(srcOff, 3)
            val hv = hash(fetch)
            val maxLen = minOf(258, source.size - 4 - srcOff)
            val hashBase = hv * POINTERS
            val hashCount = hashCounters[hv]
            var prevOffset = -1
            var matchLen = 0
            val candidates = minOf(hashCount, POINTERS)
            for (ci in 0 until candidates) {
                val cand = hashOffsets[hashBase + ci]
                val dist = srcOff - cand
                if (cand < 0 || dist < 3 || dist > 131071) continue
                if (source[cand] != source[srcOff] ||
                    source[cand + 1] != source[srcOff + 1] ||
                    source[cand + 2] != source[srcOff + 2]) continue
                var len = 3
                while (len < maxLen && source[cand + len] == source[srcOff + len]) len++
                if (len > matchLen) { matchLen = len; prevOffset = cand }
            }

            hashOffsets[hashBase + (hashCount and (POINTERS - 1))] = srcOff
            hashCounters[hv] = (hashCount + 1) and 0xFFFF

            if (matchLen >= 3) {
                val matchOffset = srcOff - prevOffset
                ctrl = (ctrl ushr 1) or 0x80000000.toInt()
                for (i in 1 until matchLen) {
                    if (srcOff + i <= lastMatchStart) {
                        val inner = srcOff + i
                        val ih = hash(readLe(inner, 3))
                        val ic = hashCounters[ih]
                        hashOffsets[ih * POINTERS + (ic and (POINTERS - 1))] = inner
                        hashCounters[ih] = (ic + 1) and 0xFFFF
                    }
                }
                srcOff += matchLen
                when {
                    matchLen == 3 && matchOffset <= 63 -> {
                        core[tgtOff] = (matchOffset shl 2).toByte(); tgtOff += 1
                    }
                    matchLen == 3 && matchOffset <= 16383 -> {
                        writeLe(tgtOff, (matchOffset shl 2) or 1, 2); tgtOff += 2
                    }
                    matchLen <= 18 && matchOffset <= 1023 -> {
                        writeLe(tgtOff, ((matchLen - 3) shl 2) or (matchOffset shl 6) or 2, 2); tgtOff += 2
                    }
                    matchLen <= 33 -> {
                        writeLe(tgtOff, ((matchLen - 2) shl 2) or (matchOffset shl 7) or 3, 3); tgtOff += 3
                    }
                    else -> {
                        writeLe(tgtOff, ((matchLen - 3) shl 7) or (matchOffset shl 15) or 3, 4); tgtOff += 4
                    }
                }
            } else {
                core[tgtOff] = source[srcOff]; tgtOff += 1; srcOff += 1
                ctrl = ctrl ushr 1
            }
        }

        while (srcOff <= lastByte) {
            if (ctrl and 1 != 0) {
                flushCtrl()
                ctrlOff = tgtOff
                tgtOff += 4
                ctrl = 0x80000000.toInt()
            }
            core[tgtOff] = source[srcOff]; tgtOff += 1; srcOff += 1
            ctrl = ctrl ushr 1
        }

        while (ctrl and 1 == 0) ctrl = ctrl ushr 1
        flushCtrl()

        val coreLength = maxOf(tgtOff, 9)
        val headerSize = if (source.size < 216) 3 else 9
        val compressedSize = headerSize + coreLength
        if (compressedSize >= headerSize + source.size) {
            val stored = ByteArray(headerSize + source.size)
            stored[0] = (0x7C or (if (headerSize == 9) 0x02 else 0)).toByte()
            if (headerSize == 3) {
                stored[1] = stored.size.toByte()
                stored[2] = source.size.toByte()
            } else {
                for (i in 0 until 4) stored[1 + i] = ((stored.size ushr (i * 8)) and 0xFF).toByte()
                for (i in 0 until 4) stored[5 + i] = ((source.size ushr (i * 8)) and 0xFF).toByte()
            }
            source.copyInto(stored, headerSize)
            return stored
        }

        val out = ByteArray(compressedSize)
        out[0] = (0x7D or (if (headerSize == 9) 0x02 else 0)).toByte()
        if (headerSize == 3) {
            out[1] = compressedSize.toByte()
            out[2] = source.size.toByte()
        } else {
            for (i in 0 until 4) out[1 + i] = ((compressedSize ushr (i * 8)) and 0xFF).toByte()
            for (i in 0 until 4) out[5 + i] = ((source.size ushr (i * 8)) and 0xFF).toByte()
        }
        core.copyInto(out, headerSize, 0, coreLength)
        return out
    }

    /** 整张卡：4 字节零前缀 + 每 2048 字节一块 */
    fun buildPayload(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(32 * 1024)
        out.write(byteArrayOf(0, 0, 0, 0))
        var off = 0
        while (off < raw.size) {
            val end = minOf(off + BLOCK_SIZE, raw.size)
            out.write(encodeBlock(raw.copyOfRange(off, end)))
            off = end
        }
        return out.toByteArray()
    }
}
