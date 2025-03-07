package com.gregtechceu.gtceu.api.machine.trait;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;

import com.lowdragmc.lowdraglib.misc.FluidStorage;
import com.lowdragmc.lowdraglib.side.fluid.FluidHelper;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.side.fluid.FluidTransferHelper;
import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.core.Direction;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Predicate;

/**
 * @author KilaBash
 * @date 2023/2/20
 * @implNote NotifiableFluidTank
 */
public class NotifiableFluidTank extends NotifiableRecipeHandlerTrait<FluidIngredient>
                                 implements ICapabilityTrait, IFluidTransfer {

    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(NotifiableFluidTank.class,
            NotifiableRecipeHandlerTrait.MANAGED_FIELD_HOLDER);
    @Getter
    public final IO handlerIO;
    @Getter
    public final IO capabilityIO;
    @Persisted
    @Getter
    private final FluidStorage[] storages;
    @Setter
    protected boolean allowSameFluids; // Can different tanks be filled with the same fluid. It should be determined
                                       // while creating tanks.
    private Boolean isEmpty;

    @Persisted
    @DescSynced
    @Getter
    protected FluidStorage lockedFluid = new FluidStorage(FluidHelper.getBucket());

    public NotifiableFluidTank(MetaMachine machine, int slots, long capacity, IO io, IO capabilityIO) {
        super(machine);
        this.handlerIO = io;
        this.storages = new FluidStorage[slots];
        this.capabilityIO = capabilityIO;
        for (int i = 0; i < this.storages.length; i++) {
            this.storages[i] = new FluidStorage(capacity);
            // this.storages[i].setOnContentsChanged(this::onContentsChanged);
        }
    }

    public NotifiableFluidTank(MetaMachine machine, List<FluidStorage> storages, IO io, IO capabilityIO) {
        super(machine);
        this.handlerIO = io;
        this.storages = storages.toArray(FluidStorage[]::new);
        this.capabilityIO = capabilityIO;
        // for (FluidStorage storage : this.getStorages()) {
        // storage.setOnContentsChanged(this::onContentsChanged);
        // }
        if (io == IO.IN) {
            this.allowSameFluids = true;
        }
    }

    public NotifiableFluidTank(MetaMachine machine, int slots, long capacity, IO io) {
        this(machine, slots, capacity, io, io);
    }

    public NotifiableFluidTank(MetaMachine machine, List<FluidStorage> storages, IO io) {
        this(machine, storages, io, io);
    }

    public void onContentsChanged() {
        isEmpty = null;
        notifyListeners();
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public List<FluidIngredient> handleRecipeInner(IO io, GTRecipe recipe, List<FluidIngredient> left,
                                                   boolean simulate) {
        return handleIngredient(io, recipe, left, simulate, this.handlerIO, storages);
    }

    @Nullable
    public static List<FluidIngredient> handleIngredient(IO io, GTRecipe recipe, List<FluidIngredient> left,
                                                         boolean simulate, IO handlerIO, FluidStorage[] storages) {
        if (io != handlerIO) return left;
        if (io != IO.IN && io != IO.OUT) return left.isEmpty() ? null : left;

        FluidAction action = simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE;
        boolean changed = false;
        // Store the FluidStack in each slot after an operation
        // Necessary for simulation since we don't actually modify the slot's contents
        // Doesn't hurt for execution, and definitely cheaper than copying the entire storage
        FluidStack[] visited = new FluidStack[storages.length];
        for (var it = left.iterator(); it.hasNext();) {
            var ingredient = it.next();
            if (ingredient.isEmpty()) {
                it.remove();
                continue;
            }

            var fluids = ingredient.getStacks();
            if (fluids.length == 0 || fluids[0].isEmpty()) {
                it.remove();
                continue;
            }

            if (io == IO.OUT && !allowSameFluids) {
                CustomFluidTank existing = null;
                for (var storage : storages) {
                    if (!storage.getFluid().isEmpty() && storage.getFluid().isFluidEqual(fluids[0])) {
                        existing = storage;
                        break;
                    }
                }
                if (existing != null) {
                    FluidStack output = fluids[0];
                    int filled = existing.fill(output, action);
                    ingredient.shrink(filled);
                    if (ingredient.getAmount() <= 0) {
                        it.remove();
                    }
                    // Continue to next ingredient regardless of if we filled this ingredient completely
                    continue;
                }
            }

            for (int tank = 0; tank < storages.length; ++tank) {
                FluidStack stored = getFluidInTank(tank);
                int amount = (visited[tank] == null ? stored.getAmount() : visited[tank].getAmount());
                if (io == IO.IN) {
                    if (amount == 0) continue;
                    if ((visited[tank] == null && ingredient.test(stored)) || ingredient.test(visited[tank])) {
                        var drained = storages[tank].drain(ingredient.getAmount(), action);
                        if (drained.getAmount() > 0) {
                            changed = action.execute();
                            visited[tank] = drained.copy();
                            visited[tank].setAmount(amount - drained.getAmount());
                            ingredient.shrink(drained.getAmount());
                        }
                    }
                } else { // IO.OUT && No tank already has this output
                    FluidStack output = fluids[0].copy();
                    output.setAmount(ingredient.getAmount());
                    if (visited[tank] == null || visited[tank].isFluidEqual(output)) {
                        int filled = storages[tank].fill(output, action);
                        if (filled > 0) {
                            changed = action.execute();
                            visited[tank] = output.copy();
                            visited[tank].setAmount(filled);
                            ingredient.shrink(filled);
                            if (!allowSameFluids) {
                                if (ingredient.getAmount() <= 0) it.remove();
                                break;
                            }
                        }
                    }
                }

                if (ingredient.getAmount() <= 0) {
                    it.remove();
                    break;
                }
            }
        }
        if (changed) onContentsChanged();
        return left.isEmpty() ? null : left;
    }

    @Override
    public boolean test(FluidIngredient ingredient) {
        return !this.isLocked() || ingredient.test(this.lockedFluid.getFluid());
    }

    @Override
    public int getPriority() {
        return !isLocked() || lockedFluid.getFluid().isEmpty() ? super.getPriority() : HIGH - getTanks();
    }

    public boolean isLocked() {
        return !lockedFluid.getFluid().isEmpty();
    }

    public void setLocked(boolean locked) {
        setLocked(locked, storages[0].getFluid());
    }

    public void setLocked(boolean locked, FluidStack fluidStack) {
        if (this.isLocked() == locked) return;
        if (locked && !fluidStack.isEmpty()) {
            this.lockedFluid.setFluid(fluidStack.copy());
            this.lockedFluid.getFluid().setAmount(1);
            setFilter(stack -> stack.isFluidEqual(this.lockedFluid.getFluid()));
        } else {
            this.lockedFluid.setFluid(FluidStack.empty());
            setFilter(stack -> true);
        }
        onContentsChanged();
    }

    public NotifiableFluidTank setFilter(Predicate<FluidStack> filter) {
        for (FluidStorage storage : getStorages()) {
            storage.setValidator(filter);
        }
        return this;
    }

    @Override
    public RecipeCapability<FluidIngredient> getCapability() {
        return FluidRecipeCapability.CAP;
    }

    public int getTanks() {
        return getStorages().length;
    }

    @Override
    public int getSize() {
        return getTanks();
    }

    @Override
    public List<Object> getContents() {
        List<FluidStack> ingredients = new ArrayList<>();
        for (int i = 0; i < getTanks(); ++i) {
            FluidStack stack = getFluidInTank(i);
            if (!stack.isEmpty()) {
                ingredients.add(stack);
            }
        }
        return Arrays.asList(ingredients.toArray());
    }

    @Override
    public double getTotalContentAmount() {
        long amount = 0;
        for (int i = 0; i < getTanks(); ++i) {
            FluidStack stack = getFluidInTank(i);
            if (!stack.isEmpty()) {
                amount += stack.getAmount();
            }
        }
        return amount;
    }

    public boolean isEmpty() {
        if (isEmpty == null) {
            isEmpty = true;
            for (FluidStorage storage : getStorages()) {
                if (!storage.getFluid().isEmpty()) {
                    isEmpty = false;
                    break;
                }
            }
        }
        return isEmpty;
    }

    public void exportToNearby(@NotNull Direction... facings) {
        if (isEmpty()) return;
        var level = getMachine().getLevel();
        var pos = getMachine().getPos();
        for (Direction facing : facings) {
            FluidTransferHelper.exportToTarget(this, Integer.MAX_VALUE, getMachine().getFluidCapFilter(facing), level,
                    pos.relative(facing),
                    facing.getOpposite());
        }
    }

    public void importFromNearby(@NotNull Direction... facings) {
        var level = getMachine().getLevel();
        var pos = getMachine().getPos();
        for (Direction facing : facings) {
            FluidTransferHelper.importToTarget(this, Integer.MAX_VALUE, getMachine().getFluidCapFilter(facing), level,
                    pos.relative(facing),
                    facing.getOpposite());
        }
    }

    //////////////////////////////////////
    // ******* Capability ********//
    //////////////////////////////////////
    @NotNull
    @Override
    public FluidStack getFluidInTank(int tank) {
        return getStorages()[tank].getFluid();
    }

    @Override
    public void setFluidInTank(int tank, @NotNull FluidStack fluidStack) {
        getStorages()[tank].setFluid(fluidStack);
        onContentsChanged();
    }

    @Override
    public long getTankCapacity(int tank) {
        return getStorages()[tank].getCapacity();
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        return getStorages()[tank].isFluidValid(stack);
    }

    @Override
    public long fill(FluidStack resource, boolean simulate, boolean notifyChanges) {
        if (resource.isEmpty() || !canCapInput()) return 0;
        long filled = 0;
        FluidStorage existingStorage = null;
        if (!allowSameFluids) {
            for (var storage : getStorages()) {
                if (!storage.getFluid().isEmpty() && storage.getFluid().isFluidEqual(resource)) {
                    existingStorage = storage;
                    break;
                }
            }
        }
        if (existingStorage == null) {
            for (int i = 0; i < getTanks(); i++) {
                if (filled > 0 && !allowSameFluids) {
                    break;
                }
                filled += fill(i, resource.copy(resource.getAmount() - filled), simulate, notifyChanges);
                if (filled == resource.getAmount()) break;
            }
        } else {
            filled += existingStorage.fill(resource.copy(resource.getAmount() - filled), simulate, notifyChanges);
        }
        if (notifyChanges && filled > 0 && !simulate) {
            onContentsChanged();
        }
        return filled;
    }

    @Override
    public long fill(int tank, FluidStack resource, boolean simulate, boolean notifyChanges) {
        if (tank >= 0 && tank < getStorages().length && canCapInput()) {
            return getStorages()[tank].fill(resource, simulate, notifyChanges);
        }
        return 0;
    }

    @Override
    public long fill(FluidStack resource, boolean simulate) {
        if (canCapInput()) {
            return fillInternal(resource, simulate);
        }
        return 0;
    }

    public long fillInternal(FluidStack resource, boolean simulate) {
        if (resource.isEmpty()) return 0;
        var copied = resource.copy();
        FluidStorage existingStorage = null;
        if (!allowSameFluids) {
            for (var storage : getStorages()) {
                if (!storage.getFluid().isEmpty() && storage.getFluid().isFluidEqual(resource)) {
                    existingStorage = storage;
                    break;
                }
            }
        }
        if (existingStorage == null) {
            for (var storage : getStorages()) {
                var filled = storage.fill(copied.copy(), simulate);
                if (filled > 0) {
                    copied.shrink(filled);
                    if (!allowSameFluids) {
                        break;
                    }
                }
                if (copied.isEmpty()) break;
            }
        } else {
            copied.shrink(existingStorage.fill(copied.copy(), simulate));
        }
        int filled = resource.getAmount() - copied.getAmount();
        if (filled > 0 && action.execute()) onContentsChanged();
        return filled;
    }

    @NotNull
    @Override
    public FluidStack drain(int tank, FluidStack resource, boolean simulate, boolean notifyChanges) {
        if (tank >= 0 && tank < getStorages().length && canCapOutput()) {
            return getStorages()[tank].drain(resource, simulate, notifyChanges);
        }
        return FluidStack.empty();
    }

    @NotNull
    @Override
    public FluidStack drain(FluidStack resource, boolean simulate) {
        if (canCapOutput()) {
            return drainInternal(resource, simulate);
        }
        return FluidStack.empty();
    }

    public FluidStack drainInternal(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return FluidStack.EMPTY;

        var copied = resource.copy();
        for (var storage : storages) {
            var candidate = copied.copy();
            copied.shrink(storage.drain(candidate, action).getAmount());
            if (copied.isEmpty()) break;
        }
        copied.setAmount(resource.getAmount() - copied.getAmount());
        if (!copied.isEmpty() && action.execute()) onContentsChanged();
        return copied;
    }

    @NotNull
    @Override
    public FluidStack drain(long maxDrain, boolean simulate) {
        if (canCapOutput()) {
            return drainInternal(maxDrain, simulate);
        }
        return FluidStack.empty();
    }

    public FluidStack drainInternal(long maxDrain, boolean simulate) {
        if (maxDrain == 0) return FluidStack.empty();
        FluidStack totalDrained = null;
        for (var storage : getStorages()) {
            if (totalDrained == null || totalDrained.isEmpty()) {
                totalDrained = storage.drain(maxDrain, simulate);
                if (totalDrained.isEmpty()) {
                    totalDrained = null;
                } else {
                    maxDrain -= totalDrained.getAmount();
                }
            } else {
                FluidStack copy = totalDrained.copy();
                copy.setAmount(maxDrain);
                FluidStack drain = storage.drain(copy, simulate);
                totalDrained.grow(drain.getAmount());
                maxDrain -= drain.getAmount();
            }
            if (maxDrain <= 0) break;
        }
        if (totalDrained != null && !totalDrained.isEmpty() && action.execute()) onContentsChanged();
        return totalDrained == null ? FluidStack.empty() : totalDrained;
    }

    @Override
    public boolean supportsFill(int i) {
        return canCapInput();
    }

    @Override
    public boolean supportsDrain(int i) {
        return canCapOutput();
    }

    @Override
    public void onMachineLoad() {
        super.onMachineLoad();
        if (this.isLocked()) {
            setFilter(stack -> stack.isFluidEqual(this.lockedFluid.getFluid()));
        }
    }

    @NotNull
    @Override
    public Object createSnapshot() {
        return Arrays.stream(getStorages()).map(IFluidTransfer::createSnapshot).toArray(Object[]::new);
    }

    @Override
    public void restoreFromSnapshot(Object snapshot) {
        if (snapshot instanceof Object[] array && array.length == getStorages().length) {
            for (int i = 0; i < array.length; i++) {
                getStorages()[i].restoreFromSnapshot(array[i]);
            }
        }
    }
}
