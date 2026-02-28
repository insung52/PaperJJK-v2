package org.justheare.paperjjk.entity;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.justheare.paperjjk.cursed.BlackFlashState;
import org.justheare.paperjjk.cursed.ChargingRequest;
import org.justheare.paperjjk.cursed.CursedEnergy;
import org.justheare.paperjjk.cursed.ReverseOutput;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageResult;
import org.justheare.paperjjk.skill.ActiveSkill;
import org.justheare.paperjjk.status.StatusEffects;
import org.justheare.paperjjk.technique.Technique;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 주술적 존재의 추상 기반. 기존 Jobject 를 대체.
 * JPlayer, JCursedSpirit 이 상속.
 */
public abstract class JEntity {

    // ── 식별 ──────────────────────────────────────────────────────────────

    public final UUID uuid;
    public final Entity entity;

    // ── 주력 ──────────────────────────────────────────────────────────────

    public final CursedEnergy cursedEnergy;

    /** 생득술식. null = 일반인 (주술 없음) */
    @Nullable
    public Technique technique;

    // ── 주력 조작 ─────────────────────────────────────────────────────────

    public final BodyReinforcement bodyReinforcement;

    /** 반전술식. null = 사용 불가 (JCursedSpirit, 반전술식 없는 주술사) */
    @Nullable
    public final ReverseOutput reverseOutput;

    // ── 흑섬 ──────────────────────────────────────────────────────────────

    public final BlackFlashState blackFlash;

    // ── 스킬 ──────────────────────────────────────────────────────────────

    /** 현재 발동 중인 스킬 목록. WorkScheduler 가 별도 관리하지만 참조 보관. */
    protected final List<ActiveSkill> activeSkills = new ArrayList<>();

    // ── 상태이상 ──────────────────────────────────────────────────────────

    public final StatusEffects status;

    // ── 생성자 ────────────────────────────────────────────────────────────

    protected JEntity(Entity entity, double maxCursedEnergy,
                      boolean canReverseOutput, double blackFlashBaseProbability) {
        this.entity = entity;
        this.uuid = entity.getUniqueId();
        this.cursedEnergy = new CursedEnergy(maxCursedEnergy);
        this.bodyReinforcement = new BodyReinforcement(0); // max는 technique 설정 후 갱신
        this.reverseOutput = canReverseOutput ? new ReverseOutput() : null;
        this.blackFlash = new BlackFlashState(blackFlashBaseProbability);
        this.status = new StatusEffects();
    }

    // ── 틱 처리 ───────────────────────────────────────────────────────────

    /**
     * 서버 메인 틱에서 WorkScheduler 를 통해 호출.
     * 주력 회복, 충전 분산, 흑섬 Zone 틱, 상태이상 틱 처리.
     */
    public void onTick() {
        // 주력 자연 회복
        cursedEnergy.regen();

        // 충전 중인 스킬에 출력 분배
        List<ChargingRequest> requests = buildChargingRequests();
        cursedEnergy.distributeOutput(requests, getHealthPercent());
        for (ChargingRequest req : requests) {
            req.skill.applyCharge(req.actualCharged);
        }

        // 신체강화 틱
        bodyReinforcement.onTick();

        // 반전술식 틱
        if (reverseOutput != null) reverseOutput.onTick(this);

        // 흑섬 Zone 틱
        blackFlash.onTick();

        // 상태이상 틱
        status.onTick(this);
    }

    /** 충전 중인 스킬들로부터 ChargingRequest 목록 생성 */
    private List<ChargingRequest> buildChargingRequests() {
        List<ChargingRequest> requests = new ArrayList<>();
        for (ActiveSkill skill : activeSkills) {
            if (skill.isCharging()) {
                requests.add(new ChargingRequest(skill, skill.getPerTickChargeRequest()));
            }
        }
        return requests;
    }

    // ── 데미지 ────────────────────────────────────────────────────────────

    /**
     * 피격 진입점. DamagePipeline.process() 로 위임.
     */
    public DamageResult receiveDamage(DamageInfo info) {
        return org.justheare.paperjjk.damage.DamagePipeline.process(info, this);
    }

    // ── 스킬 관리 ─────────────────────────────────────────────────────────

    public void addActiveSkill(ActiveSkill skill) {
        activeSkills.add(skill);
    }

    public void removeActiveSkill(ActiveSkill skill) {
        activeSkills.remove(skill);
    }

    public List<ActiveSkill> getActiveSkills() {
        return activeSkills;
    }

    // ── 추상 메서드 ───────────────────────────────────────────────────────

    /** 현재 체력 비율 (0.0 ~ 1.0) — 구현체(JPlayer, JCursedSpirit) 에서 제공 */
    public abstract double getHealthPercent();

    /** Bukkit LivingEntity 반환 (데미지 적용 등에 사용) */
    public abstract LivingEntity getLivingEntity();

    // ── 편의 메서드 ───────────────────────────────────────────────────────

    public boolean hasTechnique() { return technique != null; }
    public boolean canReverseOutput() { return reverseOutput != null; }
    public boolean isFullyStunned() { return status.isFullyStunned(); }
    public boolean isTechniqueBlocked() { return status.isTechniqueBlocked(); }
}
