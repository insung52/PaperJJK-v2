package org.justheare.paperjjk.entity;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.justheare.paperjjk.cursed.BlackFlashState;
import org.justheare.paperjjk.cursed.CursedEnergy;
import org.justheare.paperjjk.cursed.ReverseOutput;
import org.justheare.paperjjk.damage.DamageInfo;
import org.justheare.paperjjk.damage.DamageResult;
import org.justheare.paperjjk.skill.ActiveSkill;
import org.justheare.paperjjk.status.StatusEffects;
import org.justheare.paperjjk.technique.Technique;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * DamagePipeline 이 entity.damage() 를 호출할 때 EntityDamageByEntityEvent
     * 가 다시 발생하지 않도록 억제하는 플래그.
     * Pipeline 이 damage() 직전에 true 로 세팅, 이벤트 핸들러가 감지 후 해제.
     */
    public boolean suppressDamageEvent = false;

    /**
     * 환경 데미지 소스별 쿨다운. 키 = EntityDamageEvent.Cause.name().
     * 소스마다 독립적으로 관리되어 서로 간섭하지 않음.
     */
    private final Map<String, Integer> envDamageCooldowns = new HashMap<>();

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

        // 마크 기본 i-frame 완전 제거 — 우리 시스템에서 직접 관리
        if (entity instanceof LivingEntity le) {
            DamageInfo.setnodamagetick(le);
        }
    }

    // ── 틱 처리 ───────────────────────────────────────────────────────────

    /**
     * 서버 메인 틱에서 WorkScheduler 를 통해 호출.
     * 주력 회복, 충전 분산, 흑섬 Zone 틱, 상태이상 틱 처리.
     */
    public void onTick() {
        // 주력 자연 회복
        cursedEnergy.regen();

        // CE → 충전 중인 스킬/신체강화에 분배
        drainAndDistribute();

        // 신체강화 틱
        bodyReinforcement.onTick();

        // 반전술식 틱
        if (reverseOutput != null) reverseOutput.onTick(this);

        // 흑섬 Zone 틱
        blackFlash.onTick(getLivingEntity());

        // 상태이상 틱
        status.onTick(this);

        // 환경 데미지 쿨다운 틱다운
        if (!envDamageCooldowns.isEmpty()) {
            org.justheare.paperjjk.PaperJJK.logDamage("[DBG-TICK] envCooldowns before=" + envDamageCooldowns);
        }
        envDamageCooldowns.entrySet().removeIf(e -> {
            e.setValue(e.getValue() - 1);
            return e.getValue() <= 0;
        });
        if (!envDamageCooldowns.isEmpty()) {
            org.justheare.paperjjk.PaperJJK.logDamage("[DBG-TICK] envCooldowns after=" + envDamageCooldowns);
        }
    }

    /**
     * maxOutput 을 충전 중인 스킬들에게 가중치 비례 분배.
     * 각 스킬이 흡수한 CE 합산 → cursedEnergy.drain() 으로 풀 차감 (에너지 보존).
     */
    private void drainAndDistribute() {
        double totalWeight = 0;
        for (ActiveSkill s : activeSkills) {
            if (s.isCharging()) totalWeight += s.getChargeWeight();
        }
        totalWeight += getAdditionalChargeWeight();
        if (totalWeight <= 0) return;

        double ceBefore  = cursedEnergy.getCurrent();
        double maxOutput = cursedEnergy.getMaxOutput(1.0);
        double perUnit   = maxOutput / totalWeight;
        double totalDrained = 0;

        for (ActiveSkill s : activeSkills) {
            if (!s.isCharging()) continue;
            double share  = perUnit * s.getChargeWeight();
            double actual = Math.min(share, s.getChargeBufferSpace());
            s.addToBuffer(actual);
            totalDrained += actual;
        }

        totalDrained += distributeAdditional(perUnit);
        cursedEnergy.drain(totalDrained);

        // [CE-LOG] 충전 중일 때만 출력
        if (totalDrained > 0) {
            //org.justheare.paperjjk.PaperJJK.log(String.format(
            //    "[CE] CE %.1f → %.1f (소모 %.1f | maxOut=%.1f | weight=%.1f | perUnit=%.1f)",
            //    ceBefore, cursedEnergy.getCurrent(), totalDrained,
            //    maxOutput, totalWeight, perUnit));
        }
    }

    /** 추가 CE 소비자(신체강화 등)의 가중치 합. JPlayer 에서 override. */
    protected double getAdditionalChargeWeight() { return 0; }

    /**
     * 추가 소비자에게 perUnit 을 분배하고 실제 소모량 반환.
     * JPlayer 에서 override.
     */
    protected double distributeAdditional(double perUnit) { return 0; }

    // ── 데미지 ────────────────────────────────────────────────────────────

    /**
     * 피격 진입점. DamagePipeline.process() 로 위임.
     */
    public DamageResult receiveDamage(DamageInfo info) {
        DamageResult result = org.justheare.paperjjk.damage.DamagePipeline.process(info, this);
        // info.isBlackFlash 은 onAttack() 내부에서 세팅되므로 process() 반환 후 체크
        if (info.isBlackFlash && info.attacker != null
                && info.attacker.getLivingEntity() != null) {
            org.justheare.paperjjk.effect.BlackFlashEffect.trigger(
                    info.attacker.getLivingEntity(), this.getLivingEntity(),
                    info.blackFlashBonus);
        }
        return result;
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

    /**
     * 환경 데미지 소스 쿨다운 체크 + 세팅.
     * 쿨다운 중이면 false 반환 (데미지 무시). 아니면 쿨다운 세팅 후 true 반환.
     */
    public boolean tryEnvDamage(String cause, int cooldownTicks) {
        int remaining = envDamageCooldowns.getOrDefault(cause, 0);
        org.justheare.paperjjk.PaperJJK.logDamage("[DBG-CD] tryEnvDamage cause=" + cause
                + " remaining=" + remaining + " mapSize=" + envDamageCooldowns.size());
        if (envDamageCooldowns.containsKey(cause)) return false;
        envDamageCooldowns.put(cause, cooldownTicks);
        return true;
    }

    public boolean hasTechnique() { return technique != null; }
    public boolean canReverseOutput() { return reverseOutput != null; }
    public boolean isFullyStunned() { return status.isFullyStunned(); }
    public boolean isTechniqueBlocked() { return status.isTechniqueBlocked(); }
}
