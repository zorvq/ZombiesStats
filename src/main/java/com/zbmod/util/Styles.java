package com.zbmod.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * 把旧式 § 颜色代码字符串转换为带样式的 Component。
 *
 * MC 26.1.2 移除了渲染层的 § 解析（Font.isFormattingCode 已被删除，
 * jar 中也不存在任何 legacy 解析类），因此所有含 § 的文本必须在此显式转换，
 * 否则会显示成字面 "§" 乱码。
 */
public final class Styles {

    private Styles() {
    }

    /** 解析 §0-§f / §k-o / §r 并生成等价 Component */
    public static Component styled(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        MutableComponent result = Component.empty();
        StringBuilder plain = new StringBuilder();
        Style style = Style.EMPTY;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
                if (f != null) {
                    if (plain.length() > 0) {
                        result.append(Component.literal(plain.toString()).withStyle(style));
                        plain.setLength(0);
                    }
                    style = style.applyLegacyFormat(f);
                    i++;
                    continue;
                }
            }
            plain.append(c);
        }
        if (plain.length() > 0) {
            result.append(Component.literal(plain.toString()).withStyle(style));
        }
        return result;
    }
}
