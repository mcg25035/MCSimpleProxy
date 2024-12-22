package com.mcloudtw;
import io.netty.buffer.ByteBuf;

public class ByteBufUtils {
    public static String toHexString(ByteBuf buf) {
        StringBuilder sb = new StringBuilder();
        for (int i = buf.readerIndex(); i < buf.writerIndex(); i++) {
            sb.append(String.format("%02X ", buf.getByte(i)));
        }
        return sb.toString().trim();
    }
}