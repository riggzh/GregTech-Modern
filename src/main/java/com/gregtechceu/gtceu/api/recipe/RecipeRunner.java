package com.gregtechceu.gtceu.api.recipe;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.chance.boost.ChanceBoostFunction;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import com.google.common.collect.Table;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.*;

/**
 * Used to handle recipes, only valid for a single RecipeCapability's entries
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
class RecipeRunner {

    record RecipeHandlingResult(RecipeCapability<?> capability, @UnknownNullability List content,
                                RecipeHandler.ActionResult result) {}

    // --------------------------------------------------------------------------------------------------------

    private final GTRecipe recipe;
    private final IO io;
    private final boolean isTick;
    private final IRecipeCapabilityHolder holder;
    private final Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches;
    private final Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> capabilityProxies;
    private final boolean simulated;

    // These are only used to store mutable state during each invocation of handle()
    private RecipeCapability<?> capability;
    private Set<IRecipeHandler<?>> used;
    @Getter
    private @UnknownNullability List content;
    private @UnknownNullability List search;

    public RecipeRunner(GTRecipe recipe, IO io, boolean isTick,
                        IRecipeCapabilityHolder holder, Map<RecipeCapability<?>, Object2IntMap<?>> chanceCaches,
                        boolean simulated) {
        this.recipe = recipe;
        this.io = io;
        this.isTick = isTick;
        this.holder = holder;
        this.chanceCaches = chanceCaches;
        this.capabilityProxies = holder.getCapabilitiesProxy();
        this.simulated = simulated;
    }

    @Nullable
    public RecipeHandlingResult handle(Map.Entry<RecipeCapability<?>, List<Content>> entry) {
        initState();

        this.fillContent(holder, entry);
        this.capability = this.resolveCapability(entry);

        if (capability == null)
            return null;

        var result = this.handleContents();
        if (result == null)
            return null;

        return new RecipeHandlingResult(capability, result, RecipeHandler.ActionResult.SUCCESS);
    }

    private void initState() {
        used = new HashSet<>();
        content = new ArrayList<>();
        search = simulated ? content : new ArrayList<>();
    }

    private void fillContent(IRecipeCapabilityHolder holder, Map.Entry<RecipeCapability<?>, List<Content>> entry) {
        RecipeCapability<?> cap = entry.getKey();
        ChanceBoostFunction function = recipe.getType().getChanceFunction();
        ChanceLogic logic = recipe.getChanceLogicForCapability(cap, this.io, this.isTick);
        List<Content> chancedContents = new ArrayList<>();
        for (Content cont : entry.getValue()) {
            // For simulated handling, search/content are the same instance, so there's no need to switch between them
            this.search.add(cont.content);

            // When simulating the recipe handling (used for recipe matching), chanced contents are ignored.
            if (simulated) continue;

            if (cont.chance >= cont.maxChance) {
                this.content.add(cont.content);
            } else {
                // unparallel the chanced contents - bandaid fix
                chancedContents.add(cont.copy(cap, ContentModifier.multiplier(1.0 / recipe.parallels)));
            }
        }

        // Only roll if there's anything to roll for
        if (!chancedContents.isEmpty()) {
            int recipeTier = RecipeHelper.getPreOCRecipeEuTier(recipe);
            int holderTier = holder.getChanceTier();
            var cache = this.chanceCaches.get(cap);
            chancedContents = logic.roll(chancedContents, function, recipeTier, holderTier, cache, recipe.parallels,
                    cap);

            if (chancedContents == null) return;
            for (Content cont : chancedContents) {
                this.content.add(cont.content);
            }
        }
    }

    private RecipeCapability<?> resolveCapability(Map.Entry<RecipeCapability<?>, List<Content>> entry) {
        RecipeCapability<?> capability = entry.getKey();
        if (!capability.doMatchInRecipe()) {
            return null;
        }

        content = this.content.stream().map(capability::copyContent).toList();
        if (this.content.isEmpty()) {
            content = null;
            return null;
        }

        return capability;
    }

    @Nullable
    private List handleContents() {
        handleContentsInternal(io);
        if (content == null) return null;
        handleContentsInternal(IO.BOTH);

        return content;
    }

    private void handleContentsInternal(IO capIO) {
        if (!capabilityProxies.contains(capIO, capability))
            return;

        // noinspection DataFlowIssue checked above.
        var handlers = new ArrayList<>(capabilityProxies.get(capIO, capability));
        handlers.sort(IRecipeHandler.ENTRY_COMPARATOR);

        // handle distinct first
        for (IRecipeHandler<?> handler : handlers) {
            if (!handler.isDistinct()) continue;
            var result = handler.handleRecipe(io, recipe, search, null, true);
            if (result == null) {
                if (!simulated) {
                    handler.handleRecipe(io, recipe, content, null, false);
                }
                content = null;
            }
            if (content == null) {
                break;
            }
        }
        if (content != null) {
            // handle undistinct later
            for (IRecipeHandler<?> proxy : handlers) {
                if (used.contains(proxy) || proxy.isDistinct()) continue;
                used.add(proxy);
                if (content != null) {
                    content = proxy.handleRecipe(io, recipe, content, null, simulated);
                }
                if (content == null) break;
            }
        }
    }
}
