package com.bergerkiller.bukkit.tc.controller;

import com.bergerkiller.bukkit.common.Timings;
import com.bergerkiller.bukkit.common.ToggledState;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.bases.mutable.VectorAbstract;
import com.bergerkiller.bukkit.common.config.ConfigurationNode;
import com.bergerkiller.bukkit.common.entity.CommonEntity;
import com.bergerkiller.bukkit.common.entity.type.CommonMinecart;
import com.bergerkiller.bukkit.common.inventory.ItemParser;
import com.bergerkiller.bukkit.common.inventory.MergedInventory;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.LogicUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.EntityTracker;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet.LongIterator;
import com.bergerkiller.bukkit.tc.exception.GroupUnloadedException;
import com.bergerkiller.bukkit.tc.exception.MemberMissingException;
import com.bergerkiller.bukkit.tc.TCConfig;
import com.bergerkiller.bukkit.tc.TCTimings;
import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.Util;
import com.bergerkiller.bukkit.tc.attachments.animation.Animation;
import com.bergerkiller.bukkit.tc.attachments.animation.AnimationOptions;
import com.bergerkiller.bukkit.tc.cache.RailMemberCache;
import com.bergerkiller.bukkit.tc.controller.components.ActionTrackerGroup;
import com.bergerkiller.bukkit.tc.controller.components.AnimationController;
import com.bergerkiller.bukkit.tc.controller.components.AttachmentControllerGroup;
import com.bergerkiller.bukkit.tc.controller.components.SignTrackerGroup;
import com.bergerkiller.bukkit.tc.controller.components.SpeedAheadWaiter;
import com.bergerkiller.bukkit.tc.controller.components.RailTrackerGroup;
import com.bergerkiller.bukkit.tc.controller.type.MinecartMemberChest;
import com.bergerkiller.bukkit.tc.controller.type.MinecartMemberFurnace;
import com.bergerkiller.bukkit.tc.events.*;
import com.bergerkiller.bukkit.tc.properties.CartPropertiesStore;
import com.bergerkiller.bukkit.tc.properties.IPropertiesHolder;
import com.bergerkiller.bukkit.tc.properties.TrainProperties;
import com.bergerkiller.bukkit.tc.properties.TrainPropertiesStore;
import com.bergerkiller.bukkit.tc.properties.standard.type.SlowdownMode;
import com.bergerkiller.bukkit.tc.storage.OfflineGroupManager;
import com.bergerkiller.bukkit.tc.utils.ChunkArea;
import com.bergerkiller.bukkit.tc.utils.TrackWalkingPoint;
import com.bergerkiller.generated.net.minecraft.world.level.chunk.ChunkHandle;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class MinecartGroup extends MinecartGroupStore implements IPropertiesHolder, AnimationController {
    private static final long serialVersionUID = 3;
    private static final LongHashSet chunksBuffer = new LongHashSet(50);
    protected final ToggledState ticked = new ToggledState();
    protected final ChunkArea chunkArea = new ChunkArea();
    private boolean chunkAreaValid = false;
    private final SignTrackerGroup signTracker = new SignTrackerGroup(this);
    private final RailTrackerGroup railTracker = new RailTrackerGroup(this);
    private final ActionTrackerGroup actionTracker = new ActionTrackerGroup(this);
    private final SpeedAheadWaiter speedAheadWaiter = new SpeedAheadWaiter(this);
    private final AttachmentControllerGroup attachmentController = new AttachmentControllerGroup(this);
    protected long lastSync = Long.MIN_VALUE;
    private TrainProperties prop = null;
    private boolean breakPhysics = false;
    private int teleportImmunityTick = 0;
    private double updateSpeedFactor = 1.0;
    private int updateStepCount = 1;
    private int updateStepNr = 1;
    private boolean unloaded = false;

    protected MinecartGroup() {
        this.ticked.set();
    }

    @Override
    public TrainProperties getProperties() {
        if (this.prop == null) {
            if (this.isUnloaded()) {
                throw new IllegalStateException("Group is unloaded");
            }
            this.prop = TrainPropertiesStore.create();
            for (MinecartMember<?> member : this) {
                this.prop.add(member.getProperties());
            }
            TrainPropertiesStore.bindGroupToProperties(this.prop, this);
        }
        return this.prop;
    }

    public void setProperties(TrainProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Can not set properties to null");
        }
        if (this.isUnloaded()) {
            throw new IllegalStateException("Group is unloaded");
        }
        if (this.prop == properties) {
            return;
        }
        if (this.prop != null) {
            TrainPropertiesStore.remove(this.prop.getTrainName());
            TrainPropertiesStore.unbindGroupFromProperties(this.prop, this);
        }
        this.prop = properties;
        TrainPropertiesStore.bindGroupToProperties(this.prop, this);
    }

    /**
     * Saves the properties of this train, preserving information such the order of the carts
     * and the orientation of each cart. Owner information is stripped.
     * 
     * @return configuration useful for saving as a train
     */
    public ConfigurationNode saveConfig() {
        // Save train properties getConfig() to a new configuration node copy
        // Omit cart details, overwrite with the member configurations
        ConfigurationNode savedConfig = this.getProperties().saveToConfig().clone();
        savedConfig.remove("carts");
        savedConfig.setNodeList("carts", this.stream().map(MinecartMember::saveConfig).collect(Collectors.toList()));
        return savedConfig;
    }

    public SignTrackerGroup getSignTracker() {
        return this.signTracker;
    }

    /**
     * Gets the Action Tracker that keeps track of the actions of this Group
     *
     * @return action tracker
     */
    public ActionTrackerGroup getActions() {
        return this.actionTracker;
    }

    /**
     * Gets the Rail Tracker that keeps track of the rails this train occupies.
     * 
     * @return rail tracker
     */
    public RailTrackerGroup getRailTracker() {
        return this.railTracker;
    }

    /**
     * Gets the attachment controller for this group. This controller manages
     * the (synchronized) updates of all carts of the train.
     *
     * @return group attachment controller
     */
    public AttachmentControllerGroup getAttachments() {
        return this.attachmentController;
    }

    public MinecartMember<?> head(int index) {
        return this.get(index);
    }

    public MinecartMember<?> head() {
        return this.head(0);
    }

    public MinecartMember<?> tail(int index) {
        return this.get(this.size() - 1 - index);
    }

    public MinecartMember<?> tail() {
        return this.tail(0);
    }

    public MinecartMember<?> middle() {
        return this.get((int) Math.floor((double) size() / 2));
    }

    public Iterator<MinecartMember<?>> iterator() {
        final Iterator<MinecartMember<?>> listIter = super.iterator();
        return new Iterator<MinecartMember<?>>() {
            @Override
            public boolean hasNext() {
                return listIter.hasNext();
            }

            @Override
            public MinecartMember<?> next() {
                try {
                    return listIter.next();
                } catch (ConcurrentModificationException ex) {
                    throw new MemberMissingException();
                }
            }

            @Override
            public void remove() {
                listIter.remove();
            }
        };
    }

    public MinecartMember<?>[] toArray() {
        return super.toArray(new MinecartMember<?>[0]);
    }

    public boolean connect(MinecartMember<?> contained, MinecartMember<?> with) {
        if (this.size() <= 1) {
            this.add(with);
        } else if (this.head() == contained && this.canConnect(with, 0)) {
            this.add(0, with);
        } else if (this.tail() == contained && this.canConnect(with, this.size() - 1)) {
            this.add(with);
        } else {
            return false;
        }
        return true;
    }

    // Called before addMember to fire the MemberAddEvent
    // Sets the group to this group before adding to avoid problems
    // when .getGroup() is called on the member to query the previous group.
    private void addMemberPreEvent(MinecartMember<?> member) {
        boolean wasGroupNull = false;
        if (member.group == null) {
            member.group = this;
            wasGroupNull = true;
        }
        CommonUtil.callEvent(new MemberAddEvent(member, this));
        if (wasGroupNull && member.group == this) {
            member.group = null;
        }
    }

    private void addMember(MinecartMember<?> member) {
        this.chunkAreaValid = false;
        notifyPhysicsChange();
        member.setGroup(this);
        this.getSignTracker().updatePosition();
        this.getProperties().add(member.getProperties());
    }

    public void add(int index, MinecartMember<?> member) {
        if (member.isUnloaded()) {
            throw new IllegalArgumentException("Can not add unloaded members to groups");
        }
        super.add(index, member);
        this.addMemberPreEvent(member);
        this.addMember(member);
    }

    public boolean add(MinecartMember<?> member) {
        if (member.isUnloaded()) {
            throw new IllegalArgumentException("Can not add unloaded members to groups");
        }
        super.add(member);
        this.addMemberPreEvent(member);
        this.addMember(member);
        return true;
    }

    public boolean addAll(int index, Collection<? extends MinecartMember<?>> members) {
        super.addAll(index, members);
        MinecartMember<?>[] memberArr = members.toArray(new MinecartMember<?>[0]);
        for (MinecartMember<?> m : memberArr) {
            if (m.isUnloaded()) {
                throw new IllegalArgumentException("Can not add unloaded members to groups");
            }
            this.addMemberPreEvent(m);
        }
        for (MinecartMember<?> member : memberArr) {
            this.addMember(member);
        }
        return true;
    }

    public boolean addAll(Collection<? extends MinecartMember<?>> members) {
        super.addAll(members);
        MinecartMember<?>[] memberArr = members.toArray(new MinecartMember<?>[0]);
        for (MinecartMember<?> m : memberArr) {
            if (m.isUnloaded()) {
                throw new IllegalArgumentException("Can not add unloaded members to groups");
            }
            this.addMemberPreEvent(m);
        }
        for (MinecartMember<?> member : memberArr) {
            this.addMember(member);
        }
        return true;
    }

    public boolean containsIndex(int index) {
        return !this.isEmpty() && (index >= 0 && index < this.size());
    }

    @Override
    public World getWorld() {
        return isEmpty() ? null : get(0).getWorld();
    }

    public int size(EntityType carttype) {
        int rval = 0;
        for (MinecartMember<?> mm : this) {
            if (mm.getEntity().getType() == carttype) {
                rval++;
            }
        }
        return rval;
    }

    public boolean isValid() {
        return !this.isEmpty() && (this.size() == 1 || !this.getProperties().isPoweredMinecartRequired() || this.size(EntityType.MINECART_FURNACE) > 0);
    }

    /**
     * Removes a member without splitting the train or causing link effects
     *
     * @param member to remove
     * @return True if removed, False if not
     */
    public boolean removeSilent(MinecartMember<?> member) {
        int index = this.indexOf(member);
        if (index == -1) {
            return false;
        }
        this.removeMember(index);
        if (this.isEmpty()) {
            this.remove();
        }
        return true;
    }

    public boolean remove(Object o) {
        int index = this.indexOf(o);
        return index != -1 && this.remove(index) != null;
    }

    private MinecartMember<?> removeMember(int index) {
        this.chunkAreaValid = false;
        notifyPhysicsChange();
        MinecartMember<?> member = super.get(index);
        MemberRemoveEvent.call(member);
        super.remove(index);
        this.getActions().removeActions(member);
        this.getSignTracker().updatePosition();
        onMemberRemoved(member);
        member.group = null;
        return member;
    }

    private void onMemberRemoved(MinecartMember<?> member) {
        this.getProperties().remove(member.getProperties());
        this.getRailTracker().removeMemberRails(member);
        try (Timings t = TCTimings.RAILMEMBERCACHE.start()) {
            RailMemberCache.remove(member);
        }
    }

    public MinecartMember<?> remove(int index) {
        MinecartMember<?> removed = this.removeMember(index);
        if (this.isEmpty()) {
            //Remove empty group as a result
            this.remove();
        } else {
            //Split the train at the index
            if (TCConfig.playHissWhenCartRemoved) {
                removed.playLinkEffect();
            }
            this.split(index);
        }
        return removed;
    }

    /**
     * Splits this train, the index is the first cart for the new group<br><br>
     * <p/>
     * For example, this Group has a total cart count of 5<br>
     * If you then split at index 2, it will result in:<br>
     * - This group becomes a group of 2 carts<br>
     * - A new group of 3 carts is created
     */
    public MinecartGroup split(int at) {
        Util.checkMainThread("MinecartGroup::split()");
        if (at <= 0) return this;
        if (at >= this.size()) return null;

        // Remove carts split off and create a new group using them
        MinecartGroup gnew;
        {
            List<MinecartMember<?>> splitMembers = new ArrayList<>();
            int count = this.size();
            for (int i = at; i < count; i++) {
                splitMembers.add(this.removeMember(this.size() - 1));
            }
            gnew = MinecartGroupStore.createSplitFrom(this.getProperties(),
                    splitMembers.toArray(new MinecartMember[0]));
        }

        //Remove this train if now empty
        if (!this.isValid()) {
            this.remove();
        }
        //Remove if empty or not allowed, else add
        return gnew;
    }

    @Override
    public void clear() {
        this.getSignTracker().clear();
        this.getActions().clear();

        final TrainProperties properties = this.getProperties();
        for (MinecartMember<?> mm : this.toArray()) {
            properties.remove(mm.getProperties());
            if (mm.getEntity().isDead()) {
                mm.onDie(true);
            } else {
                // Unassign member from previous group
                mm.group = null;

                // Create and assign a new group to this member with the properties already created earlier
                mm.group = MinecartGroupStore.createSplitFrom(properties, mm);
            }
        }
        super.clear();
    }

    public void remove() {
        Util.checkMainThread("MinecartGroup::remove()");
        if (!groups.remove(this)) {
            return; // Already removed
        }

        GroupRemoveEvent.call(this);
        this.clear();
        this.updateChunkInformation();
        this.chunkArea.reset();
        if (this.prop != null) {
            TrainPropertiesStore.remove(this.prop.getTrainName());
            TrainPropertiesStore.unbindGroupFromProperties(this.prop, this);
            this.prop = null;
        }
    }

    public void destroy() {
        List<MinecartMember<?>> copy = new ArrayList<MinecartMember<?>>(this);
        for (MinecartMember<?> mm : copy) {
            mm.getEntity().remove();
        }
        this.remove();
    }

    /**
     * Whether this group has been unloaded. This means members of this group can no longer be addressed
     * and methods and properties of this group are unreliable.
     * 
     * @return True if unloaded
     */
    public boolean isUnloaded() {
        return this.unloaded;
    }

    /**
     * Unloads this group, saving it in offline storage for later reloading. Does nothing if already unloaded.
     */
    public void unload() {
        // If already unloaded, do nothing
        if (this.unloaded) {
            return;
        }

        // Protect.
        Util.checkMainThread("MinecartGroup::unload()");
        this.unloaded = true;

        // Undo partial-unloading before calling the event
        for (MinecartMember<?> member : this) {
            member.group = this;
            member.setUnloaded(false);
        }

        // Event
        GroupUnloadEvent.call(this);

        // Unload in detector regions
        getSignTracker().unload();

        // Remove from member-by-rail cache
        getRailTracker().unload();

        // Store the group offline
        OfflineGroupManager.storeGroup(this);

        // Unload
        this.stop(true);
        groups.remove(this);
        for (MinecartMember<?> member : this) {
            member.group = null;
            member.unloadedLastPlayerTakable = this.getProperties().isPlayerTakeable();
            member.setUnloaded(true);

            // We must correct position here, because it will no longer be ticked!
            member.getEntity().doPostTick();
        }

        // Clear group members and disable this group further
        super.clear();

        if (this.prop != null) {
            TrainPropertiesStore.unbindGroupFromProperties(this.prop, this);
        }
        this.prop = null;
    }

    /**
     * Visually respawns this minecart to avoid teleportation smoothing
     */
    public void respawn() {
        for (MinecartMember<?> mm : this) {
            mm.respawn();
        }
    }

    public void playLinkEffect() {
        for (MinecartMember<?> mm : this) {
            mm.playLinkEffect();
        }
    }

    public void stop() {
        this.stop(false);
    }

    public void stop(boolean cancelLocationChange) {
        for (MinecartMember<?> m : this) {
            m.stop(cancelLocationChange);
        }
    }

    public void limitSpeed() {
        for (MinecartMember<?> mm : this) {
            mm.limitSpeed();
        }
    }

    public void eject() {
        for (MinecartMember<?> mm : this) mm.eject();
    }

    /**
     * A simple version of teleport where the inertia of the train is maintained
     */
    public void teleportAndGo(Block start, BlockFace direction) {
        double force = this.getAverageForce();
        this.teleport(start, direction);
        this.stop();
        this.getActions().clear();
        if (Math.abs(force) > 0.01) {
            this.tail().getActions().addActionLaunch(direction, 1.0, force);
        }
    }

    public void teleport(Block start, BlockFace direction) {
        Location[] locations = new Location[this.size()];
        TrackWalkingPoint walker = new TrackWalkingPoint(start, direction);
        for (int i = 0; i < locations.length; i++) {
            boolean canMove;
            if (i == 0) {
                canMove = walker.move(0.0);
            } else {
                canMove = walker.move(get(i - 1).getPreferredDistance(get(i)));
            }
            if (canMove) {
                locations[i] = walker.state.positionLocation();
            } else if (i > 0) {
                locations[i] = locations[i - 1].clone();
            } else {
                return; // Failed!
            }
        }
        this.teleport(locations, true);
    }

    public void teleport(Location[] locations) {
        this.teleport(locations, false);
    }

    public void teleport(Location[] locations, boolean reversed) {
        if (LogicUtil.nullOrEmpty(locations) || locations.length != this.size()) {
            return;
        }
        this.teleportImmunityTick = 10;
        this.getSignTracker().clear();
        this.getSignTracker().updatePosition();
        this.breakPhysics();

        // If world change, perform a standard teleport
        // If on same world, despawn by stopping tracking, teleport, then reset network controllers
        // This eliminates the teleporting motion
        boolean resetNetworkControllers = (locations[0].getWorld() == this.get(0).getWorld());
        if (resetNetworkControllers) {
            EntityTracker tracker = WorldUtil.getTracker(locations[0].getWorld());
            for (MinecartMember<?> member : this) {
                tracker.stopTracking(member.getEntity().getEntity());
            }
        }

        if (reversed) {
            for (int i = 0; i < locations.length; i++) {
                teleportMember(this.get(i), locations[locations.length - i - 1]);
            }
        } else {
            for (int i = 0; i < locations.length; i++) {
                teleportMember(this.get(i), locations[i]);
            }
        }
        this.updateDirection();
        this.updateChunkInformation();
        this.updateWheels();
        this.getSignTracker().updatePosition();

        // Respawn
        if (resetNetworkControllers) {
            for (MinecartMember<?> member : this) {
                member.getEntity().setNetworkController(MinecartMemberStore.createNetworkController());
            }
        }
    }

    private void teleportMember(MinecartMember<?> member, Location location) {
        member.ignoreDie.set();
        if (member.isOrientationInverted()) {
            location = Util.invertRotation(location.clone());
        }
        member.getWheels().startTeleport();
        member.getEntity().teleport(location);
        member.ignoreDie.clear();
    }

    /**
     * Gets whether this Minecart and the passenger has immunity as a result of teleportation
     *
     * @return True if it is immune, False if not
     */
    public boolean isTeleportImmune() {
        return this.teleportImmunityTick > 0;
    }

    public void shareForce() {
        double f = this.getAverageForce();
        for (MinecartMember<?> m : this) {
            m.setForwardForce(f);
        }
    }

    public void setForwardForce(double force) {
        for (MinecartMember<?> mm : this) {
            final double currvel = mm.getForce();
            if (currvel <= 0.01 || Math.abs(force) < 0.01) {
                mm.setForwardForce(force);
            } else {
                mm.getEntity().vel.multiply(force / currvel);
            }
        }

        /*
        final double currvel = this.head().getForce();
        if (currvel <= 0.01 || Math.abs(force) < 0.01) {
            for (MinecartMember<?> mm : this) {
                mm.setForwardForce(force);
            }
        } else {
            final double f = force / currvel;
            for (MinecartMember<?> mm : this) {
                mm.getEntity().vel.multiply(f);
            }
        }
        */

    }

    @Override
    public List<String> GetAnimationNames() {
        if (this.isEmpty()) {
            return Collections.emptyList();
        } else if (this.size() == 1) {
            return this.get(0).GetAnimationNames();
        } else {
            return Collections.unmodifiableList(this.stream()
                    .flatMap(m -> m.GetAnimationNames().stream())
                    .distinct()
                    .collect(Collectors.toList()));
        }
    }

    /**
     * Plays an animation by name for this train
     * 
     * @param name of the animation
     * @return True if an animation was started for one or more minecarts in this train
     */
    @Override
    public boolean playNamedAnimation(String name) {
        return AnimationController.super.playNamedAnimation(name);
    }

    /**
     * Plays an animation using the animation options specified for this train
     * 
     * @param options for the animation
     * @return True if an animation was started for one or more minecarts in this train
     */
    @Override
    public boolean playNamedAnimation(AnimationOptions options) {
        boolean success = false;
        for (MinecartMember<?> member : this) {
            success |= member.playNamedAnimation(options);
        }
        return success;
    }

    @Override
    public boolean playNamedAnimationFor(int[] targetPath, AnimationOptions options) {
        boolean success = false;
        for (MinecartMember<?> member : this) {
            success |= member.playNamedAnimationFor(targetPath, options);
        }
        return success;
    }

    @Override
    public boolean playAnimationFor(int[] targetPath, Animation animation) {
        boolean success = false;
        for (MinecartMember<?> member : this) {
            success |= member.playAnimationFor(targetPath, animation);
        }
        return success;
    }

    public boolean canConnect(MinecartMember<?> mm, int at) {
        if (this.size() == 1) return true;
        if (this.size() == 0) return false;
        CommonMinecart<?> connectedEnd;
        CommonMinecart<?> otherEnd;
        if (at == 0) {
            // Compare the head
            if (!this.head().isNearOf(mm)) {
                return false;
            }
            connectedEnd = this.head().getEntity();
            otherEnd = this.tail().getEntity();
        } else if (at == this.size() - 1) {
            //compare the tail
            if (!this.tail().isNearOf(mm)) {
                return false;
            }
            connectedEnd = this.tail().getEntity();
            otherEnd = this.head().getEntity();
        } else {
            return false;
        }
        // Verify connected end is closer than the opposite end of this Train
        // This ensures that no wrongful connections are made in curves
        return connectedEnd.loc.distanceSquared(mm.getEntity()) < otherEnd.loc.distanceSquared(mm.getEntity());
    }

    /**
     * Refreshes rail information when physics occurred since the last time {@link #refreshRailTrackerIfChanged()}
     * was called. Physics can be notified using {@link #notifyPhysicsChange()}. In addition,
     * this method checks whether the physics position of the train was changed since the last time
     * this method was called.
     */
    private void refreshRailTrackerIfChanged() {
        // Go by all the Minecarts and check whether the position since last time has changed
        for (MinecartMember<?> member : this) {
            hasPhysicsChanges |= member.railDetectPositionChange();
        }

        // If changed, reset and refresh rails
        if (hasPhysicsChanges) {
            hasPhysicsChanges = false;
            this.getRailTracker().refresh();
        }
    }

    public void updateDirection() {
        try (Timings t = TCTimings.GROUP_UPDATE_DIRECTION.start()) {
            if (this.size() == 1) {
                this.refreshRailTrackerIfChanged();
                this.head().updateDirection();
            } else if (this.size() > 1) {
                int reverseCtr = 0;
                while (true) {
                    this.refreshRailTrackerIfChanged();

                    // Update direction of individual carts
                    for (MinecartMember<?> member : this) {
                        member.updateDirection();
                    }

                    // Handle train reversing (with maximum 2 attempts)
                    if (reverseCtr++ == 2) {
                        break;
                    }
                    double fforce = 0;
                    for (MinecartMember<?> m : this) {
                        // Use rail tracker instead of recalculating for improved performance
                        // fforce += m.getForwardForce();

                        VectorAbstract vel = m.getEntity().vel;
                        fforce += m.getRailTracker().getState().position().motDot(vel.getX(), vel.getY(), vel.getZ());
                    }
                    if (fforce >= 0) {
                        break;
                    } else {
                        reverseDataStructures();
                        notifyPhysicsChange();
                    }
                }
            }
        }
    }

    public void reverse() {
        // Reverse current movement direction for each individual cart
        for (MinecartMember<?> mm : this) {
            mm.reverseDirection();
        }

        // Reverses train data structures so head becomes tail
        reverseDataStructures();

        // With velocity at 0, updateDirection() would (falsely) assume there are no changes
        // Just to make sure we always recalculate the rails, force an update
        notifyPhysicsChange();

        // Must be re-calculated since this alters the path the train takes
        this.updateDirection();
    }

    // Reverses all structures that store information sorted from head to tail
    // This is done when the direction of the train changes
    private void reverseDataStructures() {
        Collections.reverse(this);
        this.getRailTracker().reverseRailData();
    }

    // Refresh wheel position information, important to do it AFTER updateDirection()
    private void updateWheels() {
        for (MinecartMember<?> member : this) {
            try (Timings t = TCTimings.MEMBER_PHYSICS_UPDATE_WHEELS.start()) {
                member.getWheels().update();
            }
        }
    }

    /**
     * Gets the average speed/force of this train. Airbound Minecarts are exempt
     * from the average. See also:
     * {@link com.bergerkiller.bukkit.tc.rails.logic.RailLogic#hasForwardVelocity(member) RailLogic.hasForwardVelocity(member)}
     * 
     * @return average (forward) force
     */
    public double getAverageForce() {
        if (this.isEmpty()) {
            return 0;
        }
        if (this.size() == 1) {
            return this.get(0).getForce();
        }
        //Get the average forward force of all carts
        double force = 0;
        for (MinecartMember<?> m : this) {
            force += m.getForwardForce();
        }
        return force / (double) size();
    }

    public List<Material> getTypes() {
        ArrayList<Material> types = new ArrayList<>(this.size());
        for (MinecartMember<?> mm : this) {
            types.add(mm.getEntity().getCombinedItem());
        }
        return types;
    }

    public boolean hasPassenger() {
        for (MinecartMember<?> mm : this) {
            if (mm.getEntity().hasPassenger()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasFuel() {
        for (MinecartMember<?> mm : this) {
            if (mm instanceof MinecartMemberFurnace && ((MinecartMemberFurnace) mm).getEntity().hasFuel()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasItems() {
        for (MinecartMember<?> mm : this) {
            if (mm instanceof MinecartMemberChest && ((MinecartMemberChest) mm).hasItems()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasItem(ItemParser item) {
        for (MinecartMember<?> mm : this) {
            if (mm instanceof MinecartMemberChest && ((MinecartMemberChest) mm).hasItem(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean isMoving() {
        return !this.isEmpty() && this.head().isMoving();
    }

    /**
     * Checks if this Minecart Group can unload, or if chunks are kept loaded instead<br>
     * The keepChunksLoaded property is read, as well the moving state if configured<br>
     * If a player is inside the train, it will keep the chunks loaded as well
     *
     * @return True if it can unload, False if it keeps chunks loaded
     */
    public boolean canUnload() {
        if (this.getProperties().isKeepingChunksLoaded()) {
            if (!TCConfig.keepChunksLoadedOnlyWhenMoving || this.isMoving()) {
                return false;
            }
        }
        for (MinecartMember<?> member : this) {
            if (member.getEntity() != null && member.getEntity().hasPlayerPassenger()) {
                return false;
            }
        }
        return !this.isTeleportImmune();
    }

    public boolean isRemoved() {
        return !groups.contains(this);
    }

    /**
     * Gets an inventory view of all the items in the carts of this train
     *
     * @return cart inventory view
     */
    public Inventory getInventory() {
        Inventory[] source = this.stream()
                .map(MinecartMember::getEntity)
                .map(CommonEntity::getEntity)
                .filter(e -> e instanceof InventoryHolder)
                .map(e -> ((InventoryHolder) e).getInventory())
                .toArray(Inventory[]::new);
        return new MergedInventory(source);
    }

    /**
     * Gets an inventory view of all players inside all carts of this train
     *
     * @return player inventory view
     */
    public Inventory getPlayerInventory() {
        Inventory[] source = this.stream().flatMap(m -> m.getEntity().getPlayerPassengers().stream())
                     .map(Player::getInventory)
                     .toArray(Inventory[]::new);
        return new MergedInventory(source);
    }

    public void keepChunksLoaded(boolean keepLoaded) {
        for (ChunkArea.OwnedChunk chunk : this.chunkArea.getAll()) {
            chunk.keepLoaded(keepLoaded);
        }
    }

    /**
     * Gets the chunk area around this train. This is the area kept loaded if chunks are kept loaded,
     * or the chunks that when unloaded, will cause the train to unload if not.
     * 
     * @return chunk area
     */
    public ChunkArea getChunkArea() {
        return this.chunkArea;
    }

    public boolean isInChunk(World world, long chunkLongCoord) {
        if (this.getWorld() != world) {
            return false;
        }

        if (this.chunkAreaValid) {
            return this.chunkArea.containsChunk(chunkLongCoord);
        } else {
            // Slow calculation as a fallback when the chunkArea is outdated
            int center_chunkX = MathUtil.longHashMsw(chunkLongCoord);
            int center_chunkZ = MathUtil.longHashLsw(chunkLongCoord);
            LongIterator chunkIter = this.loadChunksBuffer().longIterator();
            while (chunkIter.hasNext()) {
                long chunk = chunkIter.next();
                if (Math.abs(MathUtil.longHashMsw(chunk) - center_chunkX) <= 2 &&
                    Math.abs(MathUtil.longHashLsw(chunk) - center_chunkZ) <= 2)
                {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public void onPropertiesChanged() {
        this.getSignTracker().update();
        for (MinecartMember<?> member : this.toArray()) {
            member.onPropertiesChanged();
        }
    }

    /**
     * Gets the maximum amount of ticks a member of this group has lived
     *
     * @return maximum amount of lived ticks
     */
    public int getTicksLived() {
        int ticksLived = 0;
        for (MinecartMember<?> member : this) {
            ticksLived = Math.max(ticksLived, member.getEntity().getTicksLived());
        }
        return ticksLived;
    }

    /**
     * Gets the speed factor that is applied to all velocity and movement updates in the current update.<br>
     * <br>
     * <b>Explanation:</b><br>
     * When a train moves faster than 0.4 blocks/tick, the update is split into several update steps per tick.
     * This prevents nasty derailing and makes sure that block-by-block motion can still occur. In a single tick
     * the train moves 5 blocks, which is done by doing 8 or so actual update steps. The update speed factor
     * specifies the multiplier to apply to speeds for the current update.<br>
     * <br>
     * When moving 0.4 b/t and under, this value will always be 1.0 (one update). Above it, it will be
     * set to an increasingly small number 1/stepcount. Outside of the physics function, the factor will always be 1.0.<br>
     * <br>
     * <b>When to use</b><br>
     * This factor should only be used when applying an absolute velocity. For example, when
     * a launcher sign uses a certain desired velocity, this speed factor must be used to make sure it is correctly applied.
     * Say we want a speed of "2.4", and the update is split in 6 (f=0.1666), we should apply <i>2.4*0.1666=0.4</i>. When all
     * updates finish, the velocities are corrected and will be set to the 2.4 that was requested.<br>
     * <br>
     * However, when a velocity is taken over from inside the physics loop, this factor should <b>not</b> be used.
     * For example, if you want to do <i>velocity = velocity * 0.95</i> the original velocity is already factored,
     * and no update speed factor should be applied again.
     * 
     * @return Update speed factor
     */
    public double getUpdateSpeedFactor() {
        return this.updateSpeedFactor;
    }

    /**
     * Gets the total number of physics updates performed per tick. See also the information
     * of {@link #getUpdateSpeedFactor()}.
     * 
     * @return update step count (normally 1)
     */
    public int getUpdateStepCount() {
        return this.updateStepCount;
    }

    /**
     * Gets whether the currently executing updates are the final update step.
     * See {@link #getUpdateSpeedFactor()} for an explanation of what this means.
     * 
     * @return True if this is the last update step
     */
    public boolean isLastUpdateStep() {
        return this.updateStepNr == this.updateStepCount;
    }

    /**
     * Aborts any physics routines going on in this tick
     */
    public void breakPhysics() {
        this.breakPhysics = true;
    }

    /*
     * These two overrides ensure that sets use this MinecartGroup properly
     * Without it, the AbstractList versions were used, which don't apply here
     */
    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object other) {
        return other == this;
    }

    /**
     * Gets the Member that is on the rails block position specified
     *
     * @param position to get the member at
     * @return member at the position, or null if not found
     */
    public MinecartMember<?> getAt(IntVector3 position) {
        return getRailTracker().getMemberFromRails(position);
    }

    private boolean doConnectionCheck() {
        // Check all railed minecarts follow the same tracks
        // This is important for switcher/junction split logic
        for (int i = 0; i < this.size() - 1; i++) {
            // (!get(i + 1).isFollowingOnTrack(get(i))) {
            if (get(i).getRailTracker().isTrainSplit()) {
                // Undo stepcount based velocity modifications
                for (int j = i + 1; j < this.size(); j++) {
                    this.get(j).getEntity().vel.divide(this.updateSpeedFactor);
                }
                // Split
                MinecartGroup gnew = this.split(i + 1);
                if (gnew != null) {
                    //what time do we want to prevent them from colliding too soon?
                    //needs to travel 2 blocks in the meantime
                    int time = (int) MathUtil.clamp(2 / gnew.head().getRealSpeed(), 20, 40);
                    for (MinecartMember<?> mm1 : gnew) {
                        for (MinecartMember<?> mm2 : this) {
                            mm1.ignoreCollision(mm2.getEntity().getEntity(), time);
                        }
                    }
                }
                return false;
            }
        }

        // Check that no minecart is too far apart from another
        for (int i = 0; i < this.size() - 1; i++) {
            MinecartMember<?> m1 = get(i);
            MinecartMember<?> m2 = get(i + 1);
            if (!m1.isDerailed() && !m2.isDerailed()) {
                continue; // ignore railed minecarts that can still reach each other
            }
            if (m1.getEntity().loc.distance(m2.getEntity().loc) >= m1.getMaximumDistance(m2)) {
                this.split(i + 1);
                return false;
            }
        }
        return true;
    }

    // loads the static chunksBuffer with the chunk coordinates of the minecarts of this group
    private LongHashSet loadChunksBuffer() {
        chunksBuffer.clear();
        for (MinecartMember<?> mm : this) {
            chunksBuffer.add(mm.getEntity().loc.x.chunk(), mm.getEntity().loc.z.chunk());
        }
        return chunksBuffer;
    }

    /**
     * Called after the group has been spawned or created for the first time
     */
    public void onGroupCreated() {
        this.onPropertiesChanged();

        // When keep chunks loaded is active, make sure to enforce that right away
        // If we do it next tick a chunk could unload before we can do so
        // Do not do this for normal unloading logic, as that may unload the train in there (this should be later)
        if (!this.canUnload()) {
            this.updateChunkInformation();
        }
    }

    /**
     * Refreshes the chunks this train is occupying. When the train keeps chunks loaded,
     * makes sure to load the new chunks and allow old chunks to unload again.
     */
    private void updateChunkInformation() {
        boolean canUnload = this.canUnload();
        try (Timings t = TCTimings.GROUP_UPDATE_CHUNKS.start()) {
            // Refresh the chunk area tracker using this information
            this.chunkArea.refresh(this.getWorld(), this.loadChunksBuffer());
            this.chunkAreaValid = true;

            // Keep-chunks-loaded or automatic unloading when moving into unloaded chunks
            if (canUnload) {
                // Check all newly added chunks whether the chunk is unloaded
                // When such a chunk is found, unload this train
                for (ChunkArea.OwnedChunk chunk : this.chunkArea.getAdded()) {
                    if (!chunk.isLoaded()) {
                        this.unload();
                        throw new GroupUnloadedException();
                    }
                }
            } else {
                // Load chunks we entered for asynchronous loading
                for (ChunkArea.OwnedChunk chunk : this.chunkArea.getAdded()) {
                    chunk.keepLoaded(true);
                }

                // Load chunks closeby right away and guarantee they are loaded at all times
                for (ChunkArea.OwnedChunk chunk : this.chunkArea.getAll()) {
                    if (chunk.getDistance() <= 1 && chunk.getPreviousDistance() > 1) {
                        chunk.loadChunk();
                    }
                }
            }
        }
    }

    public void logCartInfo(String header) {
        StringBuilder msg = new StringBuilder(size() * 7 + 10);
        msg.append(header);
        for (MinecartMember<?> member : this) {
            msg.append(" [");
            msg.append(member.getDirection());
            msg.append(" - ").append(member.getEntity().vel);
            msg.append("]");
        }
        TrainCarts.plugin.log(Level.INFO, msg.toString());
    }

    /**
     * Gets the distance and speed of an obstacle up ahead on the tracks.
     * This can be another train, or a mutex zone that blocks further movement.
     * 
     * @param distance The distance in blocks to check for obstacles
     * @return obstacle found within this distance, null if there is none
     */
    public SpeedAheadWaiter.Obstacle findObstacleAhead(double distance) {
        return this.speedAheadWaiter.findObstacleAhead(distance, true);
    }

    /**
     * Gets the speed the train should be moving at to avoid collision with any trains in front.
     * A return value of 0 or less indicates the train should be halted entirely. A return value
     * of Double.MAX_VALUE indicates there are no obstacles ahead and the train can move on uninterrupted.
     * 
     * @param distance to look for trains ahead
     * @return speed to match
     * @see {@link #findObstacleAhead(double)}
     */
    public double getSpeedAhead(double distance) {
        SpeedAheadWaiter.Obstacle obstacle = this.speedAheadWaiter.findObstacleAhead(distance, true);
        return (obstacle != null) ? obstacle.speed : Double.MAX_VALUE;
    }

    private void tickActions() {
        try (Timings t = TCTimings.GROUP_TICK_ACTIONS.start()) {
            this.getActions().doTick();
        }
    }

    protected void doPhysics(TrainCarts plugin) {
        // NOP if unloaded
        if (this.isUnloaded()) {
            return;
        }

        // Remove minecarts from this group that don't actually belong to this group
        // This is a fallback/workaround for a reported resource bug where fake trains are created
        {
            for (int i = 0; i < this.size(); i++) {
                MinecartMember<?> member = super.get(i);
                if (member.getEntity() == null) {
                    // Controller is detached. It's completely invalid!
                    // We handle unloading ourselves, so the minecart should be considered gone :(
                    CartPropertiesStore.remove(member.getProperties().getUUID());
                    onMemberRemoved(member);
                    super.remove(i--);
                    continue;
                }
                if (member.group != this) {
                    // Assigned to a different group. Quietly remove it. You saw nothing!
                    onMemberRemoved(member);
                    super.remove(i--);
                    continue;
                }
            }
        }

        // Remove minecarts from this group that are dead
        // This operation can completely alter the structure of the group iterated over
        // For this reason, this logic is inside a loop
        boolean finishedRemoving;
        do {
            finishedRemoving = true;
            for (int i = 0; i < this.size(); i++) {
                MinecartMember<?> member = super.get(i);
                if (member.getEntity().isDead()) {
                    this.remove(i);
                    finishedRemoving = false;
                    break;
                }
            }
        } while (!finishedRemoving);

        // Remove empty trains entirely before doing any physics at all
        if (super.isEmpty()) {
            this.remove();
            return;
        }

        if (this.canUnload()) {
            for (MinecartMember<?> m : this) {
                if (m.isUnloaded()) {
                    this.unload();
                    return;
                }
            }
        } else {
            for (MinecartMember<?> m : this) {
                m.setUnloaded(false);
            }
        }

        // If physics disabled this tick, cut off here.
        if (!plugin.getTrainUpdateController().isTicking()) {
            return;
        }

        try {
            double totalforce = this.getAverageForce();
            double speedlimit = this.getProperties().getSpeedLimit();
            double realtimeFactor = this.getProperties().hasRealtimePhysics()
                    ? plugin.getTrainUpdateController().getRealtimeFactor() : 1.0;

            if ((realtimeFactor*totalforce) > 0.4 && (realtimeFactor*speedlimit) > 0.4) {
                this.updateStepCount = (int) Math.ceil((realtimeFactor*speedlimit) / 0.4);
                this.updateSpeedFactor = realtimeFactor / (double) this.updateStepCount;
            } else {
                this.updateStepCount = 1;
                this.updateSpeedFactor = realtimeFactor;
            }

            try (Timings t = TCTimings.GROUP_DOPHYSICS.start()) {
                // Perform the physics changes
                if (this.updateStepCount > 1) {
                    for (MinecartMember<?> mm : this) {
                        mm.getEntity().vel.multiply(this.updateSpeedFactor);
                    }
                }
                for (int i = 1; i <= this.updateStepCount; i++) {
                    this.updateStepNr = i;
                    while (!this.doPhysics_step());
                }
            }

            // Restore velocity / max speed to what is exposed outside the physics function
            // Use the speed factor for this, since the max speed may have been changed during the physics update
            // This can happen with, for example, the use of waitDistance
            for (MinecartMember<?> mm : this) {
                mm.getEntity().vel.divide(this.updateSpeedFactor);

                double newMaxSpeed = mm.getEntity().getMaxSpeed() / this.updateSpeedFactor;
                newMaxSpeed = Math.min(newMaxSpeed, this.getProperties().getSpeedLimit());
                mm.getEntity().setMaxSpeed(newMaxSpeed);
            }

            this.updateSpeedFactor = 1.0;

            // Server bugfix: prevents an old Minecart duplicate staying behind inside a chunk when saved
            // This issue has been resolved on Paper, see https://github.com/PaperMC/Paper/issues/1223
            for (MinecartMember<?> mm : this) {
                CommonEntity<?> entity = mm.getEntity();
                if (entity.isInLoadedChunk()) {
                    int cx = entity.getChunkX();
                    int cz = entity.getChunkZ();
                    if (cx != entity.loc.x.chunk() || cz != entity.loc.z.chunk()) {
                        ChunkHandle.fromBukkit(entity.getWorld().getChunkAt(cx, cz)).markDirty();
                    }
                }
            }

        } catch (GroupUnloadedException ex) {
            //this group is gone
        } catch (Throwable t) {
            final TrainProperties p = getProperties();
            TrainCarts.plugin.log(Level.SEVERE, "Failed to perform physics on train '" + p.getTrainName() + "' at " + p.getLocation() + ":");
            TrainCarts.plugin.handle(t);
        }
    }

    private boolean doPhysics_step() throws GroupUnloadedException {
        this.breakPhysics = false;
        try {
            // Prevent index exceptions: remove if not a train
            if (this.isEmpty()) {
                this.remove();
                throw new GroupUnloadedException();
            }

            // Validate members and set max speed
            // We must limit it to 0.4, otherwise derailment can occur when the
            // minecart speeds up inside the physics update function
            {
                double speedLimitClamped = MathUtil.clamp(this.getProperties().getSpeedLimit() * this.updateSpeedFactor, 0.4);
                for (MinecartMember<?> mm : this) {
                    mm.checkMissing();
                    mm.getEntity().setMaxSpeed(speedLimitClamped);
                }
            }

            // Set up a valid network controller if needed
            for (MinecartMember<?> member : this) {
                member.getAttachments().fixNetworkController();
            }

            // Update some per-tick stuff
            if (this.teleportImmunityTick > 0) {
                this.teleportImmunityTick--;
            }

            // Update direction and executed actions prior to updates
            this.updateDirection();
            this.getSignTracker().refresh();

            // Perform block change Minecart logic, also take care of potential new block changes
            for (MinecartMember<?> member : this) {
                member.checkMissing();
                if (member.hasBlockChanged() | member.forcedBlockUpdate.clear()) {
                    // Perform events and logic - validate along the way
                    MemberBlockChangeEvent.call(member, member.getLastBlock(), member.getBlock());
                    member.checkMissing();
                    member.onBlockChange(member.getLastBlock(), member.getBlock());
                    this.getSignTracker().updatePosition();
                    member.checkMissing();
                }
            }
            this.getSignTracker().refresh();

            this.updateDirection();
            if (!this.doConnectionCheck()) {
                return true; //false;
            }

            this.tickActions();

            this.updateDirection();

            // Perform velocity updates
            for (MinecartMember<?> member : this) {
                member.onPhysicsStart();
            }

            // Perform velocity updates
            try (Timings t = TCTimings.MEMBER_PHYSICS_PRE.start()) {
                for (MinecartMember<?> member : this) {
                    member.onPhysicsPreMove();
                }
            }

            // Stop if all dead
            if (this.isEmpty()) {
                return false;
            }

            // Add the gravity effects right before moving the Minecart
            // This changes velocity slightly so that minecarts go downslope or fall down
            // It is important to do it here, so that gravity is taken into account
            // when sliding over the ground. Doing this in the wrong spot will make the minecart 'hover'.
            if (this.getProperties().isSlowingDown(SlowdownMode.GRAVITY)) {
                double usf_sq = this.getProperties().getGravity() * this.getUpdateSpeedFactor() * this.getUpdateSpeedFactor();
                for (MinecartMember<?> member : this) {
                    if (member.isUnloaded()) continue; // not loaded - no physics occur
                    if (member.isMovementControlled()) continue; // launched by station, launcher, etc.

                    // Find segment of the rails path the Minecart is on
                    member.getRailLogic().onGravity(member, usf_sq);
                }
            }

            // Direction can change as a result of gravity
            this.updateDirection();

            // Share forward force between all the Minecarts when size > 1
            double forwardMovingSpeed;
            if (this.size() > 1) {
                //Get the average forwarding force of all carts
                forwardMovingSpeed = this.getAverageForce();

                //Perform forward force or not? First check if we are not messing up...
                boolean performUpdate = true;
                for (int i = 0; i < this.size() - 1; i++) {
                    if (get(i).getRailTracker().isTrainSplit()) {
                        performUpdate = false;
                        break;
                    }
                }

                if (performUpdate) {
                    //update force
                    for (MinecartMember<?> m : this) {
                        m.setForwardForce(forwardMovingSpeed);
                    }
                }
            } else {
                forwardMovingSpeed = this.head().getForce();
            }

            // If a wait distance is set, check for trains ahead of the track and wait for those
            // We do the waiting by setting the max speed of the train (NOT speed limit!) to match that train's speed
            // It is important speed of this train is updated before doing these checks.
            try (Timings t = TCTimings.GROUP_ENFORCE_SPEEDAHEAD.start()) {
                this.speedAheadWaiter.update(forwardMovingSpeed);
                double limitedSpeed = this.speedAheadWaiter.getSpeedLimit();
                if (limitedSpeed != Double.MAX_VALUE) {
                    limitedSpeed = Math.min(0.4, this.updateSpeedFactor * limitedSpeed);
                    for (MinecartMember<?> mm : this) {
                        mm.getEntity().setMaxSpeed(limitedSpeed);
                    }
                }
            }

            // Calculate the speed factor that will be used to adjust the distance between the minecarts
            for (MinecartMember<?> member : this) {
                member.calculateSpeedFactor();
            }

            // Perform the rail post-movement logic
            for (MinecartMember<?> member : this) {
                try (Timings t = TCTimings.MEMBER_PHYSICS_POST.start()) {
                    member.onPhysicsPostMove();
                    if (this.breakPhysics) return true;
                }
            }

            // Always refresh at least once per tick
            // This moment is strategically chosen, because after movement is the most likely
            // that a physics change will be required
            if (this.isLastUpdateStep()) {
                notifyPhysicsChange();
            }

            // Update directions and perform connection checks after the position changes
            this.updateDirection();
            if (!this.doConnectionCheck()) {
                return true; //false;
            }

            // Refresh chunks
            this.updateChunkInformation();

            // Refresh wheel position information, important to do it AFTER updateDirection()
            this.updateWheels();

            return true;
        } catch (MemberMissingException ex) {
            return false;
        }
    }
}
