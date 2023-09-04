package net.runelite.client.plugins.microbot.util.walker.pathfinder;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.camera.Camera;
import net.runelite.client.plugins.microbot.util.equipment.JewelleryLocationEnum;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Teleport;
import net.runelite.client.plugins.microbot.util.math.Calculations;
import net.runelite.client.plugins.microbot.util.tabs.Tab;
import net.runelite.client.plugins.microbot.util.walker.Transport;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.walker.pathfinder.CollisionMap.wallNodes;

public class Pathfinder implements Runnable {
    @Getter
    private final WorldPoint start;
    @Getter
    private WorldPoint target;
    private final PathfinderConfig config;

    private final Deque<Node> boundary = new LinkedList<>();
    private final Set<WorldPoint> visited = new HashSet<>();
    @Getter
    private final Queue<Node> pending = new PriorityQueue<>();
    @Getter
    @Setter
    private List<Node> path = new ArrayList<>();
    @Getter
    private boolean done = false;
    @Getter
    int totalCost = 0;

    @Getter
    @Setter
    private boolean useTransport = true;

    Transport useCurrentTransport = null;

    boolean executeWalking = true;

    boolean useCanvas = false;

    public boolean customPath = false;


    public boolean getDebugger() {
        return false;
    }

    public Pathfinder(PathfinderConfig config) {
        this.config = config;
        this.config.refresh();
        this.executeWalking = true;
        this.start = null;
        this.config.refresh();
        new Thread(this).start();
    }

    public Pathfinder(PathfinderConfig config, WorldPoint start, WorldPoint target) {
        this.config = config;
        this.start = start;
        this.target = target;
        this.config.refresh();
        new Thread(this).start();
    }

    public Pathfinder(PathfinderConfig config, WorldPoint start, WorldPoint target, boolean canReach) {
        this.config = config;
        this.start = start;
        this.target = target;
        this.config.refresh();
        this.executeWalking = !canReach;


        //Calculate all the stairce/obstacles/ladders in advance before calculating the path to walk
        precalculateTransports(start, target, useTransport);

        new Thread(this).start();
    }

    public Pathfinder(PathfinderConfig config, WorldPoint start, WorldPoint target, boolean useTransport, boolean canReach, boolean useCanvas, WorldArea[] blockingAreas) {
        this.config = config;
        this.start = start;
        this.target = target;
        this.config.refresh();
        this.useTransport = useTransport;
        this.executeWalking = !canReach;
        this.useCanvas = useCanvas;
        if (blockingAreas != null)
            CollisionMap.blockingAreas = blockingAreas;

        //Calculate all the stairce/obstacles/ladders in advance before calculating the path to walk
        precalculateTransports(start, target, useTransport);

        final int teleportThreshHoldDistance = 20;

        //No need to teleport if we are close to our target location
        // return early
        if (start.distanceTo(target) < teleportThreshHoldDistance) {
            new Thread(this).start();
            return;
        }

        Tab.switchToInventoryTab();

        Teleport shortestPathSpell = null;
        JewelleryLocationEnum shortestPathJewellery = null;
        boolean hasTablet = false;

        //Teleport spells logic
        for (Teleport teleport : Teleport.values()) {
            if (teleport.getDestination().equals(start)
                    || teleport.getDestination().distanceTo(target) < start.distanceTo(target)
            && (shortestPathSpell == null || shortestPathSpell.getDestination().distanceTo(target) > teleport.getDestination().distanceTo(target))) {
                hasTablet = Inventory.hasItem(teleport.getTabletName());
                boolean hasRunes = true;
                for (Pair itemRequired : teleport.getItemsRequired()) {
                    if (!Inventory.hasItemAmountStackable(itemRequired.getLeft().toString(), (int) itemRequired.getRight()))
                        hasRunes = false;
                }

                if (hasRunes && Microbot.getClient().getBoostedSkillLevel(Skill.MAGIC) >= teleport.getLevel()) {
                    shortestPathSpell = teleport;
                    break;
                }

                if (hasTablet) {
                    shortestPathSpell = teleport;
                    break;
                }
            }
        }
        //jewellery teleport logic
        for (JewelleryLocationEnum jewelleryLocationEnum : JewelleryLocationEnum.values()) {
            if (jewelleryLocationEnum.getLocation().equals(start)
                    || jewelleryLocationEnum.getLocation().distanceTo(target) < start.distanceTo(target)
                    && (shortestPathJewellery == null || shortestPathJewellery.getLocation().distanceTo(target) > jewelleryLocationEnum.getLocation().distanceTo(target))) {
                shortestPathJewellery = jewelleryLocationEnum;
            }
        }

        if (shortestPathSpell != null && shortestPathJewellery != null) {
            if (shortestPathSpell.getDestination().distanceTo(target) <= shortestPathJewellery.getLocation().distanceTo(target)) {
                useTeleport(shortestPathSpell, start, hasTablet);
            } else {
                useJewellery(shortestPathJewellery);
            }
        } else
        if (shortestPathSpell != null && shortestPathJewellery == null) {
            useTeleport(shortestPathSpell, start, hasTablet);
        } else
        if (shortestPathSpell == null && shortestPathJewellery != null) {
            useJewellery(shortestPathJewellery);
        }

        new Thread(this).start();

    }

