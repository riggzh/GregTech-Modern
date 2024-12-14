package com.gregtechceu.gtceu.api.capability.recipe;

import com.google.common.collect.Table;
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.machine.trait.RecipeHandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public interface IRecipeCapabilityHolder {

    default boolean hasCapabilityProxies() {
        return !getCapabilitiesProxy().isEmpty();
    }

    @NotNull
    Map<IO, List<RecipeHandlerList>> getCapabilitiesProxy();

    Map<IO, Map<RecipeCapability<?>, List<IRecipeHandler<?>>>> getCapabilitiesFlat();

    default List<IRecipeHandler<?>> getCapabilitiesFlat(IO io, RecipeCapability<?> cap) {
        if(getCapabilitiesProxy().get(io) == null) {
            return Collections.emptyList();
        }
        return getCapabilitiesFlat().get(io).get(cap);
    }
}
