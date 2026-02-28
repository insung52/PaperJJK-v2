package org.justheare.paperjjk.entity;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.justheare.paperjjk.barrier.DomainExpansion;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.skill.SkillKeyMap;
import org.justheare.paperjjk.status.StatusEffectType;
import org.justheare.paperjjk.status.TimedStatusEffect;
import org.justheare.paperjjk.technique.Technique;

import javax.annotation.Nullable;

/**
 * 플레이어 주술사. JEntity 구현체.
 * 기존 Jplayer 를 대체.
 */
public class JPlayer extends JEntity {

    public final Player player;

    /** 키 → 스킬 매핑 */
    public final SkillKeyMap skillKeyMap;

    /** CE_UPDATE 전송 주기 카운터 (20틱 = 1초마다 전송) */
    private int ceSyncTick = 0;

    /** 생득 영역 (jjk id build 로 사전 설정) */
    @Nullable
    public InnateTerritory innateTerritory;

    /** 현재 전개 중인 영역전개 */
    @Nullable
    public DomainExpansion activeDomain;

    // ── 생성자 ────────────────────────────────────────────────────────────

    public JPlayer(Player player, double maxCursedEnergy, boolean canReverseOutput) {
        super(player, maxCursedEnergy, canReverseOutput, 0.01);
        this.player = player;
        this.skillKeyMap = new SkillKeyMap(this);
    }

    // ── 술식 설정 ─────────────────────────────────────────────────────────

    /**
     * 생득술식 부여 및 초기화.
     * 기존 Jobject.setvalues() 대체.
     */
    public void setTechnique(Technique technique) {
        if (this.technique != null) return; // 이미 있으면 무시

        this.technique = technique;

        // 키 배치 초기화
        skillKeyMap.initializeForTechnique(technique);

        // 최대 체력 갱신
        updateMaxHealth();

        // 신체강화 최대값 갱신 (최대 방출량에 비례)
        syncBodyReinMax();

        player.sendMessage(technique.getDisplayName());
    }

    /**
     * 기존 술식을 무시하고 강제로 새 술식 부여.
     * /jjk basic 커맨드에서 사용.
     */
    public void forceTechnique(Technique technique) {
        this.technique = technique;
        skillKeyMap.initializeForTechnique(technique);
        updateMaxHealth();
        syncBodyReinMax();
        player.sendMessage(technique.getDisplayName());
    }

    // ── 영역전개 ──────────────────────────────────────────────────────────

    public boolean expandDomain() {
        if (technique == null || isTechniqueBlocked()) return false;
        if (innateTerritory == null || !innateTerritory.isReady()) {
            player.sendMessage("생득 영역이 설정되지 않았습니다. (jjk id build)");
            return false;
        }
        if (cursedEnergy.getCurrent() < 50000) {
            player.sendMessage("주력이 부족합니다.");
            return false;
        }
        activeDomain = technique.createDomain();
        return activeDomain != null;
    }

    public void collapseDomain() {
        if (activeDomain == null) return;
        activeDomain.collapse();
        activeDomain = null;

        // 영역전개 후 술식 타버림
        status.add(new TimedStatusEffect(StatusEffectType.BURNED_TECHNIQUE, 20 * 30));
    }

    // ── 틱 처리 ───────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        super.onTick();

        // CE_UPDATE 주기 전송 (20틱 = 1초)
        if (++ceSyncTick >= 20) {
            ceSyncTick = 0;
            JPacketSender.sendCEUpdate(player, this);
        }

        // 반전술식 — isSneaking() 체크 (Player API 필요)
        if (reverseOutput != null && reverseOutput.isActive()) {
            if (!player.isSneaking()) {
                reverseOutput.stop();
            } else {
                applyReverseCurse();
            }
        }

        // 영역전개 틱
        if (activeDomain != null) {
            activeDomain.onTick();
            if (activeDomain.getDomainPhase() == DomainExpansion.DomainPhase.CLOSING) {
                collapseDomain();
            }
        }
    }

    /** 반전술식 주력 소모 → 체력 회복 */
    private void applyReverseCurse() {
        if (reverseOutput == null) return;
        double outputAvail = cursedEnergy.getMaxOutput(getHealthPercent());
        if (!cursedEnergy.consume(outputAvail * 0.1)) return;

        double healAmount = outputAvail * 0.1 * reverseOutput.getHealEfficiency();
        double newHealth = Math.min(player.getMaxHealth(),
                player.getHealth() + healAmount);
        player.setHealth(newHealth);
    }

    // ── JEntity 추상 메서드 구현 ──────────────────────────────────────────

    @Override
    public double getHealthPercent() {
        return player.getHealth() / player.getMaxHealth();
    }

    @Override
    public LivingEntity getLivingEntity() {
        return player;
    }

    // ── 편의 메서드 ───────────────────────────────────────────────────────

    /**
     * 최대 체력 = 20 + log(maxCursedEnergy) 비례.
     * 기존 jbasic() 대체.
     */
    public void updateMaxHealth() {
        double max = cursedEnergy.getMax();
        if (max <= 5) return; // 일반인/피지컬 기프티드는 기본 체력
        double newMax = 20 + Math.pow(max - 5, 0.3);
        player.setMaxHealth(newMax);
        player.setHealth(Math.min(player.getHealth(), newMax));
    }

    /** 신체강화 최대값 = 현재 최대 방출량에 비례 */
    public void syncBodyReinMax() {
        double maxOutput = cursedEnergy.getMaxOutput(1.0);
        bodyReinforcement.setMax(maxOutput);
    }
}
