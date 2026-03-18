package org.justheare.paperjjk;

import org.bukkit.plugin.java.JavaPlugin;
import org.justheare.paperjjk.barrier.DomainBlockBuilder;
import org.justheare.paperjjk.barrier.DomainManager;
import org.justheare.paperjjk.network.JEntityManager;
import org.justheare.paperjjk.network.JPacketHandler;
import org.justheare.paperjjk.scheduler.WorkScheduler;

public class PaperJJK extends JavaPlugin {

    public static PaperJJK instance;

    @Override
    public void onEnable() {
        instance = this;

        // JEntityManager 초기화
        JEntityManager.init();

        // DomainManager 초기화
        DomainManager.init();

        // 플레이어 데이터 로드 (playerdata.yml)
        JData.init(getDataFolder());

        // WorkScheduler 시작 (모든 스킬 틱 관리)
        WorkScheduler.getInstance().start();

        // 이벤트 리스너 등록
        getServer().getPluginManager().registerEvents(new JEvent(), this);

        // 커맨드 등록
        var cmd = getCommand("jjk");
        if (cmd != null) {
            Jcommand handler = new Jcommand();
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        // Plugin Messaging Channel 등록
        getServer().getMessenger().registerIncomingPluginChannel(
                this, JPacketHandler.CHANNEL, new JPacketHandler(this));
        getServer().getMessenger().registerOutgoingPluginChannel(
                this, JPacketHandler.CHANNEL);

        // 구 오프셋 워밍업: 첫 결없영 전개 때 메인 스레드 렉 방지 (r=0~200 비동기 선계산)
        DomainBlockBuilder.warmupAsync(200);

        getLogger().info("PaperJJK v2 enabled.");
    }

    @Override
    public void onDisable() {
        // 모든 플레이어 데이터 저장
        JData.saveAll();

        WorkScheduler.getInstance().stop();
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getLogger().info("PaperJJK v2 disabled.");
    }

    /** true 일 때 데미지 파이프라인 디버그 로그 출력 */
    public static boolean DEBUG_DAMAGE = true;

    public static void log(String msg) {
        instance.getLogger().info(msg);
    }

    public static void logDamage(String msg) {
        if (DEBUG_DAMAGE) instance.getLogger().info(msg);
    }
}
