// This file was designed and is an original check for GrimAC
// Copyright (C) 2021 DefineOutside
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PositionUpdate;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.ReachEntityMoveData;
import ac.grim.grimac.utils.data.packetentity.PlayerReachEntity;
import ac.grim.grimac.utils.nmsImplementations.ReachUtils;
import io.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.event.impl.PacketPlayReceiveEvent;
import io.github.retrooper.packetevents.event.impl.PacketPlaySendEvent;
import io.github.retrooper.packetevents.packettype.PacketType;
import io.github.retrooper.packetevents.packetwrappers.api.SendableWrapper;
import io.github.retrooper.packetevents.packetwrappers.play.in.useentity.WrappedPacketInUseEntity;
import io.github.retrooper.packetevents.packetwrappers.play.out.entity.WrappedPacketOutEntity;
import io.github.retrooper.packetevents.packetwrappers.play.out.entityteleport.WrappedPacketOutEntityTeleport;
import io.github.retrooper.packetevents.packetwrappers.play.out.namedentityspawn.WrappedPacketOutNamedEntitySpawn;
import io.github.retrooper.packetevents.packetwrappers.play.out.ping.WrappedPacketOutPing;
import io.github.retrooper.packetevents.packetwrappers.play.out.transaction.WrappedPacketOutTransaction;
import io.github.retrooper.packetevents.utils.pair.Pair;
import io.github.retrooper.packetevents.utils.player.ClientVersion;
import io.github.retrooper.packetevents.utils.server.ServerVersion;
import io.github.retrooper.packetevents.utils.vector.Vector3d;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// You may not copy the check unless you are licensed under GPL
public class Reach extends PacketCheck {

    public static final ExecutorService posSender = Executors.newSingleThreadExecutor();
    public final Int2ObjectLinkedOpenHashMap<PlayerReachEntity> entityMap = new Int2ObjectLinkedOpenHashMap<>();
    private final GrimPlayer player;
    private final ConcurrentLinkedQueue<Integer> playerAttackQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Pair<ReachEntityMoveData, SendableWrapper>> moveQueue = new ConcurrentLinkedQueue<>();

    private short trackedTransaction = Short.MIN_VALUE;
    private boolean ignoreThisPacket = false; // Not required to be atomic - sync'd to one thread

    public Reach(GrimPlayer player) {
        super(player);
        this.player = player;
    }

