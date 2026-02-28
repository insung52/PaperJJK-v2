package org.justheare.paperjjk.cursed;

import org.justheare.paperjjk.skill.ActiveSkill;

/**
 * CursedEnergy.distributeOutput() 에서 사용.
 * 충전 중인 스킬이 틱당 요청하는 출력량과, 실제 배정된 양을 담는다.
 */
public class ChargingRequest {

    public final ActiveSkill skill;
    public final double perTickRequest; // 이 스킬이 틱당 요청하는 출력량
    public double actualCharged;        // distributeOutput() 이 채워주는 실제 배정량

    public ChargingRequest(ActiveSkill skill, double perTickRequest) {
        this.skill = skill;
        this.perTickRequest = perTickRequest;
        this.actualCharged = 0;
    }
}
