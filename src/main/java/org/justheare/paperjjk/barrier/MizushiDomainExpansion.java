package org.justheare.paperjjk.barrier;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.innate.MizushiInnateTerritory;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.network.PacketIds;

/**
 * 복마어주자(Malevolent Shrine) 영역전개.
 *
 * isOpen=false (일반):
 *   - 결계 재질: BEDROCK
 *   - 반경: 30블록
 *   - 구 블록 점진 생성 후 ACTIVE
 *   - 일반 엔티티 포획은 부모 클래스(DomainExpansion) 에서 처리
 *
 * isOpen=true (결없영):
 *   - 결계 없음, 현실에 전개
 *   - 매 틱 범위 내 LivingEntity에 해(Kai) 필중
 *   - 포획 없이 현실 전개 (DomainExpansion.captureAllEntitiesInRange 에서 isOpen 체크로 제외됨)
 */
public class MizushiDomainExpansion extends DomainExpansion {

    private static final int      RANGE_NORMAL    = 30;
    private static final int      RANGE_OPEN      = 200;
    private static final double   BARRIER_LEVEL   = 10.0;
    private static final Material BARRIER_MAT     = Material.BEDROCK;
    private static final int      BLOCKS_PER_TICK = 200;

    private final DomainBlockBuilder builder;

    private int syncTickCounter = 0;

    public MizushiDomainExpansion(JEntity caster, InnateTerritory territory, boolean open) {
        super(caster, territory, open ? RANGE_OPEN : RANGE_NORMAL, open, BARRIER_LEVEL);
        this.builder = open ? null : new DomainBlockBuilder();
    }

    // ── DomainExpansion 구현 ──────────────────────────────────────────────

    @Override
    protected void onExpanding() {
        Location center = caster.entity.getLocation();

        if (++syncTickCounter % 5 == 0) {
            broadcastDomainVisualSync(center);
        }

        if (isOpen) {
            // 결없영: 블록 건설 없이 즉시 ACTIVE 전환 (첫 틱에만)
            if (syncTickCounter == 1) {
                domainPhase = DomainPhase.ACTIVE;
                // 결없영은 포획하지 않음 (captureAllEntitiesInRange의 isOpen 체크로 처리)
            }
        } else {
            boolean done = builder.buildTick(center, RANGE_NORMAL, BARRIER_MAT, BLOCKS_PER_TICK);
            if (done) {
                domainPhase = DomainPhase.ACTIVE;
                captureAllEntitiesInRange(); // JEntity + 일반 엔티티 모두 포획
                broadcastDomainVisualActive(center);

                center.getWorld().playSound(center, Sound.BLOCK_BEACON_ACTIVATE,
                        SoundCategory.MASTER, 3f, 0.5f);
            }
        }
    }

    @Override
    protected void onDomainActive() {
        innateTerritory.onActiveTick();

        // 결없영 전용: 매 틱 범위 내 LivingEntity에 해(Kai) 필중
        if (isOpen && innateTerritory instanceof MizushiInnateTerritory mit) {
            Location center = caster.entity.getLocation();
            if (center.getWorld() != null) {
                center.getWorld().getNearbyEntities(center, RANGE_OPEN, RANGE_OPEN, RANGE_OPEN)
                        .stream()
                        .filter(e -> e instanceof org.bukkit.entity.LivingEntity
                                && !(e instanceof org.bukkit.entity.Player))
                        .forEach(e -> mit.applySureHitVanilla((org.bukkit.entity.LivingEntity) e));
            }
        }
    }

    @Override
    protected void onClosing() {
        if (isOpen || builder == null) {
            // 결없영: 블록 없으므로 즉시 DONE
            domainPhase = DomainPhase.DONE;
            broadcastDomainVisualEnd(caster.entity.getLocation());
            return;
        }
        boolean done = builder.restoreTick(BLOCKS_PER_TICK);
        if (done) {
            domainPhase = DomainPhase.DONE;
            broadcastDomainVisualEnd(caster.entity.getLocation());
        }
    }

    @Override
    public boolean containsBarrierBlock(Location loc) {
        if (builder == null) return false;
        return builder.isBarrierBlock(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /** BlockBreak 이벤트에서 파손된 결계 블록의 색인 제거 */
    public void removeBarrierBlock(int bx, int by, int bz) {
        if (builder != null) builder.removeBarrierBlock(bx, by, bz);
    }

    // ── 패킷 브로드캐스트 ─────────────────────────────────────────────────

    private void broadcastDomainVisualSync(Location center) {
        if (center.getWorld() == null) return;
        JPacketSender.broadcastDomainVisualStart(center,
                caster.uuid, PacketIds.DomainType.MIZUSHI,
                center, (float) getRange(), isOpen,
                DomainManager.BROADCAST_RANGE);
    }

    private void broadcastDomainVisualActive(Location center) {
        broadcastDomainVisualSync(center);
    }

    private void broadcastDomainVisualEnd(Location center) {
        if (center.getWorld() == null) return;
        JPacketSender.broadcastDomainVisualEnd(center, caster.uuid, DomainManager.BROADCAST_RANGE);
    }
}
