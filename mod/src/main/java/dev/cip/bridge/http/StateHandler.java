package dev.cip.bridge.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.cip.bridge.exec.ThreadBridge;

/**
 * {@code GET /state}：玩家状态快照（SPEC v1.1 §4.1 / CB-03）。
 *
 * <p><b>线程铁律（SPEC §2.4）</b>：{@link #handle(HttpApi.Request)} 跑在
 * {@code cipbridge-http-*} 线程上，方法体内只做两件事——把读取任务投递给
 * {@link ThreadBridge#onClient}、阻塞等待结果。所有 Minecraft 对象
 * （{@code Minecraft} / {@code player} / {@code level} / 射线 / 注册表）只在
 * {@link #snapshot()} 里触碰，而 {@code snapshot()} 由客户端线程执行。
 * 因此"HTTP 线程绝不直接读世界"在编译期就能一眼审阅：{@code handle} 里没有
 * 任何 MC 字段/方法引用。</p>
 *
 * <p>失败语义：未进世界（{@code Minecraft}/{@code player}/{@code level} 为 null，
 * 或客户端尚未就绪导致 {@link ThreadBridge#onClient} 直接抛
 * {@link ThreadBridge.BridgeException}）→ <b>503</b>
 * {@code {"ok":false,"error":"no minecraft","code":"not_ingame"}}，绝不让 HTTP 线程崩；
 * 游戏线程 5 s 内没响应 → <b>504</b> {@code timeout}（不把 HTTP 线程永久挂住）。
 * 其它异常按 HttpApi 的统一 500 处理。</p>
 *
 * <p>字段形状见 CB-03 卡：{@code ok,tick,dim,world,player{...},nearby_entities}。
 * {@code look_at} 只在准星命中方块时出现（MISS 时省略，而不是伪造成 air）；
 * {@code nearby_entities} 统计半径 16 格内的存活实体（不含玩家自己）。</p>
 */
public final class StateHandler implements HttpApi.Handler {

    /** /state 等待客户端线程的上限：正常是微秒级，5 s 只用于把"卡死"变成 504。 */
    private static final long TIMEOUT_MS = 5_000L;

    /** nearby_entities 的统计半径（格）。 */
    private static final double NEARBY_RADIUS = 16.0;

    /** look_at 的射线长度（格），与游戏内交互距离量级一致。 */
    private static final double LOOK_DISTANCE = 8.0;

    @Override
    public HttpApi.Response handle(HttpApi.Request request) {
        // ↓ 本方法运行在 HTTP 线程：除 ThreadBridge 的调度/等待外，不得出现任何 MC 引用。
        try {
            CompletableFuture<JsonObject> pending = ThreadBridge.onClient(StateHandler::snapshot);
            JsonObject snapshot = ThreadBridge.await(pending, TIMEOUT_MS);
            if (snapshot == null) {
                return noMinecraft();
            }
            return HttpApi.Response.json(snapshot);
        } catch (ThreadBridge.BridgeException e) {
            if (e.isTimeout()) {
                return HttpApi.Response.error(504, "timeout",
                        "timed out after " + TIMEOUT_MS + " ms waiting for the client thread");
            }
            // Minecraft 未就绪 / 任务被取消：对调用方都是"现在拿不到游戏状态"。
            return noMinecraft();
        }
    }

    private static HttpApi.Response noMinecraft() {
        return HttpApi.Response.error(503, "not_ingame", "no minecraft");
    }

    // ────────────────────────── 以下全部在客户端线程执行 ──────────────────────────

