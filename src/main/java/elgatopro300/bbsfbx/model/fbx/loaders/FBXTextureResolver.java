package elgatopro300.bbsfbx.model.fbx.loaders;

import elgatopro300.bbsfbx.model.fbx.FBXMesh;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the default model texture and registers each mesh's material
 * (plus its per-material texture, when one exists) on a ModelInstance.
 */
public final class FBXTextureResolver
{
    private FBXTextureResolver() {}

    /**
     * Picks the texture a model falls back to when a mesh has no
     * material-specific texture: first the FBX material's own diffuse
     * texture (if the first mesh carries one), then any image file among the
     * model's links.
     */
    public static Link resolveDefaultTexture(BOBJData data, Link model, Collection<Link> links)
    {
        Link textureLink = null;

        if (!data.meshes.isEmpty() && data.meshes.get(0) instanceof FBXMesh mesh)
        {
            if (mesh.texture != null && !mesh.texture.isEmpty())
            {
                Link specificLink = model.combine(mesh.texture);
                if (links.contains(specificLink))
                {
                    textureLink = specificLink;
                }
                else
                {
                    for (Link l : links)
                    {
                        if (l.path.endsWith(mesh.texture))
                        {
                            textureLink = l;
                            break;
                        }
                    }
                }
            }
        }

        if (textureLink == null)
        {
            for (Link l : links)
            {
                String path = l.path.toLowerCase();

                if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg"))
                {
                    textureLink = l;
                    break;
                }
            }
        }

        return textureLink;
    }

    /**
     * Registers each unique material name used by the compiled meshes onto
     * the ModelInstance, resolving a per-material texture folder where one
     * exists (BBS FS's per-material texture picker relies on this).
     */
    public static void registerMaterials(ModelInstance modelInstance, List<CompiledData> compiledMeshes, AssetProvider provider, Link model, Collection<Link> links)
    {
        Set<String> registeredMaterials = new HashSet<>();

        for (CompiledData mesh : compiledMeshes)
        {
            String material = mesh.mesh.name;

            if (material == null || material.isEmpty() || !registeredMaterials.add(material))
            {
                continue;
            }

            modelInstance.materials.add(material);

            Link materialTexture = IModelLoader.findMaterialTexture(links, model, material);

            if (materialTexture != null)
            {
                modelInstance.materialTextures.put(material, materialTexture);
            }
            else
            {
                IModelLoader.ensureMaterialFolder(provider, model, material);
            }
        }
    }
}