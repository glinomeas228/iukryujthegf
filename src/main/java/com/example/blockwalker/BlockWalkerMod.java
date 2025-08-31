package com.example.blockwalker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.*;

/**
 * Fabric client mod. When you type ".start" in chat, it scans cube (13,29,-146) to (33,41,-166)
 * and attempts to walk to all non-air blocks without breaking or placing blocks.
 */
public class BlockWalkerMod implements ClientModInitializer {
    // Cube bounds
    private static final int MIN_X = 13, MAX_X = 33;
    private static final int MIN_Y = 29, MAX_Y = 41;
    private static final int MIN_Z = -166, MAX_Z = -146;

    private static final int SAFE_DROP = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "blockwalker-worker"));
    private volatile boolean running = false;

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.CHAT.register(message -> {
            if (".start".equals(message.trim())) {
                if (!running) {
                    running = true;
                    worker.submit(this::runTask);
                }
            }
        });
    }

    private boolean isOutsideCube(int x, int y, int z) {
        return x < MIN_X || x > MAX_X || y < MIN_Y || y > MAX_Y || z < MIN_Z || z > MAX_Z;
    }

    private void runTask() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.of("[BlockWalker] starting scan...")));

        List<BlockPos> targets = new ArrayList<>();
        CompletableFuture<Void> gatherFuture = new CompletableFuture<>();
        client.execute(() -> {
            World world = client.world;
            if (world == null) {
                gatherFuture.complete(null);
                return;
            }
            for (int x = MIN_X; x <= MAX_X; x++) {
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    for (int z = MIN_Z; z <= MAX_Z; z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        Block b = world.getBlockState(p).getBlock();
                        if (!b.equals(Blocks.AIR)) {
                            targets.add(p);
                        }
                    }
                }
            }
            gatherFuture.complete(null);
        });
        try { gatherFuture.get(); } catch (Exception ignored) {}

        targets.sort(Comparator.comparingDouble(this::distanceSqToPlayer));

        for (BlockPos target : targets) {
            if (!running) break;
            List<BlockPos> access = computeAccessPoints(target);
            access.sort(Comparator.comparingDouble(this::distanceSqToPlayer));

            boolean reached = false;
            for (BlockPos accessPoint : access) {
                List<BlockPos> path = computePath(accessPoint);
                if (path != null && followPath(path)) {
                    client.execute(() -> client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.of("[BlockWalker] visited " + target.toShortString())));
                    reached = true;
                    break;
                }
            }
            if (!reached) {
                client.execute(() -> client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.of("[BlockWalker] unreachable: " + target.toShortString())));
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        stop();
    }

    private void stop() {
        running = false;
        MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().inGameHud().getChatHud().addMessage(net.minecraft.text.Text.of("[BlockWalker] finished/stop.")));
    }

    private double distanceSqToPlayer(BlockPos p) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return Double.MAX_VALUE;
        double dx = player.getX() - (p.getX() + 0.5);
        double dy = player.getY() - p.getY();
        double dz = player.getZ() - (p.getZ() + 0.5);
        return dx*dx + dy*dy + dz*dz;
    }

    private List<BlockPos> computeAccessPoints(BlockPos target) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = client.world;
        List<BlockPos> acc = new ArrayList<>();
        if (world == null) return acc;

        BlockPos[] neighbors = {
                target.add(1,0,0), target.add(-1,0,0),
                target.add(0,1,0), target.add(0,-1,0),
                target.add(0,0,1), target.add(0,0,-1)
        };
        for (BlockPos p : neighbors) {
            if (!world.getBlockState(p).getBlock().equals(Blocks.AIR)) continue;
            BlockPos below = p.down();
            Block belowBlock = world.getBlockState(below).getBlock();
            boolean support = !belowBlock.equals(Blocks.AIR);
            if (belowBlock == Blocks.LADDER && !isOutsideCube(below.getX(), below.getY(), below.getZ())) support = false;
            if (support) acc.add(p);
        }
        BlockPos above = target.up();
        if (world.getBlockState(above).getBlock().equals(Blocks.AIR)) {
            BlockPos below = above.down();
            if (!world.getBlockState(below).getBlock().equals(Blocks.AIR)) acc.add(above);
        }
        return acc;
    }

    private List<BlockPos> computePath(BlockPos goal) {
        MinecraftClient client = MinecraftClient.getInstance();
        World world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) return null;

        BlockPos start = new BlockPos(Math.floor(player.getX()), Math.floor(player.getY()), Math.floor(player.getZ()));

        Set<BlockPos> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> gScore = new HashMap<>();

        open.add(new Node(start, 0, heuristic(start, goal)));
        gScore.put(start, 0.0);

        int iterationLimit = 20000;
        int iter = 0;

        while(!open.isEmpty() && iter++ < iterationLimit) {
            Node curr = open.poll();
            if (curr.pos.equals(goal)) {
                List<BlockPos> path = new ArrayList<>();
                BlockPos p = goal;
                path.add(p);
                while (!p.equals(start)) {
                    p = cameFrom.get(p);
                    if (p == null) break;
                    path.add(p);
                }
                Collections.reverse(path);
                return path;
            }
            closed.add(curr.pos);

            for (BlockPos nb : neighborsFor(curr.pos, world)) {
                if (closed.contains(nb)) continue;
                double tentativeG = gScore.getOrDefault(curr.pos, Double.MAX_VALUE) + curr.pos.getSquaredDistance(nb);
                if (tentativeG < gScore.getOrDefault(nb, Double.MAX_VALUE)) {
                    cameFrom.put(nb, curr.pos);
                    gScore.put(nb, tentativeG);
                    double f = tentativeG + heuristic(nb, goal);
                    open.add(new Node(nb, tentativeG, f));
                }
            }
        }
        return null;
    }

    private static class Node {
        BlockPos pos; double g; double f;
        Node(BlockPos p, double g, double f) { this.pos = p; this.g = g; this.f = f; }
    }

    private List<BlockPos> neighborsFor(BlockPos p, World world) {
        List<BlockPos> out = new ArrayList<>();
        int[][] deltas = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
        for (int[] d : deltas) {
            BlockPos horiz = p.add(d[0], 0, d[2]);
            if (canStandAt(horiz, world)) {
                out.add(horiz);
                continue;
            }
            BlockPos up1 = horiz.up();
            if (canStandAt(up1, world) && (world.getBlockState(horiz).getBlock().equals(Blocks.AIR))) {
                out.add(up1);
            }
        }
        for (int down = 1; down <= SAFE_DROP; down++) {
            BlockPos downPos = p.down(down);
            if (canStandAt(downPos, world)) {
                out.add(downPos);
                break;
            }
        }
        return out;
    }

    private boolean canStandAt(BlockPos pos, World world) {
        if (!world.getBlockState(pos).getBlock().equals(Blocks.AIR)) return false;
        BlockPos below = pos.down();
        Block b = world.getBlockState(below).getBlock();
        if (b.equals(Blocks.AIR)) return false;
        if (b.equals(Blocks.LADDER) && !isOutsideCube(below.getX(), below.getY(), below.getZ())) return false;
        return true;
    }

    private double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX()-b.getX();
        double dy = a.getY()-b.getY();
        double dz = a.getZ()-b.getZ();
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }

    private boolean followPath(List<BlockPos> path) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return false;

        for (BlockPos step : path) {
            if (!running) return false;
            double tx = step.getX() + 0.5;
            double ty = step.getY();
            double tz = step.getZ() + 0.5;
            CompletableFuture<Void> moveFut = new CompletableFuture<>();
            client.execute(() -> {
                player.setPos(tx, ty, tz);
                if (player.networkHandler != null) {
                    player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(tx, ty, tz, player.isOnGround()));
                }
                moveFut.complete(null);
            });
            try { moveFut.get(2, TimeUnit.SECONDS); } catch (Exception e) { return false; }
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }
        return true;
    }
}