    private void useTeleport(Teleport shortestPathSpell, WorldPoint start, boolean hasTablet) {
        if (hasTablet) {
            Inventory.useItemFast(shortestPathSpell.getTabletName(), "Break");
            sleepUntil(() -> Microbot.isAnimating());
            sleepUntil(() -> !Microbot.isAnimating());
        } else {
            Rs2Magic.cast(shortestPathSpell.getSpell());
            sleepUntil(() -> Microbot.isAnimating());
            sleepUntil(() -> !Microbot.isAnimating());
        }
    }

    private void useJewellery(JewelleryLocationEnum shortestPathJewellery) {
        String itemName = shortestPathJewellery.getTooltip() + "(";
        boolean hasItemEquipped = Rs2Equipment.hasEquippedContains(itemName);
        if (hasItemEquipped) {
            if (shortestPathJewellery.name().contains("ring")) {
                Rs2Equipment.useRingAction(shortestPathJewellery);
            } else {
                Rs2Equipment.useAmuletAction(shortestPathJewellery);
            }
            sleepUntil(() -> Microbot.isAnimating());
            sleepUntil(() -> !Microbot.isAnimating());
        } else if (Inventory.hasItemContains(itemName)) {
            Inventory.useItemFastContains(itemName, "Rub");
            sleepUntil(() -> Rs2Widget.getWidget(219, 1) != null);
            VirtualKeyboard.typeString(Integer.toString(shortestPathJewellery.getIdentifier() - 1));
            sleepUntil(() -> Microbot.isAnimating());
            sleepUntil(() -> !Microbot.isAnimating());
        }
    }

    private void precalculateTransports(WorldPoint start, WorldPoint target, boolean useTransport) {
        if (useTransport) {
            if (target.getPlane() != Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane()) {
                //custom logic for stairce/ladders
                Transport transport = getMatchingTransport(Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane());
                Transport linkedTransport = transport;
                while (linkedTransport != null) {
                    if (linkedTransport.linkedTransport == null) {
                        this.target = linkedTransport.origin;
                        this.useCurrentTransport = linkedTransport;
                        break;
                    }
                    if (target.getPlane() > Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane()) {
                        if (linkedTransport.linkedTransport.getOrigin().getPlane() == Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane()) {
                            this.target = linkedTransport.linkedTransport.getOrigin();
                            this.useCurrentTransport = linkedTransport.linkedTransport;
                            break;
                        }
                    } else {
                        if (linkedTransport.linkedTransport.getDestination().getPlane() == Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane()) {
                            this.target = linkedTransport.linkedTransport.getDestination();
                            this.useCurrentTransport = linkedTransport.linkedTransport;
                            this.useCurrentTransport.action = this.useCurrentTransport.reverseAction;
                            break;
                        }
                    }
                    linkedTransport = linkedTransport.linkedTransport;
                }
            } else {
                if (start.getY() < 9000 && target.getY() > 9000) {
                    //use object to go underground
                    Transport transportUnderground = getClosestTransport(target);
                    if (transportUnderground != null) {
                        this.target = transportUnderground.getOrigin();
                    }
                } else if (start.getY() >= 9000 && target.getY() < 9000) {
                    //use object to go surface
                    Transport transportSurface = getClosestTransport(target);
                    if (transportSurface != null) {
                        this.target = transportSurface.getDestination();
                    }
                }
            }
        }
    }

