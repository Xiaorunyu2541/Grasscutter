package emu.grasscutter.game.dungeons;

import emu.grasscutter.GameConstants;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.excels.DungeonData;
import emu.grasscutter.data.excels.DungeonPassConfigData;
import emu.grasscutter.game.dungeons.handlers.DungeonBaseHandler;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.server.game.BaseGameSystem;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.packet.send.PacketDungeonEntryInfoRsp;
import emu.grasscutter.server.packet.send.PacketPlayerEnterDungeonRsp;
import emu.grasscutter.utils.Position;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.reflections.Reflections;

import java.util.List;

public class DungeonSystem extends BaseGameSystem {
    private static final BasicDungeonSettleListener basicDungeonSettleObserver = new BasicDungeonSettleListener();
    private final Int2ObjectMap<DungeonBaseHandler> passCondHandlers;

    public DungeonSystem(GameServer server) {
        super(server);
        this.passCondHandlers = new Int2ObjectOpenHashMap<>();
        registerHandlers();
    }

    public void registerHandlers() {
        this.registerHandlers(this.passCondHandlers, "emu.grasscutter.game.dungeons.pass_condition", DungeonBaseHandler.class);
    }

    public <T> void registerHandlers(Int2ObjectMap<T> map, String packageName, Class<T> clazz) {
        Reflections reflections = new Reflections(packageName);
        var handlerClasses = reflections.getSubTypesOf(clazz);

        for (var obj : handlerClasses) {
            this.registerPacketHandler(map, obj);
        }
    }

    public <T> void registerPacketHandler(Int2ObjectMap<T> map, Class<? extends T> handlerClass) {
        try {
            DungeonValue opcode = handlerClass.getAnnotation(DungeonValue.class);

            if (opcode == null || opcode.value() == null) {
                return;
            }

            map.put(opcode.value().ordinal(), handlerClass.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void getEntryInfo(Player player, int pointId) {
        ScenePointEntry entry = GameData.getScenePointEntryById(player.getScene().getId(), pointId);

        if (entry == null) {
            // Error
            player.sendPacket(new PacketDungeonEntryInfoRsp());
            return;
        }

        player.sendPacket(new PacketDungeonEntryInfoRsp(player, entry.getPointData()));
    }

    public boolean triggerCondition(DungeonPassConfigData.DungeonPassCondition condition, int... params) {
        var handler = passCondHandlers.get(condition.getCondType().ordinal());

        if (handler == null) {
            Grasscutter.getLogger().debug("Could not trigger condition {} at {}", condition.getCondType(), params);
            return false;
        }

        return handler.execute(condition, params);
    }

    public boolean enterDungeon(Player player, int pointId, int dungeonId) {
        DungeonData data = GameData.getDungeonDataMap().get(dungeonId);

        if (data == null) {
            return false;
        }
        Grasscutter.getLogger().info("{}({}) is trying to enter dungeon {}" ,player.getNickname(),player.getUid(),dungeonId);

        int sceneId = data.getSceneId();
        var scene = player.getScene();
        scene.setPrevScene(sceneId);

        if (player.getWorld().transferPlayerToScene(player, sceneId, data)) {
            scene = player.getScene();
            var dungeonManager = new DungeonManager(scene, data);
            dungeonManager.startDungeon();
            scene.addDungeonSettleObserver(basicDungeonSettleObserver);
        }

        scene.setPrevScenePoint(pointId);
        player.sendPacket(new PacketPlayerEnterDungeonRsp(pointId, dungeonId));
        return true;
    }

    /**
     * used in tower dungeons handoff
     */
    public boolean handoffDungeon(Player player, int dungeonId, List<DungeonSettleListener> dungeonSettleListeners) {
        DungeonData data = GameData.getDungeonDataMap().get(dungeonId);

        if (data == null) {
            return false;
        }
        Grasscutter.getLogger().info("{}({}) is trying to enter tower dungeon {}" ,player.getNickname(),player.getUid(),dungeonId);

        if (player.getWorld().transferPlayerToScene(player, data.getSceneId(), data)) {
            dungeonSettleListeners.forEach(player.getScene()::addDungeonSettleObserver);
        }
        return true;
    }

    public void exitDungeon(Player player) {
        Scene scene = player.getScene();

        if (scene==null || scene.getSceneType() != SceneType.SCENE_DUNGEON) {
            return;
        }

        // Get previous scene
        int prevScene = scene.getPrevScene() > 0 ? scene.getPrevScene() : 3;

        // Get previous position
        var dungeonManager = scene.getDungeonManager();
        DungeonData dungeonData =  dungeonManager != null ? dungeonManager.getDungeonData() : null;
        Position prevPos = new Position(GameConstants.START_POSITION);

        if (dungeonData != null) {
            ScenePointEntry entry = GameData.getScenePointEntryById(prevScene, scene.getPrevScenePoint());

            if (entry != null) {
                prevPos.set(entry.getPointData().getTranPos());
            }
            if(!dungeonManager.isFinishedSuccessfully()){
                dungeonManager.quitDungeon();
            }
        }
        // clean temp team if it has
        player.getTeamManager().cleanTemporaryTeam();
        player.getTowerManager().clearEntry();

        // Transfer player back to world
        player.getWorld().transferPlayerToScene(player, prevScene, prevPos);
        player.sendPacket(new BasePacket(PacketOpcodes.PlayerQuitDungeonRsp));
    }

    public void updateDailyDungeons() {
        GameData.getScenePointEntries().forEach((id, entry) -> entry.getPointData().updateDailyDungeon());
    }
}
