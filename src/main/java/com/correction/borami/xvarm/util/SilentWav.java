package com.correction.borami.xvarm.util;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 무음 WAV 바이트 생성기 — DUMMY 추출이 <b>진짜 오디오처럼 보이는</b> 파일을 만들게 한다.
 *
 * <p>16kHz · 모노 · 16bit PCM. 하류의 매직 넘버 판별({@code RIFF....WAVE})과
 * 파일 크기 안정성 검사가 실제로 동작하는지 확인할 수 있어야 한다.</p>
 */
public final class SilentWav {

    private static final int SAMPLE_RATE = 16_000;
    private static final short CHANNELS = 1;
    private static final short BITS = 16;

    private SilentWav() {
    }

    public static byte[] of(int seconds) {
        int dataSize = SAMPLE_RATE * CHANNELS * (BITS / 8) * Math.max(seconds, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);

        out.writeBytes("RIFF".getBytes());
        out.writeBytes(le32(36 + dataSize));
        out.writeBytes("WAVE".getBytes());

        out.writeBytes("fmt ".getBytes());
        out.writeBytes(le32(16));
        out.writeBytes(le16((short) 1));
        out.writeBytes(le16(CHANNELS));
        out.writeBytes(le32(SAMPLE_RATE));
        out.writeBytes(le32(SAMPLE_RATE * CHANNELS * (BITS / 8)));
        out.writeBytes(le16((short) (CHANNELS * (BITS / 8))));
        out.writeBytes(le16(BITS));

        out.writeBytes("data".getBytes());
        out.writeBytes(le32(dataSize));
        out.writeBytes(new byte[dataSize]);

        return out.toByteArray();
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static byte[] le16(short v) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array();
    }
}
