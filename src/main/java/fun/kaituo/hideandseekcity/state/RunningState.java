package fun.kaituo.hideandseekcity.state;

import com.destroystokyo.paper.ParticleBuilder;
import fun.kaituo.gameutils.GameUtils;
import fun.kaituo.gameutils.game.GameState;
import fun.kaituo.gameutils.util.Misc;
import fun.kaituo.hideandseekcity.HideAndSeekCity;
import fun.kaituo.hideandseekcity.util.GameCharacter;
import fun.kaituo.hideandseekcity.util.HiderData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RunningState implements GameState, Listener {

    public static RunningState INST = new RunningState();
    private UUID lastHiderUUID;

    private RunningState() {
    }

    private HideAndSeekCity game;
    private World world;

    public void init() {
        game = HideAndSeekCity.inst();
        mainObjective = game.getMainObjective();
        world = GameUtils.inst().getMainWorld();
    }

    private final Set<Integer> taskIds = new HashSet<>();
    private Objective mainObjective;
    private AtomicInteger catchTimeCountdown;

    @Override
    public void enter() {
        game.generateNewGameUUID();

        game.getKillCountMap().clear();
        for (Player p : game.getPlayers()) {
            addPlayer(p);
        }
        game.getProtocolManager().addPacketListener(game.getEquipmentPacketListener()); // hiders fully invisible
        Bukkit.getPluginManager().registerEvents(this, game);
        game.clearMainObjective();
        game.setMainObjectiveDisplay(HideAndSeekCity.MainObjectiveDisplay.PREPARE);
        AtomicInteger graceCountdown = new AtomicInteger(60);
        int graceCountdownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(game, () -> {
            mainObjective.getScore("prepare_time").setScore(graceCountdown.get());
            graceCountdown.getAndDecrement();
        }, 0, 20L);
        taskIds.add(graceCountdownTaskId);
        taskIds.add(Bukkit.getScheduler().runTaskLater(game, () -> {
            for (Player p : game.getHiders()) {
                p.getInventory().clear();
                p.sendActionBar(Component.text("你的伪装方块已被锁定！", NamedTextColor.RED));
            }
        }, 15 * 20L).getTaskId());
        taskIds.add(Bukkit.getScheduler().runTaskLater(game, () -> {
            for (Player player : game.getPlayers()) {
                taskIds.addAll(Misc.displayCountdown(player, 5, "§a搜寻者还有 %time% 秒解禁", "§e开始追捕！", game));
            }
        }, 55 * 20L).getTaskId());
        taskIds.add(Bukkit.getScheduler().runTaskLater(game, () -> {
            game.setMainObjectiveDisplay(HideAndSeekCity.MainObjectiveDisplay.CATCH);

            // player state update
            for (Player hider : game.getHiders()) {
                hider.getInventory().setItem(0, HideAndSeekCity.tauntItem);
                hider.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 1, false, false));
            }
            for (Player seeker : game.getSeekers()) {
                seeker.teleport(Objects.requireNonNull(game.getLoc("mapSpawn")));
                seeker.setRespawnLocation(game.getLoc("mapSpawn"), true);
                Objects.requireNonNull(game.getInv("seeker")).apply(seeker);
                seeker.getInventory().addItem(HideAndSeekCity.soundItem);
            }

            // clean grace countdown
            Bukkit.getScheduler().cancelTask(graceCountdownTaskId);
            taskIds.remove(graceCountdownTaskId);
            mainObjective.getScore("prepare_time").resetScore();
        }, 60 * 20L).getTaskId());
        catchTimeCountdown = new AtomicInteger(game.getGameTimeMinutes() * 60);
        int catchTimeCountdownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(game, () -> {
            mainObjective.getScore("remaining_time").setScore(catchTimeCountdown.get());
            catchTimeCountdown.getAndDecrement();
            HashMap<UUID, Integer> staminaMap = game.getSeekerStaminaMap();
            for (Map.Entry<UUID, Integer> entry : staminaMap.entrySet()) {
                if (entry.getValue() < 0) {
                    staminaMap.put(entry.getKey(), 0);
                } else if (entry.getValue() < 20) {
                    staminaMap.put(entry.getKey(), entry.getValue() + 1);
                } else {
                    staminaMap.put(entry.getKey(), 20);
                }
            }
        }, 60 * 20L, 20L);
        taskIds.add(catchTimeCountdownTaskId);

        taskIds.add(Bukkit.getScheduler().runTaskLater(game, () -> {
            if (game.getSeekers().size() == 1 && game.getHiders().size() >= 3) {
                Random random = new Random();
                int promoteIndex = random.nextInt(game.getHiders().size());
                Player promoted = game.getHiders().toArray(new Player[0])[promoteIndex];
                recruitSeeker(promoted);
            }
        }, 120 * 20L).getTaskId());

        taskIds.add(Bukkit.getScheduler().runTaskLater(game, () -> {
            for (Player player : game.getHiders()) {
                player.getInventory().addItem(HideAndSeekCity.revengeSwordItem);
            }
        }, 180 * 20L).getTaskId());
    }

    @Override
    public void exit() {
        // clean tasks
        List<Integer> taskIdsCopy = new ArrayList<>(taskIds);
        taskIds.clear();
        for (int i : taskIdsCopy) {
            Bukkit.getScheduler().cancelTask(i);
        }

        // clean scoreboards
        mainObjective.getScore("remaining_time").resetScore();
        mainObjective.getScore("remaining_hiders").resetScore();

        HandlerList.unregisterAll(this);

        // show win effects
        Component winMessage;
        if (!game.getHiders().isEmpty()) {
            winMessage = Component.text("躲藏者获胜！", NamedTextColor.GOLD);
        } else {
            winMessage = Component.text("搜寻者获胜！", NamedTextColor.GOLD);
        }

        HashMap<UUID, Integer> killCounts = game.getKillCountMap();
        List<Map.Entry<UUID, Integer>> topThree = killCounts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((e1, e2) -> e2.getValue() - e1.getValue())
                .limit(3).toList();

        for (Player p : game.getPlayers()) {
            p.showTitle(
                    Title.title(
                            winMessage,
                            Component.empty(),
                            Title.Times.times(
                                    Duration.ofMillis(5 * 50L),
                                    Duration.ofMillis(50 * 50L),
                                    Duration.ofMillis(5 * 50L)
                            )
                    )
            );
            Misc.spawnFireworks(p, game);
            p.getInventory().clear();
            p.clearActivePotionEffects();
            p.addPotionEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH, 1, 20));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 1, 20));
            p.sendMessage(
                    Component.text("=====", NamedTextColor.YELLOW).append(
                            Component.text("结果", NamedTextColor.AQUA).append(
                                    Component.text("=====", NamedTextColor.YELLOW))));
            p.sendMessage(Component.empty());
            p.sendMessage(winMessage);
            Component winners = Component.empty();
            if (!game.getHiders().isEmpty()) {
                for (Player hider : game.getHiders()) {
                    winners = winners.append(hider.name());
                    winners = winners.append(Component.text(" "));
                }
            } else {
                for (Player seeker : game.getSeekers()) {
                    if (seeker.getUniqueId().equals(lastHiderUUID)) continue;
                    winners = winners.append(seeker.name());
                    winners = winners.append(Component.text(" "));
                }
            }
            p.sendMessage(Component.text("获胜者：", NamedTextColor.GREEN).append(winners));
            p.sendMessage(Component.empty());
            p.sendMessage(Component.text("击杀数", NamedTextColor.RED));
            for (Map.Entry<UUID, Integer> entry : topThree) {
                p.sendMessage(
                        Component.text(
                                Objects.requireNonNull(Bukkit.getPlayer(entry.getKey())).getName(),
                                NamedTextColor.GOLD).append(
                                Component.text(" ").append(
                                        Component.text(entry.getValue(), NamedTextColor.GREEN)
                                )
                        )
                );
            }
        }

        // show hiders
        game.getHiderIds().forEach(uuid -> {
            BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(uuid);
            if (fakeBlockDisplay != null) {
                fakeBlockDisplay.remove();
            }
        });
        game.getHiderDataMap().forEach((id, data) -> {
            if (data.disguised) {
                Player hider = Bukkit.getPlayer(id);
                if (hider == null) return;
                game.getPlayers().forEach(
                        p1 -> p1.sendBlockChange(
                                hider.getLocation().getBlock().getLocation(),
                                Material.AIR.createBlockData()
                        )
                );
            }
        });

        // clean player maps
        game.clearTeams();
        game.clearPlayerMaps();
        game.getProtocolManager().removePacketListener(game.getEquipmentPacketListener());
        game.getKillCountMap().clear();
        Bukkit.getScheduler().runTaskLater(game, () -> {
            for (Player p : game.getPlayers()) {
                p.teleport(Objects.requireNonNull(game.getLoc("hub")));
            }
        }, 3 * 20L);
    }

    @Override
    public void tick() {
        for (Player hider : game.getHiders()) {
            UUID hiderId = hider.getUniqueId();
            Location hiderLoc = hider.getLocation();
            HiderData hiderData = game.getHiderDataMap().get(hiderId);
            Score sneakTime = game.getSneakTimeObjective().getScore(hider.getName());
            if (hider.isSneaking() && getDistance(hiderLoc, hiderData.previousLocation) <= 0.2 && !hiderData.disguised) {
                switch (sneakTime.getScore()) {
                    case 20 -> {
                        hider.sendActionBar(Component.text("⏺", NamedTextColor.RED)
                                .append(Component.text("⏺⏺⏺⏺", NamedTextColor.GRAY))
                        );
                        hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 0.8F);
                    }
                    case 40 -> {
                        hider.sendActionBar(Component.text("⏺⏺", NamedTextColor.RED)
                                .append(Component.text("⏺⏺⏺", NamedTextColor.GRAY))
                        );
                        hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.1F);
                    }
                    case 60 -> {
                        hider.sendActionBar(Component.text("⏺⏺⏺", NamedTextColor.YELLOW)
                                .append(Component.text("⏺⏺", NamedTextColor.GRAY))
                        );
                        hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.4F);
                    }
                    case 80 -> {
                        hider.sendActionBar(Component.text("⏺⏺⏺⏺", NamedTextColor.YELLOW)
                                .append(Component.text("⏺", NamedTextColor.GRAY))
                        );
                        hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 1.7F);
                    }
                    case 100 -> {
                        hider.sendActionBar(Component.text("⏺⏺⏺⏺⏺", NamedTextColor.GREEN));
                        hider.playSound(hiderLoc, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1F, 2F);
                        hiderData.disguised = true;
                        hiderData.replacedBlockMaterial = hider.getLocation().getBlock().getType();
                    }
                }
            } else {
                sneakTime.setScore(0);
            }
            if (hiderData.disguised) {
                hider.sendActionBar(Component.text("伪装中", Style.style(NamedTextColor.DARK_AQUA, TextDecoration.BOLD)));
                game.getPlayers().stream().filter(p -> !p.getUniqueId().equals(hiderId)).forEach(
                        p1 -> p1.sendBlockChange(
                                hiderLoc.getBlock().getLocation(),
                                hiderData.disguiseMaterial.createBlockData()
                        )
                );
            }
            BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(hiderId);
            if (fakeBlockDisplay == null) {
                fakeBlockDisplay = spawnFakeBlockDisplay(hider);
                hiderData.fakeBlockDisplayId = fakeBlockDisplay.getUniqueId();
            }
            BlockDisplay finalFakeBlockDisplay = fakeBlockDisplay;
            if (hiderData.disguised) {
                fakeBlockDisplay.teleport(offsetLocation(hiderLoc.getBlock().getLocation(), 0.00125, 0.00125, 0.00125));
                fakeBlockDisplay.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 0), new Vector3f(0.9975f, 0.9975f, 0.9975f), new AxisAngle4f(0, 0, 0, 0)));
                game.getPlayers().forEach(p -> {
                    if (!p.getUniqueId().equals(hiderId)) {
                        p.hideEntity(game, finalFakeBlockDisplay);
                    }
                });
            } else {
                fakeBlockDisplay.teleport(offsetLocation(removePitchYaw(hiderLoc), -0.5, 0, -0.5));
                game.getPlayers().forEach(p -> {
                    if (!p.getUniqueId().equals(hiderId)) {
                        p.showEntity(game, finalFakeBlockDisplay);
                    }
                });
            }

            hiderData.previousLocation = hiderLoc;
        }
        mainObjective.getScore("remaining_hiders").setScore(game.getHiders().size());
        for (Player p : game.getSeekers()) {
            int stamina = game.getSeekerStaminaMap().getOrDefault(p.getUniqueId(), 0);
            p.setExp(stamina / 20.01F);
            p.setLevel(stamina);
        }
        if (game.getHiders().isEmpty() || catchTimeCountdown.get() == 0) {
            game.setState(IdleState.INST);
            return;
        }
        if (game.getSeekers().isEmpty()) {
            if (game.getHiders().size() == 1) {
                game.setState(IdleState.INST);
            } else {
                Random random = new Random();
                int promoteIndex = random.nextInt(game.getHiders().size());
                Player promoted = game.getHiders().toArray(new Player[0])[promoteIndex];
                recruitSeeker(promoted);
            }
        }
    }

    private double getDistance(Location l1, Location l2) {
        return Math.sqrt(
                Math.pow(l1.getX() - l2.getX(), 2) +
                        Math.pow(l1.getY() - l2.getY(), 2) +
                        Math.pow(l1.getZ() - l2.getZ(), 2)
        );
    }

    private BlockDisplay getFakeBlockDisplay(UUID hiderId) {
        if (!game.getHiderIds().contains(hiderId)) {
            return null;
        }
        HiderData data = game.getHiderDataMap().get(hiderId);
        UUID fakeBlockDisplayId = data.fakeBlockDisplayId;
        return world.getEntitiesByClass(BlockDisplay.class)
                .stream()
                .filter(blockDisplay -> blockDisplay.getUniqueId().equals(fakeBlockDisplayId))
                .findFirst()
                .orElse(null);
    }

    private BlockDisplay spawnFakeBlockDisplay(Player hider) {
        Location hiderLoc = hider.getLocation();
        UUID hiderId = hider.getUniqueId();
        HiderData hiderData = game.getHiderDataMap().get(hiderId);
        BlockDisplay fakeBlockDisplay = (BlockDisplay) world.spawnEntity(hiderLoc, EntityType.BLOCK_DISPLAY);
        fakeBlockDisplay.setBlock(hiderData.disguiseMaterial.createBlockData());
        hiderData.fakeBlockDisplayId = fakeBlockDisplay.getUniqueId();
        return fakeBlockDisplay;
    }

    @SuppressWarnings("SameParameterValue")
    private Location offsetLocation(Location baseLocation, double dx, double dy, double dz) {
        return new Location(baseLocation.getWorld(), baseLocation.getX() + dx, baseLocation.getY() + dy, baseLocation.getZ() + dz);
    }

    private Location removePitchYaw(Location location) {
        return new Location(location.getWorld(), location.getX(), location.getY(), location.getZ());
    }

    private void recruitSeeker(Player player) {
        BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(player.getUniqueId());
        if (fakeBlockDisplay != null) {
            fakeBlockDisplay.remove();
        }
        player.getInventory().clear();
        if (game.getHiders().size() == 1) lastHiderUUID = player.getUniqueId();
        game.selectCharacter(player, GameCharacter.SEEKER);
        Objects.requireNonNull(game.getInv("seeker")).apply(player);
    }

    @Override
    public void addPlayer(Player player) {
        game.getKillCountMap().put(player.getUniqueId(), 0);
        if (game.getSeekers().contains(player)) {
            player.teleport(Objects.requireNonNull(game.getLoc("waitingRoom")));
            player.setRespawnLocation(Objects.requireNonNull(game.getLoc("waitingRoom")), true);
            game.getSeekerStaminaMap().put(player.getUniqueId(), 20);
        } else {
            // init hider data
            UUID hiderId = player.getUniqueId();
            HiderData hiderData = new HiderData();
            hiderData.previousLocation = game.getLoc("mapSpawn");
            hiderData.disguised = false;
            hiderData.disguiseMaterial = Material.SPRUCE_PLANKS;
            hiderData.fakeBlockDisplayId = null;    // will be modified later
            hiderData.replacedBlockMaterial = null; // will be modified later
            game.getHiderDataMap().put(hiderId, hiderData);

            player.teleport(Objects.requireNonNull(game.getLoc("mapSpawn")));
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 5 * 20, 4));
            player.setRespawnLocation(game.getLoc("mapSpawn"), true);
            Objects.requireNonNull(game.getInv("block_choose")).apply(player);
            player.sendTitlePart(TitlePart.TIMES, Title.Times.times(
                    Duration.ofMillis(500), Duration.ofMillis(5000), Duration.ofMillis(1000)
            ));
            player.sendActionBar(Component.text("你只有15秒来决定要伪装成的方块！", NamedTextColor.RED));
            player.sendTitlePart(TitlePart.TIMES, Title.Times.times(
                    Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000)
            ));
        }
    }

    @Override
    public void removePlayer(Player player) {

    }

    @Override
    public void forceStop() {

    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;
        if (!game.getPlayers().contains(player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK &&
                event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setCancelled(true);
            return;
        }
        if (!game.getHiders().contains(player)) return;
        if (!(event.getDamageSource().getCausingEntity() instanceof Player seeker)) return;
        if (!game.getSeekers().contains(seeker)) return;
        game.getSeekerStaminaMap().put(
                seeker.getUniqueId(),
                game.getSeekerStaminaMap().getOrDefault(seeker.getUniqueId(), 1) - 1
        );
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction().isLeftClick()) {
            // seeker attack logic (because hiders are, well, hidden!)
            Player seeker = event.getPlayer();
            if (!game.getSeekers().contains(seeker)) return;
            if (game.getSeekerStaminaMap().getOrDefault(seeker.getUniqueId(), 0) <= 0) return;
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                game.getSeekerStaminaMap().put(
                        seeker.getUniqueId(),
                        game.getSeekerStaminaMap().getOrDefault(seeker.getUniqueId(), 1) - 1
                );
            }
            Location seekerEyeLoc = seeker.getEyeLocation();
            org.bukkit.util.Vector rayTraceStart = seekerEyeLoc.toVector();
            Vector rayTraceDirection = seekerEyeLoc.getDirection();
            Player target = null;
            for (Player hider : game.getHiders()) {
                BoundingBox toBeChecked = hider.getBoundingBox();
                RayTraceResult result = toBeChecked.rayTrace(rayTraceStart, rayTraceDirection, 3.0);
                if (result != null) {
                    target = hider;
                    break;
                }
                HiderData hiderData = game.getHiderDataMap().get(hider.getUniqueId());
                if (hiderData.disguised) {
                    Location blockLoc = hider.getLocation().toBlockLocation();
                    toBeChecked = new BoundingBox(
                            blockLoc.x() - 0.00125, blockLoc.y() - 0.00125, blockLoc.z() - 0.00125,
                            blockLoc.x() + 1.00125, blockLoc.y() + 1.00125, blockLoc.z() + 1.00125
                    );
                } else {
                    BlockDisplay fbd = getFakeBlockDisplay(hider.getUniqueId());
                    if (fbd != null) {
                        toBeChecked = new BoundingBox(
                                fbd.getX(), fbd.getY(), fbd.getZ(),
                                fbd.getX() + 1, fbd.getY() + 1, fbd.getZ() + 1
                        );
                    } else continue;
                }
                result = toBeChecked.rayTrace(rayTraceStart, rayTraceDirection, 3.0);
                if (result != null) {
                    target = hider;
                    break;
                }
            }
            if (target == null) return;
            event.setCancelled(true);
            seeker.attack(target);
        } else if (event.getAction().isRightClick()) {
            // various props
            Player player = event.getPlayer();
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.equals(HideAndSeekCity.tauntItem) && game.getHiders().contains(player)) {
                // hiders taunt
                world.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.4F, 1.0F);
                new ParticleBuilder(Particle.NOTE).location(player.getEyeLocation()).count(1).receivers(game.getPlayers()).force(true).spawn();
                BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(player.getUniqueId());
                if (fakeBlockDisplay != null) {
                    fakeBlockDisplay.setGlowing(true);
                }
                taskIds.add(Bukkit.getScheduler().runTaskLater(game, () -> {
                    BlockDisplay fakeBlockDisplayAfter = getFakeBlockDisplay(player.getUniqueId());
                    if (fakeBlockDisplayAfter != null) {
                        fakeBlockDisplayAfter.setGlowing(false);
                    }
                }, 3 * 20L).getTaskId());
                int countdownNow = catchTimeCountdown.get();
                catchTimeCountdown.set(countdownNow < HideAndSeekCity.TAUNT_REDUCE_SECONDS ? 0 : countdownNow - HideAndSeekCity.TAUNT_REDUCE_SECONDS);
                player.getInventory().remove(HideAndSeekCity.tauntItem);
                taskIds.add(Bukkit.getScheduler().runTaskLater(game, () -> player.getInventory().addItem(HideAndSeekCity.tauntItem), HideAndSeekCity.TAUNT_COOLDOWN_SECONDS * 20L).getTaskId());
            } else if (item.equals(HideAndSeekCity.soundItem) && game.getSeekers().contains(player)) {
                // seekers make hiders make sound and particles
                game.getHiders().forEach(p1 -> world.playSound(p1.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.3F, 1.0F));
                game.getSeekers().forEach(p1 -> p1.getInventory().remove(HideAndSeekCity.soundItem));
                taskIds.add(Bukkit.getScheduler().runTaskLater(game, () -> game.getSeekers().forEach(p1 -> p1.getInventory().addItem(HideAndSeekCity.soundItem)), HideAndSeekCity.SOUND_COOLDOWN_SECONDS * 20L).getTaskId());
            } else if (game.getHiders().contains(player)) {
                // hiders change disguise material
                HiderData hiderData = game.getHiderDataMap().get(player.getUniqueId());
                List<Material> blockMaterials = List.of(
                        Material.SPRUCE_PLANKS,
                        Material.ANVIL,
                        Material.BEACON,
                        Material.DARK_PRISMARINE,
                        Material.OAK_LEAVES
                );
                if (blockMaterials.contains(item.getType())) {
                    hiderData.disguiseMaterial = item.getType();
                    BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(player.getUniqueId());
                    if (fakeBlockDisplay != null) fakeBlockDisplay.remove();
                }
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (!game.getHiders().contains(p)) return;
        HiderData hiderData = game.getHiderDataMap().get(p.getUniqueId());
        if (!hiderData.disguised) return;
        if (getDistance(event.getFrom(), event.getTo()) <= 0.1) return;
        Material originalMaterial;
        originalMaterial = Objects.requireNonNullElse(hiderData.replacedBlockMaterial, Material.AIR);
        game.getPlayers().forEach(
                p1 -> p1.sendBlockChange(
                        p.getLocation().getBlock().getLocation(),
                        originalMaterial.createBlockData()
                )
        );
        hiderData.disguised = false;
        BlockDisplay fakeBlockDisplay = getFakeBlockDisplay(p.getUniqueId());
        if (fakeBlockDisplay != null) {
            fakeBlockDisplay.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 0), new Vector3f(0.999f, 0.999f, 0.999f), new AxisAngle4f(0, 0, 0, 0)));
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getPlayer();
        if (game.getSeekers().contains(p)) {
            final Location location = p.getRespawnLocation();
            assert location != null;
            int seekerStuckTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(game, () -> {
                p.teleport(location);
            }, 0, 1);
            taskIds.add(seekerStuckTaskId);
            taskIds.add(Bukkit.getScheduler().runTaskLater(game, () -> Bukkit.getScheduler().cancelTask(seekerStuckTaskId), 3 * 20L).getTaskId());
        } else if (game.getHiders().contains(p)) {
            DamageSource damageSource = event.getDamageSource();
            if (damageSource.getDamageType() == DamageType.PLAYER_ATTACK) {
                Player attacker = (Player) damageSource.getCausingEntity();
                assert attacker != null;
                game.getKillCountMap().put(attacker.getUniqueId(), game.getKillCountMap().get(attacker.getUniqueId()) + 1);
            }
            recruitSeeker(p);
        }
    }

    @EventHandler
    public void onPlayerInventoryClick(InventoryClickEvent event) {
        event.setCancelled(true);
    }
}