    @Override
    public void onPacketReceive(final PacketPlayReceiveEvent event) {
        if (event.getPacketId() == PacketType.Play.Client.USE_ENTITY) {
            WrappedPacketInUseEntity action = new WrappedPacketInUseEntity(event.getNMSPacket());
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getPlayer());

            if (player == null) return;

            if (action.getAction() == WrappedPacketInUseEntity.EntityUseAction.ATTACK) {
                checkReach(action.getEntityId());
            }
        }
    }

    @Override
    public void onPositionUpdate(final PositionUpdate positionUpdate) {
        tickFlying();
    }

    public void checkReach(int entityID) {
        if (entityMap.containsKey(entityID))
            playerAttackQueue.add(entityID);
    }

    private void tickFlying() {
        Integer attackQueue = playerAttackQueue.poll();
        while (attackQueue != null) {
            PlayerReachEntity reachEntity = entityMap.get((int) attackQueue);
            SimpleCollisionBox targetBox = reachEntity.getPossibleCollisionBoxes();
            Vector3d from = player.packetStateData.lastPacketPosition;

            // 1.9 -> 1.8 precision loss in packets
            // (ViaVersion is doing some stuff that makes this code difficult)
            //
            // This will likely be fixed with PacketEvents 2.0, where our listener is before ViaVersion
            // Don't attempt to fix it with this version of PacketEvents, it's not worth our time when 2.0 will fix it.
            if (ServerVersion.getVersion().isNewerThanOrEquals(ServerVersion.v_1_9) && player.getClientVersion().isOlderThan(ClientVersion.v_1_9)) {
                targetBox.expand(0.03125);
            }

            // 1.7 and 1.8 players get a bit of extra hitbox (this is why you should use 1.8 on cross version servers)
            // Yes, this is vanilla and not uncertainty.  All reach checks have this or they are wrong.
            if (player.getClientVersion().isOlderThan(ClientVersion.v_1_9)) {
                targetBox.expand(0.1);
            }

            // This is better than adding to the reach, as 0.03 can cause a player to miss their target
            // Adds some more than 0.03 uncertainty in some cases, but a good trade off for simplicity
            //
            // Just give the uncertainty on 1.9+ clients as we have no way of knowing whether they had 0.03 movement
            //
            // Technically I should only have to listen for lastLastMovement, although Tecnio warned me to just use both
            if (!player.packetStateData.didLastLastMovementIncludePosition || !player.packetStateData.didLastMovementIncludePosition || player.getClientVersion().isNewerThanOrEquals(ClientVersion.v_1_9))
                targetBox.expand(0.03);

            // TODO: Support complex 1.14+ get eye height
            Vector eyePos = new Vector(from.getX(), from.getY() + (player.packetStateData.isPacketSneaking ? 1.54 : 1.62), from.getZ());
            Vector attackerDirection = ReachUtils.getLook(player, player.packetStateData.packetPlayerXRot, player.packetStateData.packetPlayerYRot);
            Vector endReachPos = eyePos.clone().add(new Vector(attackerDirection.getX() * 6, attackerDirection.getY() * 6, attackerDirection.getZ() * 6));

            Vector intercept = ReachUtils.calculateIntercept(targetBox, eyePos, endReachPos);
            Vector vanillaIntercept = null;

            // This is how vanilla handles look vectors on 1.8 - it's a tick behind.
            // 1.9+ you have no guarantees of which look vector it is due to 0.03
            //
            // The only safe version is 1.7
            if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.v_1_8)) {
                Vector vanillaDir = ReachUtils.getLook(player, player.packetStateData.packetPlayerXRot, player.packetStateData.packetPlayerYRot);
                Vector vanillaEndPos = eyePos.clone().add(new Vector(vanillaDir.getX() * 6, vanillaDir.getY() * 6, vanillaDir.getZ() * 6));

                vanillaIntercept = ReachUtils.calculateIntercept(targetBox, eyePos, vanillaEndPos);
            }

            if (!ReachUtils.isVecInside(targetBox, eyePos)) {
                if (intercept == null && vanillaIntercept == null) {
                    Bukkit.broadcastMessage(ChatColor.RED + "Player missed hitbox!");
                } else {
                    double maxReach = player.packetStateData.gameMode == GameMode.CREATIVE ? 5 : 3;

                    double reach = 6;
                    if (intercept != null)
                        reach = eyePos.distance(intercept);
                    if (vanillaIntercept != null)
                        reach = Math.min(reach, eyePos.distance(vanillaIntercept));

                    if (reach < maxReach && (!player.packetStateData.didLastLastMovementIncludePosition || !player.packetStateData.didLastMovementIncludePosition)) {
                        Bukkit.broadcastMessage(ChatColor.GREEN + "Intersected!  Reach was " + reach + " (0.03 = true)");
                    } else if (reach < maxReach) {
                        Bukkit.broadcastMessage(ChatColor.GREEN + "Intersected!  Reach was " + reach);
                    } else {
                        Bukkit.broadcastMessage(ChatColor.RED + "Intersected!  Reach was " + reach + " 0.03 " + player.packetStateData.didLastLastMovementIncludePosition + " " + player.packetStateData.didLastMovementIncludePosition + " report on discord if false - DefineOutside#4497");
                    }
                }
            }

            attackQueue = playerAttackQueue.poll();
        }

        for (PlayerReachEntity entity : entityMap.values()) {
            entity.onMovement(player.getClientVersion().isNewerThan(ClientVersion.v_1_8));
        }
    }

    @Override
    public void onPacketSend(final PacketPlaySendEvent event) {
        byte packetID = event.getPacketId();

        if (ignoreThisPacket) return;

        if (packetID == PacketType.Play.Server.TRANSACTION) {
            WrappedPacketOutTransaction transaction = new WrappedPacketOutTransaction(event.getNMSPacket());
            if (transaction.getActionNumber() == trackedTransaction)
                event.setPostTask(this::handleMarkedTransaction);
        }

        if (packetID == PacketType.Play.Server.PING) {
            WrappedPacketOutPing transaction = new WrappedPacketOutPing(event.getNMSPacket());
            if (transaction.getId() == trackedTransaction)
                event.setPostTask(this::handleMarkedTransaction);
        }

        if (packetID == PacketType.Play.Server.NAMED_ENTITY_SPAWN) {
            WrappedPacketOutNamedEntitySpawn spawn = new WrappedPacketOutNamedEntitySpawn(event.getNMSPacket());
            Entity entity = spawn.getEntity();

            if (entity != null && entity.getType() == EntityType.PLAYER) {
                handleSpawnPlayer(spawn.getEntityId(), spawn.getPosition());
            }
        }

        if (packetID == PacketType.Play.Server.REL_ENTITY_MOVE || packetID == PacketType.Play.Server.REL_ENTITY_MOVE_LOOK || packetID == PacketType.Play.Server.ENTITY_LOOK) {
            WrappedPacketOutEntity.WrappedPacketOutRelEntityMove move = new WrappedPacketOutEntity.WrappedPacketOutRelEntityMove(event.getNMSPacket());

            if (entityMap.containsKey(move.getEntityId())) {
                event.setCancelled(true);
                ReachEntityMoveData moveData = new ReachEntityMoveData(move.getEntityId(), move.getDeltaX(), move.getDeltaY(), move.getDeltaZ(), true);
                moveQueue.add(new Pair<>(moveData, move));
            }
        }

        if (packetID == PacketType.Play.Server.ENTITY_TELEPORT) {
            WrappedPacketOutEntityTeleport teleport = new WrappedPacketOutEntityTeleport(event.getNMSPacket());

            if (entityMap.containsKey(teleport.getEntityId())) {
                event.setCancelled(true);
                Vector3d position = teleport.getPosition();
                ReachEntityMoveData moveData = new ReachEntityMoveData(teleport.getEntityId(), position.getX(), position.getY(), position.getZ(), false);
                moveQueue.add(new Pair<>(moveData, teleport));
            }
        }
    }

    // Fun hack to sync to netty
    // otherwise someone else might send some packet, and we accidentally cancel it
    private void handleMarkedTransaction() {
        ignoreThisPacket = true;
        for (Pair<ReachEntityMoveData, SendableWrapper> moveData : moveQueue) {
            handleMoveEntity(moveData.getFirst().getEntityID(), moveData.getFirst().getX(), moveData.getFirst().getY(), moveData.getFirst().getZ(), moveData.getFirst().isRelative());
            PacketEvents.get().getPlayerUtils().writePacket(player.bukkitPlayer, moveData.getSecond());
        }
        ignoreThisPacket = false;
        moveQueue.clear();

        player.sendAndFlushTransactionOrPingPong();
    }

    private void handleSpawnPlayer(int playerID, Vector3d spawnPosition) {
        entityMap.put(playerID, new PlayerReachEntity(spawnPosition.getX(), spawnPosition.getY(), spawnPosition.getZ()));
    }

    private void handleMoveEntity(int entityId, double deltaX, double deltaY, double deltaZ, boolean isRelative) {
        PlayerReachEntity reachEntity = entityMap.get(entityId);

        if (reachEntity != null) {
            // Update the tracked server's entity position
            if (isRelative)
                reachEntity.serverPos = reachEntity.serverPos.add(new Vector3d(deltaX, deltaY, deltaZ));
            else
                reachEntity.serverPos = new Vector3d(deltaX, deltaY, deltaZ);

            int lastTrans = player.lastTransactionSent.get();
            Vector3d newPos = reachEntity.serverPos;

            player.latencyUtils.addRealTimeTask(lastTrans, () -> reachEntity.onFirstTransaction(newPos.getX(), newPos.getY(), newPos.getZ()));
            player.latencyUtils.addRealTimeTask(lastTrans + 1, reachEntity::onSecondTransaction);
        }
    }

    public void onEndOfTickEvent() {
        if (!moveQueue.isEmpty()) { // Only spam transactions if we have to
            trackedTransaction = player.getNextTransactionID(1);
            player.sendTransactionOrPingPong(trackedTransaction, false);
        }
    }

    public void removeEntity(int entityID) {
        entityMap.remove(entityID);
    }
}