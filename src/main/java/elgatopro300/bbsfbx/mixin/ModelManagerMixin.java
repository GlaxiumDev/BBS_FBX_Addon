package elgatopro300.bbsfbx.mixin;

import elgatopro300.bbsfbx.BBSFbxAddon;
import elgatopro300.bbsfbx.model.fbx.loaders.FBXModelLoader;

import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.Link;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BBS FS has no {@code RegisterModelLoadersEvent} (unlike CML), so this mixin
 * hooks into {@link ModelManager} to register the FBX loader and to make
 * {@code .fbx} files count as reloadable model assets.
 *
 * <p>Injecting into {@code setupLoaders} (instead of only the constructor)
 * keeps the FBX loader registered after {@code ModelManager.reload()},
 * which clears and rebuilds the loader list.</p>
 */
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
}