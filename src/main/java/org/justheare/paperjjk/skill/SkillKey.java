package org.justheare.paperjjk.skill;

import javax.annotation.Nullable;

public enum SkillKey {
    X, C, V, B;

    /**
     * 패킷 slot 바이트 → SkillKey 변환.
     * 0x01=X, 0x02=C, 0x03=V, 0x04=B
     */
    @Nullable
    public static SkillKey fromSlot(byte slot) {
        return switch (slot) {
            case 0x01 -> X;
            case 0x02 -> C;
            case 0x03 -> V;
            case 0x04 -> B;
            default   -> null;
        };
    }

    /** SkillKey → 패킷 slot 바이트 변환 (1~4) */
    public byte toSlot() {
        return (byte)(ordinal() + 1);
    }
}
