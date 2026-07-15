package elgatopro300.bbsfbx.model.fbx.loaders;

import elgatopro300.bbsfbx.model.fbx.FBXShapeKeyModel;
import elgatopro300.bbsfbx.model.fbx.FBXShapeKeyNames;
import elgatopro300.bbsfbx.model.fbx.convert.FBXTextureExtractor;
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
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIAnimMesh;
import org.lwjgl.assimp.AIMesh;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

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

            long contentHash = FBXModelLoadCache.hash(bytes);
            FBXModelLoadCache.Cached cached = FBXModelLoadCache.get(fbxLink.path, contentHash);

            BOBJData data;
            Set<String> shapeKeyNames;

            if (cached != null)
            {
                /* Same file content as last time this path was loaded - e.g.
                 * "reload models" triggered by an unrelated asset change, not
                 * an edit to this FBX. Skip the native Assimp import and the
                 * scene -> BOBJData conversion entirely. */
                data = cached.data;
                shapeKeyNames = cached.shapeKeyNames;

                /* The geometry cache hit above says nothing about whether the PNGs extracted from
                 * this .fbx last time are still on disk - e.g. someone deleted
                 * "textures/<material>/default.png" by hand. Check cheaply, and only pay for an
                 * actual reimport (still skipping BOBJData conversion + shape key collection,
                 * since those are already cached) if something's actually missing. */
                ensureTexturesPresent(bytes, cached.texturedMaterials, models, model);
            }
            else
            {
                AIScene scene = null;
                Set<String> texturedMaterials;

                try
                {
                    scene = FBXAssimpImporter.importScene(bytes);

                    if (scene == null)
                    {
                        return null;
                    }

                    shapeKeyNames = collectShapeKeyNames(scene);
                    data = FBXConverter.convert(scene);
                    texturedMaterials = FBXConverter.extractEmbeddedTextures(scene, models.provider, model);
                }
                finally
                {
                    if (scene != null)
                    {
                        Assimp.aiReleaseImport(scene);
                    }
                }

                FBXModelLoadCache.put(fbxLink.path, contentHash, data, shapeKeyNames, texturedMaterials);
            }

            data.initiateArmatures();

            /* BBS FS's BOBJModel takes one CompiledData per mesh (instead of
             * CML's single merged CompiledData), and FS's renderer reads the
             * material name from CompiledData.mesh, so each mesh is compiled
             * separately with its mesh reference attached. Independent meshes
             * are compiled in parallel - see FBXLoaderExecutor. */
            List<CompiledData> compiledMeshes = compileMeshes(data);

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

            FBXShapeKeyModel bobjModel = new FBXShapeKeyModel(armature, compiledMeshes, false, shapeKeyNames);

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
    /**
     * Compiles every mesh into its packed renderer arrays. A model with
     * only one mesh (the common case) just compiles it directly - no point
     * paying thread hand-off cost for a single unit of work. A model with
     * several meshes (multi-material characters, props with separate
     * parts, etc.) spreads them across {@link FBXLoaderExecutor}'s pool,
     * since each mesh's compile is independent and only reads from the
     * already-built, unchanging vertex/texture/normal pools in {@code data}.
     */
    private static List<CompiledData> compileMeshes(BOBJData data) throws Exception
    {
        int count = data.meshes.size();

        if (count <= 1)
        {
            List<CompiledData> result = new ArrayList<>(count);

            for (BOBJMesh mesh : data.meshes)
            {
                result.add(FBXMeshCompiler.compile(data, mesh));
            }

            return result;
        }

        List<java.util.concurrent.Future<CompiledData>> futures = new ArrayList<>(count);

        for (BOBJMesh mesh : data.meshes)
        {
            futures.add(FBXLoaderExecutor.POOL.submit(() -> FBXMeshCompiler.compile(data, mesh)));
        }

        List<CompiledData> result = new ArrayList<>(count);

        for (java.util.concurrent.Future<CompiledData> future : futures)
        {
            result.add(future.get());
        }

        return result;
    }

    /**
     * Checks that every material this .fbx is known to have an embedded texture for still has its
     * {@code textures/<material>/default.png} on disk, and if not, re-imports the scene purely to
     * re-run {@link FBXTextureExtractor} - the geometry conversion and shape key name collection
     * stay served from the cache either way. A no-op (cheap, no I/O beyond the checks) in the
     * common case where nothing's been deleted.
     */
    private static void ensureTexturesPresent(byte[] bytes, Set<String> texturedMaterials, ModelManager models, Link model)
    {
        if (texturedMaterials == null || texturedMaterials.isEmpty())
        {
            return;
        }

        boolean missing = false;

        for (String materialName : texturedMaterials)
        {
            File folder = models.provider.getFile(model.combine("textures/" + materialName));
            File target = folder == null ? null : new File(folder, "default.png");

            if (target == null || !target.exists())
            {
                missing = true;
                break;
            }
        }

        if (!missing)
        {
            return;
        }

        AIScene scene = null;

        try
        {
            scene = FBXAssimpImporter.importScene(bytes);

            if (scene != null)
            {
                FBXConverter.extractEmbeddedTextures(scene, models.provider, model);
            }
        }
        finally
        {
            if (scene != null)
            {
                Assimp.aiReleaseImport(scene);
            }
        }
    }

    private static Set<String> collectShapeKeyNames(AIScene scene)
    {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        if (scene == null || scene.mNumMeshes() <= 0 || scene.mMeshes() == null)
        {
            return names;
        }

        PointerBuffer meshes = scene.mMeshes();

        for (int meshIndex = 0; meshIndex < scene.mNumMeshes(); meshIndex++)
        {
            AIMesh mesh = AIMesh.createSafe(meshes.get(meshIndex));

            if (mesh == null || mesh.mNumAnimMeshes() <= 0 || mesh.mAnimMeshes() == null)
            {
                continue;
            }

            String meshName = FBXShapeKeyNames.safeName(mesh.mName().dataString());
            PointerBuffer animMeshes = mesh.mAnimMeshes();

            for (int animIndex = 0; animIndex < mesh.mNumAnimMeshes(); animIndex++)
            {
                AIAnimMesh animMesh = AIAnimMesh.createSafe(animMeshes.get(animIndex));

                if (animMesh == null)
                {
                    continue;
                }

                String shapeKeyName = FBXShapeKeyNames.buildShapeKeyName(animMesh, meshName, animIndex);

                if (!shapeKeyName.isBlank())
                {
                    names.add(shapeKeyName);
                }
            }
        }

        return names;
    }
}