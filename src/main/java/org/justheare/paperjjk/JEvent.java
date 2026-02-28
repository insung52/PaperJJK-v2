package org.justheare.paperjjk;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.justheare.paperjjk.entity.JPlayer;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.technique.Technique;
import org.justheare.paperjjk.technique.TechniqueFactory;

import java.util.UUID;

/**
 * 플레이어 접속/퇴장 이벤트 처리.
 * JPlayer 생성 및 JEntityManager 등록/해제.
 */
public class JEvent implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 이미 등록된 경우 (드문 edge case — 강제 리로그 등)
        if (JEntityManager.instance.get(uuid) != null) {
            PaperJJK.log("[JEvent] Already registered: " + player.getName() + ", skipping.");
            return;
        }

        // 저장된 데이터 가져오기 (없으면 null = 신규)
        JData.PlayerSaveData saved = JData.consumePending(uuid);

        JPlayer jp;
        if (saved != null && !saved.techniqueName.isEmpty()) {
            // 기존 플레이어 — 저장 데이터 복원
            jp = new JPlayer(player, saved.maxCE, saved.canReverseOutput);
            jp.cursedEnergy.setCurrent(saved.currentCE);
            jp.blackFlash.setLifeTimeCount(saved.blackFlashLifeTimeCount);

            // 술식 복원
            Technique technique = TechniqueFactory.create(saved.techniqueName, jp);
            if (technique != null) {
                jp.setTechnique(technique);
                // Mahoraga 적응 데이터 복원
                if (technique instanceof org.justheare.paperjjk.technique.MahoragaTechnique mt
                        && saved.mahoragaAdaptMap != null) {
                    mt.loadAdaptationMap(saved.mahoragaAdaptMap);
                }
                // 비행 허용 (주술사 등급)
                player.setAllowFlight(saved.maxCE > 1000);
            }

            event.setJoinMessage("§6sorcerer joined");
            PaperJJK.log("[JEvent] Restored: " + player.getName()
                    + " tech=" + saved.techniqueName
                    + " CE=" + (int) saved.currentCE + "/" + (int) saved.maxCE);
        } else {
            // 신규 플레이어 — 일반인으로 생성
            jp = new JPlayer(player, 200.0, false);
            event.setJoinMessage("§anew sorcerer joined");
            PaperJJK.log("[JEvent] New player: " + player.getName());
        }

        JEntityManager.instance.register(jp);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        JPlayer jp = JEntityManager.instance.getPlayer(uuid);
        if (jp != null) {
            // 실행 중인 스킬 전부 종료
            for (var skill : jp.getActiveSkills()) {
                skill.end();
            }
            // 데이터 저장
            JData.save(jp);
        }

        JEntityManager.instance.unregister(uuid);
        PaperJJK.log("[JEvent] " + player.getName() + " quit, saved and unregistered.");
    }
}
