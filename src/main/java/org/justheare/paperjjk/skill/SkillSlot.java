package org.justheare.paperjjk.skill;

import javax.annotation.Nullable;

/**
 * 키 하나에 매핑된 스킬 슬롯.
 */
public class SkillSlot {

    public final String skillId;        // "infinity_ao", "mizushi_kai" 등
    public final boolean chargeable;    // 길게 누르면 충전, 떼면 발동
    public final boolean rechargeable;  // 이미 발동된 스킬 재충전 가능

    /** 현재 이 키에서 실행 중인 스킬 인스턴스. null = 미실행 */
    @Nullable
    public ActiveSkill runningSkill;

    public SkillSlot(String skillId, boolean chargeable, boolean rechargeable) {
        this.skillId = skillId;
        this.chargeable = chargeable;
        this.rechargeable = rechargeable;
    }

    public boolean isRunning() {
        return runningSkill != null && !runningSkill.isDone();
    }
}