    public Pathfinder(PathfinderConfig config, WorldPoint target) {
        this.config = config;
        this.config.refresh();
        this.target = target;
        start = null;
        done = true;
    }

    private void addNeighbors(Node node) {
        for (Node neighbor : config.getMap().getNeighbors(node, config, useTransport, target, !this.executeWalking)) {
            if (config.avoidWilderness(node.position, neighbor.position, target)) {
                continue;
            }
            if (visited.add(neighbor.position)) {
                if (neighbor instanceof TransportNode) {
                    pending.add(neighbor);
                } else {
                    boundary.addLast(neighbor);
                }
            }
        }
    }

    @Override
    public void run() {
        boolean skip = false;
        CollisionMap.nodesChecked = new ArrayList<>();
        wallNodes = new ArrayList<>();
        Microbot.status = "Calculating webwalking, please wait...";
        boundary.addFirst(new Node(start, null));

        int bestDistance = Integer.MAX_VALUE;
        long bestHeuristic = Integer.MAX_VALUE;
        Instant cutoffTime = Instant.now().plus(Duration.ofSeconds(8));

        while (!boundary.isEmpty() || !pending.isEmpty()) {
            Node node = boundary.peekFirst();
            Node p = pending.peek();

            if (p != null && (node == null || p.cost < node.cost)) {
                boundary.addFirst(p);
                pending.poll();
            }

            node = boundary.removeFirst();

            if (node == null || node.position == null) break;

            if (node.position.distanceTo(target) < 1 || (!config.isNear(start))) {
                path = node.getPath();
                break;
            }

            int distance = Node.distanceBetween(node.position, target);
            long heuristic = distance + Node.distanceBetween(node.position, target, 2);
            if (heuristic < bestHeuristic || (heuristic <= bestHeuristic && distance < bestDistance)) {
                path = node.getPath();
                totalCost += node.cost;
                bestDistance = distance;
                bestHeuristic = heuristic;
                cutoffTime = Instant.now().plus(Duration.ofSeconds(8));
            }

            if (Instant.now().isAfter(cutoffTime)) {
                break;
            }

            addNeighbors(node);
        }


        if (this.executeWalking) {
            skip = handleDoors();

            if (!skip) {
                skip = handleTransports();
            }

            if (!skip) {
                Collections.reverse(path);
                if (useCanvas) {
                    handleWalkableNodesCanvas();
                } else {
                    handleWalkableNodes();
                }
            }
        }

        done = true;
        boundary.clear();
        visited.clear();
        pending.clear();
    }

    private void handleWalkableNodes() {
        for (Node node : path) {
            if (Calculations.tileOnMap(node.position) && (node.position.distanceTo(Microbot.getClient().getLocalPlayer().getWorldLocation()) < 14 || customPath)) {
                Point point = Calculations.tileToMinimap(node.position);
                Microbot.getMouse().click(point);
                break;
            }
        }
    }

    private void handleWalkableNodesCanvas() {
        for (Node node : path) {
            if (Calculations.tileOnMap(node.position) && node.position.distanceTo(Microbot.getClient().getLocalPlayer().getWorldLocation()) < 3) {
                LocalPoint localPoint = LocalPoint.fromWorld(Microbot.getClient(), node.position);
                Microbot.getWalker().walkFastLocal(localPoint);
                break;
            }
        }
    }

