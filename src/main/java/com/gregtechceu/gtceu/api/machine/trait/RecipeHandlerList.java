package com.gregtechceu.gtceu.api.machine.trait;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHandler;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.lowdragmc.lowdraglib.syncdata.ISubscription;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

public class RecipeHandlerList {
    public Map<RecipeCapability<?>, List<IRecipeHandler<?>>> handlerMap = new Object2ObjectOpenHashMap<>();
    private IO io;
    @Setter
    @Getter
    private boolean isDistinct = false;

    public RecipeHandlerList(IO io) {
        this.io = io;
    }

    public static RecipeHandlerList of(IO io, IRecipeHandler<?> handler) {
        RecipeHandlerList rhl = new RecipeHandlerList(io);
        rhl.addHandler(handler);
        return rhl;
    }

    public List<IRecipeHandler<?>> getCapability(RecipeCapability<?> cap) {
        return handlerMap.getOrDefault(cap, Collections.emptyList());
    }

    public void addHandler(IRecipeHandler<?>... handlers) {
        for(var handler : handlers) {
            handlerMap.computeIfAbsent(handler.getCapability(), c -> new ArrayList<>()).add(handler);
        }
    }

    public boolean hasCapability(RecipeCapability<?> cap) {
        return handlerMap.containsKey(cap);
    }

    public IO getHandlerIO() {
        return io;
    }

    public List<ISubscription> addChangeListeners(Runnable listener) {
        List<ISubscription> ret = new ArrayList<>();
        for(var handlerList : handlerMap.values()) {
            for(var handler : handlerList) {
                if(handler instanceof IRecipeHandlerTrait<?> handlerTrait) {
                    ret.add(handlerTrait.addChangedListener(listener));
                }
            }
        }
        return ret;
    }

    public Map<RecipeCapability<?>, List> handleRecipe(IO io, GTRecipe recipe, Map<RecipeCapability<?>, List> contents, boolean simulate) {
        if(handlerMap.isEmpty()) return contents;
        var copy = new IdentityHashMap<>(contents);
        var it = copy.entrySet().iterator();
        while(it.hasNext()) {
            var entry = it.next();
            var handlerList = handlerMap.get(entry.getKey());
            if(handlerList == null)
                continue;
            for(var handler : handlerList) {
                var left = handler.handleRecipe(io, recipe, entry.getValue(), simulate);
                if(left == null) {
                    it.remove();
                    break;
                } else {
                    entry.setValue(left);
                }
            }
        }
        return copy;
    }
}
