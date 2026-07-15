package elgatopro300.bbsfbx.mixin;

import elgatopro300.bbsfbx.BBSFbxAddon;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXModelLoadCache;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXModelLoader;

import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.Link;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ModelManager.class, remap = false)
public class ModelManagerMixin
{
    @Inject(method = "setupLoaders", at = @At("TAIL"), remap = false)
    private void bbsFbx$registerFbxLoader(CallbackInfo info)
    {
        ModelManager manager = (ModelManager) (Object) this;
        manager.loaders.add(new FBXModelLoader());
        BBSFbxAddon.LOGGER.info("FBX model loader registered");
    }

    @Inject(method = "isRelodable", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbsFbx$fbxIsRelodable(Link link, CallbackInfoReturnable<Boolean> info)
    {
        String path = link.path;

        if (path.startsWith(ModelManager.MODELS_PREFIX)
                && !path.contains("/animations/")
                && !path.contains("/shapes/")
                && path.toLowerCase().endsWith(".fbx"))
        {
            info.setReturnValue(true);
        }
    }

    /**
     * CRITICAL FIX: Clear the FBX geometry cache on every reload.
     *
     * BBS FS calls reload() when F6 is pressed. Without this, deleting a
     * model folder and re-adding it returns stale BOBJData from the previous
     * load. That stale data has mutated mesh names (from compilation) and
     * causes BBS FS to register only 1 material, which gets saved to disk.
     * Reloading the world then loads that broken saved config.
     */
    @Inject(method = "reload", at = @At("HEAD"), remap = false)
    private void bbsFbx$clearCacheOnReload(CallbackInfo info)
    {
        int size = FBXModelLoadCache.size();
        if (size > 0)
        {
            FBXModelLoadCache.clear();
            BBSFbxAddon.LOGGER.info("Cleared FBX model load cache ({} entries) for reload", size);
        }
    }
}