    /**
     * 组装状态快照；未进世界返回 {@code null}（由 {@link #handle} 映射成 503）。
     *
     * <p>只允许在客户端线程调用（{@link ThreadBridge#onClient} 的 supplier 内）。</p>
     */
    private static JsonObject snapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return null;
        }

        JsonObject root = Json.obj();
        root.addProperty("ok", true);
        root.addProperty("tick", level.getGameTime());
        root.addProperty("dim", level.dimension().location().toString());
        root.addProperty("world", worldName(minecraft));

        JsonObject playerJson = Json.obj();
        playerJson.addProperty("name", player.getScoreboardName());
        playerJson.add("pos", vec(player.position()));
        playerJson.add("block_pos", blockPos(player.blockPosition()));
        playerJson.addProperty("yaw", round1(player.getYRot()));
        playerJson.addProperty("pitch", round1(player.getXRot()));
        playerJson.addProperty("hp", player.getHealth());
        playerJson.addProperty("food", player.getFoodData().getFoodLevel());
        playerJson.addProperty("xp_level", player.experienceLevel);
        playerJson.addProperty("gm", gameMode(minecraft));
        playerJson.addProperty("sneaking", player.isCrouching());
        playerJson.addProperty("on_ground", player.onGround());
        playerJson.addProperty("in_water", player.isInWater());
        playerJson.addProperty("in_lava", player.isInLava());
        playerJson.addProperty("facing", Direction.fromYRot(player.getYRot()).getName());
        JsonObject lookAt = lookAt(player, level);
        if (lookAt != null) {
            playerJson.add("look_at", lookAt);
        }
        playerJson.add("main_hand", mainHand(player));

        root.add("player", playerJson);
        root.addProperty("nearby_entities", nearbyEntities(level, player));
        return root;
    }

    /**
     * 世界名：单机存档读集成服务器 {@code level-name}；多人退回当前服务器条目名/地址；
     * 都拿不到时用 {@code "unknown"}（不编造）。
     */
    private static String worldName(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            String name = server.getWorldData().getLevelName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        ServerData current = minecraft.getCurrentServer();
        if (current != null) {
            if (current.name != null && !current.name.isBlank()) {
                return current.name;
            }
            if (current.ip != null && !current.ip.isBlank()) {
                return current.ip;
            }
        }
        return "unknown";
    }

    private static String gameMode(Minecraft minecraft) {
        MultiPlayerGameMode manager = minecraft.gameMode;
        if (manager == null) {
            return "unknown";
        }
        GameType type = manager.getPlayerMode();
        return type == null ? "unknown" : type.getName();
    }

    /**
     * 准星命中的方块：{@code player.pick(8.0, 0.0F, false)} 只做方块射线（不做实体射线），
     * 命中方块才返回对象；{@code dist} 是眼睛到命中点的距离（射线真实长度）。
     */
    private static JsonObject lookAt(LocalPlayer player, ClientLevel level) {
        HitResult hit = player.pick(LOOK_DISTANCE, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state == null) {
            return null;
        }
        JsonObject lookAt = Json.obj();
        lookAt.addProperty("block", blockId(state));
        lookAt.add("pos", blockPos(pos));
        lookAt.addProperty("dist",
                round2(blockHit.getLocation().distanceTo(player.getEyePosition())));
        return lookAt;
    }

    private static JsonObject mainHand(LocalPlayer player) {
        ItemStack stack = player.getMainHandItem();
        JsonObject hand = Json.obj();
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        hand.addProperty("id", key == null ? "unknown" : key.toString());
        hand.addProperty("count", stack.getCount());
        return hand;
    }

    /** 半径 {@value #NEARBY_RADIUS} 格内的存活实体数（{@code getEntities} 自带排除玩家自己）。 */
    private static int nearbyEntities(ClientLevel level, LocalPlayer player) {
        List<Entity> entities = level.getEntities(player,
                player.getBoundingBox().inflate(NEARBY_RADIUS), Entity::isAlive);
        return entities.size();
    }

    private static String blockId(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key == null ? "unknown" : key.toString();
    }

    private static JsonArray vec(Vec3 vector) {
        JsonArray array = Json.arr();
        array.add(round2(vector.x));
        array.add(round2(vector.y));
        array.add(round2(vector.z));
        return array;
    }

    private static JsonArray blockPos(BlockPos pos) {
        JsonArray array = Json.arr();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
