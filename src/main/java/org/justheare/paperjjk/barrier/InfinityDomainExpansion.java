package org.justheare.paperjjk.barrier;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.justheare.paperjjk.entity.JEntity;
import org.justheare.paperjjk.innate.InnateTerritory;
import org.justheare.paperjjk.network.JPacketSender;
import org.justheare.paperjjk.network.PacketIds;

/**
 * 무량공처(Unlimited Void) 영역전개.
 *
 * - 결계 재질: BARRIER
 * - 반경: 30블록
 * - EXPANDING: 200블록/틱 점진 구 생성 → 완성 시 ACTIVE
 * - ACTIVE: InfinityInnateTerritory 에 필중 위임
 * - CLOSING: 200블록/틱 점진 복원 → 완성 시 DONE
 */
public class InfinityDomainExpansion extends DomainExpansion {

    private static final int      RANGE         = 30;
    private static final double   BARRIER_LEVEL = 9.0;
    private static final Material BARRIER_MAT   = Material.BARRIER;
    private static final int      BLOCKS_PER_TICK = 200;

    private final DomainBlockBuilder builder = new DomainBlockBuilder();

    /** 매 틱 DOMAIN_VISUAL SYNC 주기 (5틱마다) */
    private int syncTickCounter = 0;

    public InfinityDomainExpansion(JEntity caster, InnateTerritory territory) {
        super(caster, territory, RANGE, false, BARRIER_LEVEL);
    }

    // ── DomainExpansion 구현 ──────────────────────────────────────────────

    @Override
    protected void onExpanding() {
        Location center = caster.entity.getLocation();
        boolean done = builder.buildTick(center, RANGE, BARRIER_MAT, BLOCKS_PER_TICK);

        // 5틱마다 SYNC 패킷
        if (++syncTickCounter % 5 == 0) {
            broadcastDomainVisualSync(center);
        }

        if (done) {
            domainPhase = DomainPhase.ACTIVE;
            captureAllEntitiesInRange();
            broadcastDomainVisualActive(center);

            // 생득 영역 완성 음향 효과
            center.getWorld().playSound(center, Sound.BLOCK_BEACON_ACTIVATE,
                    SoundCategory.MASTER, 3f, 0.7f);
        }
    }

    @Override
    protected void onDomainActive() {
        innateTerritory.onActiveTick();
    }

    @Override
    protected void onClosing() {
        boolean done = builder.restoreTick(BLOCKS_PER_TICK);
        if (done) {
            domainPhase = DomainPhase.DONE;
            broadcastDomainVisualEnd(caster.entity.getLocation());
        }
    }

    @Override
    public boolean containsBarrierBlock(Location loc) {
        return builder.isBarrierBlock(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    // ── 결계 블록 파손 ────────────────────────────────────────────────────

    @Override
    public void onBarrierDamaged(double damage) {
        super.onBarrierDamaged(damage);
        // 파손된 블록 색인 제거
        // (실제 블록 좌표는 JEvent.onBlockBreak 에서 전달)
    }

    /** BlockBreak 이벤트에서 파손된 결계 블록의 색인 제거 */
    public void removeBarrierBlock(int bx, int by, int bz) {
        builder.removeBarrierBlock(bx, by, bz);
    }

    // ── 패킷 브로드캐스트 ─────────────────────────────────────────────────

    private void broadcastDomainVisualSync(Location center) {
        if (center.getWorld() == null) return;
        JPacketSender.broadcastDomainVisualStart(center,
                caster.uuid, PacketIds.DomainType.INFINITY,
                center, (float) RANGE, false,
                DomainManager.BROADCAST_RANGE);
    }

    private void broadcastDomainVisualActive(Location center) {
        // 완성 시 재브로드캐스트 (클라이언트에서 ACTIVE 상태 인식)
        broadcastDomainVisualSync(center);
    }

    private void broadcastDomainVisualEnd(Location center) {
        if (center.getWorld() == null) return;
        JPacketSender.broadcastDomainVisualEnd(center, caster.uuid, DomainManager.BROADCAST_RANGE);
    }
}
