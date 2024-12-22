package com.mcloudtw;
import io.netty.buffer.ByteBuf;

/**
 * 讀取/寫入 Minecraft 用 VarInt 壓縮整數的工具類
 */
public class VarIntUtils {

    /**
     * 讀取 VarInt（Minecraft 用於表徵封包長度、ID 等的壓縮整數）
     *  - 回傳 -1 表示尚未讀完或格式錯誤
     */
    public static int readVarInt(ByteBuf in) {
        int value = 0;
        int position = 0;
        while (true) {
            if (!in.isReadable()) {
                return -1; // Not enough data
            }
            byte b = in.readByte();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                return -1; // VarInt too long or malformed
            }
        }
    }
}