    private boolean handleTransports() {
        boolean skip = false;
        if (useTransport) {
            if (useCurrentTransport != null && Camera.isTileOnScreen(LocalPoint.fromWorld(Microbot.getClient(), target))) {
                //custom logic for stairce/ladders
                TileObject tileObject = Rs2GameObject.findObjectByLocation(this.target);
                Rs2GameObject.interact(tileObject, this.useCurrentTransport.getAction());
                int currentPlane = Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane();
                sleepUntil(() -> currentPlane != Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane());
                skip = true;
            } else {
                for (Node node : path.stream().collect(Collectors.toList())) {
                    if (Microbot.getClient().getLocalPlayer().getWorldLocation().equals(node.position)) continue;
                    Transport transport = getMatchingTransport(node.position);
                    if (transport == null) continue;
                    if (Camera.isTileOnScreen(LocalPoint.fromWorld(Microbot.getClient(), transport.origin))) {
                        TileObject tileObject = Rs2GameObject.findObjectByLocation(transport.origin);
                        Rs2GameObject.interact(tileObject);
                        sleepUntil(() -> Microbot.isAnimating());
                        sleepUntil(() -> !Microbot.isAnimating());
                        skip = true;
                        break;
                    }
                }
            }
        }
        return skip;
    }

    private boolean handleDoors() {
        if (useCanvas) return false;
        boolean skip = false;
        CheckedNode[] wallNodeArrays = wallNodes.toArray(CheckedNode[]::new);
        for (int i = 0; i < wallNodeArrays.length; i++) {
            final CheckedNode wallNode = wallNodeArrays[i];
            if (wallNode.node.position.equals(start)) continue;
            if (!path.stream().anyMatch(x -> x.position.equals(wallNode.node.position))) continue;
            if (Calculations.tileOnMap(wallNode.node.position) && wallNode.shape != null) {
                Microbot.getMouse().click(wallNode.shape.getBounds());
                sleepUntil(() -> Microbot.isWalking());
                sleepUntil(() -> !Microbot.isWalking());
                skip = true;
                break;
            }
        }
        return skip;
    }

    public Transport getClosestTransport(WorldPoint worldPoint) {
        WorldPoint transportKey = config.getTransports().keySet().stream().sorted(Comparator.comparingInt((WorldPoint w) -> w.distanceTo(worldPoint))).findFirst().orElse(null);
        List<Transport> matchingTransports = config.getTransports().values().stream()
                .filter(x -> x.get(0).getDestination() == transportKey)
                .findFirst().orElse(null);

        if (matchingTransports != null) {
            return matchingTransports.get(0);
        }
        return null;
    }

    public Transport getMatchingTransport(WorldPoint worldPoint) {
        List<Transport> matchingTransports = config.getTransports().get(worldPoint);
        if (matchingTransports == null || matchingTransports.isEmpty()) return null;
        Transport transportNode = matchingTransports.stream().findFirst().orElseGet(null);
        return transportNode;
    }

    public Transport getMatchingTransport(int plane) {
        Transport closestTransportNode = null;
        for (Map.Entry<WorldPoint, List<Transport>> entry : config.getTransports().entrySet()) {
            for (Transport transport : entry.getValue()) {
                //Search for all the transport objects that bring us on the same height
                if (transport.origin.getPlane() == plane && transport.destination.getPlane() != plane) {
                    if (closestTransportNode == null || (closestTransportNode.origin.distanceTo(target) > transport.destination.distanceTo(target))) {
                        //if the origin of the transport is on another plane, skip it
                        if (transport.getOrigin().getPlane() != Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane())
                            continue;
                        //if our target plane is greater than our players plane, we want to go up
                        if (target.getPlane() > Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane()) {
                            //if the transport plane is lower than the players current plane, skip this transport, because we want to go up
                            if (transport.getDestination().getPlane() < Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane())
                                continue;
                        } else {
                            //go down
                            //if the transport plane is higher than the players current plane, skip this transport, because we want to go down
                            if (transport.getDestination().getPlane() > Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane())
                                continue;
                        }
                        closestTransportNode = transport;
                    }
                }
            }
        }
        return closestTransportNode;
    }
}
