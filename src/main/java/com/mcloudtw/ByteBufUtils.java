package com.mcloudtw;
import io.netty.buffer.ByteBuf;

/**
 * ByteBuf 相關工具方法
 */
public class ByteBufUtils {

    /**
     * 將 ByteBuf 轉成 Hex 字串，方便除錯
     */
    public static String toHexString(ByteBuf buf) {
        StringBuilder sb = new StringBuilder();
        for (int i = buf.readerIndex(); i < buf.writerIndex(); i++) {
            sb.append(String.format("%02X ", buf.getByte(i)));
        }
        return sb.toString().trim();
    }
}