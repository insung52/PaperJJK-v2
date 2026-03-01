package org.justheare.paperjjk.cursed;

import org.justheare.paperjjk.skill.ActiveSkill;

import java.util.function.Consumer;

/**
 * CursedEnergy.distributeOutput() 에서 사용.
 * 충전 중인 소비자(스킬 또는 신체강화 등)가 틱당 요청하는 출력량과
 * 실제 배정된 양을 담는다.
 */
public class ChargingRequest {

    /** 충전량이 확정됐을 때 호출할 콜백 (actualCharged 를 받아 처리) */
    public final Consumer<Double> chargeCallback;
    public final double perTickRequest; // 이 소비자가 틱당 요청하는 출력량
    public double actualCharged;        // distributeOutput() 이 채워주는 실제 배정량

    /** 스킬 충전 요청 (일반 케이스) */
    public ChargingRequest(ActiveSkill skill, double perTickRequest) {
        this.chargeCallback = skill::applyCharge;
        this.perTickRequest = perTickRequest;
        this.actualCharged = 0;
    }

    /** 비-스킬 CE 소비자 (신체강화 등) */
    public ChargingRequest(Consumer<Double> chargeCallback, double perTickRequest) {
        this.chargeCallback = chargeCallback;
        this.perTickRequest = perTickRequest;
        this.actualCharged = 0;
    }
}
