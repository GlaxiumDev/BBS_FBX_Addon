package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJGroup;
import mchorse.bbs_mod.bobj.BOBJKeyframe;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.CompiledData;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.model.loaders.IModelLoader;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import org.joml.Vector2d;
import org.joml.Vector3f;

import org.lwjgl.BufferUtils;
import org.lwjgl.assimp.AIPropertyStore;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import elgatopro300.bbsfbx.model.fbx.FBXConverter;

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

            ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
            buffer.put(bytes);
            buffer.flip();

            AIPropertyStore store = Assimp.aiCreatePropertyStore();
            AIScene scene;
            try
            {
                assert store != null;
                Assimp.aiSetImportPropertyInteger(store, Assimp.AI_CONFIG_IMPORT_FBX_PRESERVE_PIVOTS, 0);
                Assimp.aiSetImportPropertyFloat(store, Assimp.AI_CONFIG_GLOBAL_SCALE_FACTOR_KEY, 1.0f);

                scene = Assimp.aiImportFileFromMemoryWithProperties(buffer,
                        Assimp.aiProcess_Triangulate |
                                Assimp.aiProcess_FlipUVs |
                                Assimp.aiProcess_LimitBoneWeights |
                                Assimp.aiProcess_JoinIdenticalVertices |
                                Assimp.aiProcess_GenSmoothNormals |
                                Assimp.aiProcess_PopulateArmatureData,
                        (ByteBuffer) null,
                        store);
            }
            finally
            {
                assert store != null;
                Assimp.aiReleasePropertyStore(store);
            }

            if (scene == null)
            {
                System.err.println("Error loading FBX model: " + Assimp.aiGetErrorString());
                return null;
            }

            // Keep the scene alive through the whole build; the material loop below
            // reads embedded textures out of it, so it must not be released early.
            try
            {
                BOBJData data = FBXConverter.convert(scene);

                data.initiateArmatures();

                /* BBS FS's BOBJModel takes one CompiledData per mesh (instead of
                 * CML's single merged CompiledData), and FS's renderer reads the
                 * material name from CompiledData.mesh, so each mesh is compiled
                 * separately with its mesh reference attached. */
                List<CompiledData> compiledMeshes = new ArrayList<>();

                for (BOBJMesh mesh : data.meshes)
                {
                    compiledMeshes.add(this.compile(data, mesh));
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

                Animations animations = new Animations(models.parser);

                for (BOBJAction action : data.actions.values())
                {
                    Animation animation = new Animation(action.name, models.parser);
                    animation.setLength(action.getDuration() / 20.0);

                    for (BOBJGroup group : action.groups.values())
                    {
                        AnimationPart part = new AnimationPart(models.parser);

                        for (BOBJChannel channel : group.channels)
                        {
                            KeyframeChannel<MolangExpression> targetChannel = switch (channel.path) {
                                case "location.x" -> part.x;
                                case "location.y" -> part.y;
                                case "location.z" -> part.z;
                                case "rotation.x" -> part.rx;
                                case "rotation.y" -> part.ry;
                                case "rotation.z" -> part.rz;
                                case "scale.x" -> part.sx;
                                case "scale.y" -> part.sy;
                                case "scale.z" -> part.sz;
                                default -> null;
                            };

                            if (targetChannel != null)
                            {
                                for (BOBJKeyframe kf : channel.keyframes)
                                {
                                    targetChannel.insert(kf.frame, new MolangValue(models.parser, new Constant(kf.value)));
                                }
                            }
                        }

                        animation.parts.put(group.name, part);
                    }

                    animations.add(animation);
                }

                Link textureLink = null;

                /*
                 Try to find texture from mesh data first
                 */
                if (!data.meshes.isEmpty() && data.meshes.get(0) instanceof FBXConverter.FBXMesh mesh)
                {
                    if (mesh.texture != null && !mesh.texture.isEmpty())
                    {
                        Link specificLink = model.combine(mesh.texture);
                        if (links.contains(specificLink))
                        {
                            textureLink = specificLink;
                        } else
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

                ModelInstance modelInstance = new ModelInstance(id, bobjModel, animations, textureLink);

                /* In BBS FS each mesh is its own material (keyed by mesh name),
                 * mirroring FS's BOBJModelLoader: register the material and try to
                 * resolve a per-material texture folder; meshes without one fall
                 * back to the model texture.
                 *
                 * Several meshes can share the same material name (e.g. multiple
                 * Blender objects using one material), so materials are
                 * deduplicated here - each unique name is only registered once. */
                Set<String> registeredMaterials = new HashSet<>();

                for (CompiledData mesh : compiledMeshes)
                {
                    String material = mesh.mesh.name;

                    if (material == null || material.isEmpty() || !registeredMaterials.add(material))
                    {
                        continue;
                    }

                    modelInstance.materials.add(material);

                    /* Always ensure the real, loader-resolved material folder exists.
                     * This doubles as the fix for the "model won't load until a
                     * folder is created" discovery bug, since a subfolder now always
                     * exists. Then extract any embedded texture INTO that exact
                     * folder, so nothing ends up in a stray temp directory. */
                    IModelLoader.ensureMaterialFolder(models.provider, model, material);
                    File matFolder = models.provider.getFile(model.combine("textures/" + material));
                    boolean extracted = FBXConverter.extractEmbeddedTexture(scene, material, matFolder);

                    Link materialTexture = IModelLoader.findMaterialTexture(links, model, material);

                    /* findMaterialTexture only sees files that existed in `links`
                     * BEFORE this FBX import ran, so it can never see a texture
                     * (embedded or baked solid-color) we just wrote above on this
                     * very load. Resolve that Link directly instead of waiting
                     * for a second load/rescan to pick it up. */
                    if (materialTexture == null && extracted)
                    {
                        materialTexture = model.combine("textures/" + material + "/default.png");
                    }

                    if (materialTexture != null)
                    {
                        modelInstance.materialTextures.put(material, materialTexture);
                    }
                }

                modelInstance.applyConfig(config);
                return modelInstance;
            }
            finally
            {
                Assimp.aiReleaseImport(scene);
            }
        }
        catch (Throwable e)
        {
            System.err.println("Failed to load FBX model for " + id + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * Compiles a single mesh into a {@link CompiledData}, keeping the mesh
     * reference (BBS FS reads the material name from {@code CompiledData.mesh}
     * when rendering).
     */
    private CompiledData compile(BOBJData data, BOBJMesh mesh)
    {
        int totalVertices = 0;
        for (BOBJLoader.Face face : mesh.faces)
        {
            totalVertices += face.idxGroups.length;
        }

        float[] pos = new float[totalVertices * 3];
        float[] tex = new float[totalVertices * 2];
        float[] norm = new float[totalVertices * 3];
        float[] weights = new float[totalVertices * 4];
        int[] bones = new int[totalVertices * 4];
        int[] indices = new int[totalVertices];

        int vIndex = 0;  // Vertex index
        int wIndex = 0;  // Weight/Bone index (x4)
        int pIndex = 0;  // Position/Normal index (x3)
        int tIndex = 0;  // Texture index (x2)

        for (BOBJLoader.Face face : mesh.faces)
        {
            for (BOBJLoader.IndexGroup group : face.idxGroups)
            {
                BOBJLoader.Vertex v = data.vertices.get(group.idxPos);
                Vector2d t = data.textures.get(group.idxTextCoord);
                Vector3f n = data.normals.get(group.idxVecNormal);

                pos[pIndex] = v.x; pos[pIndex+1] = v.y; pos[pIndex+2] = v.z;
                norm[pIndex] = n.x; norm[pIndex+1] = n.y; norm[pIndex+2] = n.z;
                pIndex += 3;

                tex[tIndex] = (float) t.x; tex[tIndex+1] = (float) t.y;
                tIndex += 2;

                if (v.weights.isEmpty())
                {
                    weights[wIndex] = 1.0f; bones[wIndex] = 0;
                    weights[wIndex+1] = 0.0f; bones[wIndex+1] = -1;
                    weights[wIndex+2] = 0.0f; bones[wIndex+2] = -1;
                    weights[wIndex+3] = 0.0f; bones[wIndex+3] = -1;
                } else
                {
                    for (int i = 0; i < 4; i++)
                    {
                        if (i < v.weights.size())
                        {
                            BOBJLoader.Weight w = v.weights.get(i);
                            weights[wIndex+i] = w.factor;
                            BOBJBone bone = mesh.armature != null ? mesh.armature.bones.get(w.name) : null;
                            bones[wIndex+i] = (bone == null ? 0 : bone.index);
                        } else
                        {
                            weights[wIndex+i] = 0f;
                            bones[wIndex+i] = -1;
                        }
                    }
                }
                wIndex += 4;
                indices[vIndex] = vIndex;
                vIndex++;
            }
        }

        return new CompiledData(
                pos, tex, norm, weights, bones, indices,
                mesh
        );
    }
}