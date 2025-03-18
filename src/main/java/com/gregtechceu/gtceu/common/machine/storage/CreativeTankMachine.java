package com.gregtechceu.gtceu.common.machine.storage;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.transfer.fluid.InfiniteFluidTransferProxy;

import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ResourceBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.*;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.side.fluid.FluidTransferHelper;
import com.lowdragmc.lowdraglib.side.fluid.IFluidTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DropSaved;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;

public class CreativeTankMachine extends QuantumTankMachine {

    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(CreativeTankMachine.class,
            QuantumTankMachine.MANAGED_FIELD_HOLDER);

    @Persisted
    @DropSaved
    private int mBPerCycle = 1;
    @Persisted
    @DropSaved
    private int ticksPerCycle = 1;

    private final InfiniteFluidTransferProxy capabilityTransferProxy;

    public CreativeTankMachine(IMachineBlockEntity holder) {
        super(holder, GTValues.MAX, -1);

        capabilityTransferProxy = new InfiniteFluidTransferProxy(cache, true, true);
    }

    @Nullable
    @Override
    public IFluidTransfer getFluidTransferCap(@Nullable Direction side, boolean useCoverCapability) {
        if (side == null || (useCoverCapability && coverContainer.hasCover(side)))
            return super.getFluidTransferCap(side, useCoverCapability);

        return capabilityTransferProxy;
    }

    protected NotifiableFluidTank createCacheFluidHandler(Object... args) {
        return new NotifiableFluidTank(this, 1, 1000, IO.BOTH, IO.NONE);
    }

    @Override
    protected void updateAutoOutputSubscription() {
        var outputFacing = getOutputFacingFluids();
        if ((isAutoOutputFluids() && !cache.isEmpty()) && outputFacing != null && FluidTransferHelper
                .getFluidTransfer(getLevel(), getPos().relative(outputFacing), outputFacing.getOpposite()) != null) {
            autoOutputSubs = subscribeServerTick(autoOutputSubs, this::checkAutoOutput);
        } else if (autoOutputSubs != null) {
            autoOutputSubs.unsubscribe();
            autoOutputSubs = null;
        }
    }

    protected void checkAutoOutput() {
        if (getOffsetTimer() % ticksPerCycle == 0) {
            if (isAutoOutputFluids() && getOutputFacingFluids() != null) {
                updateFluidTick();
            }
            updateAutoOutputSubscription();
        }
    }

    @Override
    public long getStoredAmount() {
        return (long) Math.ceil(1d * mBPerCycle / ticksPerCycle);
    }

    private InteractionResult updateStored(FluidStack fluid) {
        stored = new FluidStack(fluid, 1000);
        onFluidChanged();
        return InteractionResult.SUCCESS;
    }

    private void setTicksPerCycle(String value) {
        if (value.isEmpty()) return;
        ticksPerCycle = Integer.parseInt(value);
        onFluidChanged();
    }

    private void setmBPerCycle(String value) {
        if (value.isEmpty()) return;
        mBPerCycle = Integer.parseInt(value);
        onFluidChanged();
    }

    @Override
    public InteractionResult onUse(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand,
                                   BlockHitResult hit) {
        var heldItem = player.getItemInHand(hand);
        if (hit.getDirection() == getFrontFacing() && !isRemote()) {
            // Clear fluid if empty + shift-rclick
            if (heldItem.isEmpty()) {
                if (player.isCrouching() && !stored.isEmpty()) {
                    return updateStored(FluidStack.EMPTY);
                }
                return InteractionResult.PASS;
            }

            // If no fluid set and held-item has fluid, set fluid
            if (stored.isEmpty()) {
                return FluidUtil.getFluidContained(heldItem)
                        .map(this::updateStored)
                        .orElse(InteractionResult.PASS);
            }

            // Need to make a fake source to fully fill held-item since our cache only allows mbPerTick extraction
            CustomFluidTank source = new CustomFluidTank(new FluidStack(stored, Integer.MAX_VALUE));
            ItemStack result = FluidUtil.tryFillContainer(heldItem, source, Integer.MAX_VALUE, player, true)
                    .getResult();
            if (!result.isEmpty() && heldItem.getCount() > 1) {
                ItemHandlerHelper.giveItemToPlayer(player, result);
                result = heldItem.copy();
                result.shrink(1);
            }

            if (!result.isEmpty()) {
                player.setItemInHand(hand, result);
                return InteractionResult.SUCCESS;
            } else {
                return FluidUtil.getFluidContained(heldItem)
                        .map(this::updateStored)
                        .orElse(InteractionResult.PASS);
            }
        }
        return InteractionResult.PASS;
    }

    @Override
    public WidgetGroup createUIWidget() {
        var group = new WidgetGroup(0, 0, 176, 131);
        group.addWidget(new PhantomFluidWidget(this.cache.getStorages()[0], 0, 36, 6, 18, 18,
                () -> this.cache.getStorages()[0].getFluid(), (fluid) -> this.cache.getStorages()[0].setFluid(fluid))
                .setShowAmount(false).setBackground(GuiTextures.FLUID_SLOT));
        group.addWidget(new LabelWidget(7, 9, "gtceu.creative.tank.fluid"));
        group.addWidget(new ImageWidget(7, 45, 154, 14, GuiTextures.DISPLAY));
        group.addWidget(new TextFieldWidget(9, 47, 152, 10, () -> String.valueOf(mBPerCycle), value -> {
            if (!value.isEmpty()) {
                mBPerCycle = Integer.parseInt(value);
            }
        }).setMaxStringLength(11).setNumbersOnly(1, Integer.MAX_VALUE));
        group.addWidget(new LabelWidget(7, 28, "gtceu.creative.tank.mbpc"));

        group.addWidget(new ImageWidget(7, 82, 154, 14, GuiTextures.DISPLAY));
        group.addWidget(new TextFieldWidget(9, 84, 152, 10, () -> String.valueOf(ticksPerCycle), value -> {
            if (!value.isEmpty()) {
                ticksPerCycle = Integer.parseInt(value);
            }
        }).setMaxStringLength(11).setNumbersOnly(1, Integer.MAX_VALUE));
        group.addWidget(new LabelWidget(7, 65, "gtceu.creative.tank.tpc"));

        group.addWidget(new SwitchWidget(7, 101, 162, 20, (clickData, value) -> setWorkingEnabled(value))
                .setTexture(
                        new GuiTextureGroup(ResourceBorderTexture.BUTTON_COMMON,
                                new TextTexture("gtceu.creative.activity.off")),
                        new GuiTextureGroup(ResourceBorderTexture.BUTTON_COMMON,
                                new TextTexture("gtceu.creative.activity.on")))
                .setPressed(isWorkingEnabled()));

        return group;
    }

    public void updateFluidTick() {
        if (ticksPerCycle == 0 || getOffsetTimer() % ticksPerCycle != 0 ||
                cache.getStorages()[0].getFluid().isEmpty() || getLevel().isClientSide || !isWorkingEnabled())
            return;

        IFluidTransfer transfer = FluidTransferHelper.getFluidTransfer(getLevel(),
                getPos().relative(getOutputFacingFluids()), getOutputFacingFluids().getOpposite());
        if (transfer != null) {
            FluidStack stack = cache.getStorages()[0].getFluid().copy();
            stack.setAmount(mBPerCycle);
            long canInsertAmount = transfer.fill(stack, true);
            stack.setAmount(Math.min(mBPerCycle, canInsertAmount));

            transfer.fill(stack, false);
        }
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }
}
