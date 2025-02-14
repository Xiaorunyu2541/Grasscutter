package emu.grasscutter.game.dungeons;

import emu.grasscutter.game.dungeons.dungeon_results.BaseDungeonResult.DungeonEndReason;
import emu.grasscutter.game.dungeons.dungeon_results.TowerResult;
import emu.grasscutter.server.packet.send.PacketDungeonSettleNotify;
import emu.grasscutter.server.packet.send.PacketTowerFloorRecordChangeNotify;

public class TowerDungeonSettleListener implements DungeonSettleListener {

    @Override
    public void onDungeonSettle(DungeonManager dungeonManager, DungeonEndReason endReason) {
        var scene = dungeonManager.getScene();
        var dungeonData = dungeonManager.getDungeonData();
        if(scene.getScriptManager().getVariables().containsKey("stage")
                && scene.getScriptManager().getVariables().get("stage") == 1){
            return;
        }

        var towerManager = scene.getPlayers().get(0).getTowerManager();

        towerManager.notifyCurLevelRecordChangeWhenDone(3);
        scene.broadcastPacket(new PacketTowerFloorRecordChangeNotify(
                towerManager.getCurrentFloorId(),
                3,
                towerManager.canEnterScheduleFloor()
        ));

        var challenge = scene.getChallenge();
        var dungeonStats = new DungeonEndStats(scene.getKilledMonsterCount(), challenge.getFinishedTime(), 0, endReason);
        var result = new TowerResult(dungeonData, dungeonStats, towerManager, challenge);

        scene.broadcastPacket(new PacketDungeonSettleNotify(result));

    }
}
