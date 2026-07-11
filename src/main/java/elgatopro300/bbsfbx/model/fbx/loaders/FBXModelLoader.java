package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;

import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import elgatopro300.bbsfbx.model.fbx.FBXConverter;

/**
 * Registers as BBS FS's FBX model loader (see the {@code bbs-addon} mixin
 * that installs this into {@code ModelManager.loaders}). Orchestrates the
 * pipeline; the actual work is split into:
 * <ul>
 *   <li>{@link FBXAssimpImporter} — drives the Assimp import</li>
 *   <li>{@link FBXConverter} — Assimp scene -> BOBJData</li>
 *   <li>{@link FBXMeshCompiler} — BOBJMesh -> packed renderer buffers</li>
 *   <li>{@link FBXAnimationConverter} — BOBJAction -> BBS Animations</li>
 *   <li>{@link FBXTextureResolver} — default texture + material registration</li>
 * </ul>
 */
public class FBXModelLoader implements IModelLoader
{
    @Override
    public ModelInstance load(String id, ModelManager models, Link model, Collection<Link> links, MapType config)
    {
        Link fbxLink = null;

        for (Link link : links)
        {
            if (link.path.toLowerCase().endsWith(".fbx"))
            {
                fbxLink = link;
                break;
            }
        }

        if (fbxLink == null)
        {
            return null;
        }

        try
        {
            byte[] bytes;
            try (InputStream stream = models.provider.getAsset(fbxLink))
            {
                if (stream == null)
                {
                    return null;
                }
                bytes = stream.readAllBytes();
            }

            AIScene scene = FBXAssimpImporter.importScene(bytes);

            if (scene == null)
            {
                return null;
            }

            BOBJData data;
            try
            {
                data = FBXConverter.convert(scene);
                FBXConverter.extractEmbeddedTextures(scene, models.provider, model);
            }
            finally
            {
                Assimp.aiReleaseImport(scene);
            }

            data.initiateArmatures();

            /* BBS FS's BOBJModel takes one CompiledData per mesh (instead of
             * CML's single merged CompiledData), and FS's renderer reads the
             * material name from CompiledData.mesh, so each mesh is compiled
             * separately with its mesh reference attached. */
            List<CompiledData> compiledMeshes = new ArrayList<>();

            for (BOBJMesh mesh : data.meshes)
            {
                compiledMeshes.add(FBXMeshCompiler.compile(data, mesh));
            }

            BOBJArmature armature = null;
            if (!data.armatures.isEmpty())
            {
                armature = data.armatures.values().iterator().next();
            }

            if (armature == null)
            {
                armature = new BOBJArmature("Armature");
                armature.initArmature();
            }

            BOBJModel bobjModel = new BOBJModel(armature, compiledMeshes, false);

            Animations animations = FBXAnimationConverter.convert(data.actions, models.parser);

            Link textureLink = FBXTextureResolver.resolveDefaultTexture(data, model, links);

            ModelInstance modelInstance = new ModelInstance(id, bobjModel, animations, textureLink);

            FBXTextureResolver.registerMaterials(modelInstance, compiledMeshes, models.provider, model, links);

            modelInstance.applyConfig(config);
            return modelInstance;
        }
        catch (Throwable e)
        {
            System.err.println("Failed to load FBX model for " + id + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        return null;
    }
}