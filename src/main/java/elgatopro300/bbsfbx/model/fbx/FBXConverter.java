package elgatopro300.bbsfbx.model.fbx;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.Face;
import mchorse.bbs_mod.bobj.BOBJLoader.IndexGroup;
import mchorse.bbs_mod.bobj.BOBJLoader.Vertex;
import mchorse.bbs_mod.bobj.BOBJLoader.Weight;

import org.joml.Matrix4f;
import org.joml.Vector2d;
import org.joml.Vector3f;

import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.AIVertexWeight;
import org.lwjgl.assimp.Assimp;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FBXConverter converts an Assimp {@link AIScene} into BBS FS {@link BOBJData}.
 *
 * <p>Coordinate handling:
 * <ul>
 *   <li>Blender bakes a 100x (cm->m) scale into the FBX node transform, so
 *       vertices are pre-multiplied by {@link #FBX_UNIT_SCALE} (0.01).</li>
 *   <li>No auto-centering, grounding, or height-normalization. The model keeps
 *       the exact position/scale it had in Blender.</li>
 *   <li>For non-skinned scenes, each object becomes its own bone named after the
 *       object, anchored at that object's Blender origin, so every mesh pivots
 *       around its own point (requires OptimizeGraph to be OFF in the loader).</li>
 * </ul>
 */
@SuppressWarnings({"resource", "DataFlowIssue"})
public class FBXConverter
{
    /** Undoes the 100x cm->m scale Blender bakes into FBX node transforms. */
    private static final float FBX_UNIT_SCALE = 0.01f;

    /**
     * Converts an Assimp scene into BOBJData.
     */
    public static BOBJData convert(AIScene scene)
    {
        List<Vertex> vertices = new ArrayList<>();
        List<Vector2d> textures = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<BOBJMesh> meshes = new ArrayList<>();
        Map<String, BOBJAction> actions = new HashMap<>();
        Map<String, BOBJArmature> armatures = new HashMap<>();

        AINode rootNode = scene.mRootNode();
        if (rootNode == null)
        {
            return new BOBJData(vertices, textures, normals, meshes, actions, armatures);
        }

        FBXMetadata metadata = new FBXMetadata(scene);
        Matrix4f rootCorrection = buildRootCorrection(metadata);

        Map<Integer, String> meshNodeNames = new HashMap<>();
        Map<Integer, Matrix4f> meshTransforms = collectMeshTransforms(rootNode, meshNodeNames);

        Map<String, AIBone> skinnedBones = new HashMap<>();
        int numMeshes = scene.mNumMeshes();
        for (int i = 0; i < numMeshes; i++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            int numBones = aiMesh.mNumBones();
            for (int j = 0; j < numBones; j++)
            {
                AIBone aiBone = AIBone.create(aiMesh.mBones().get(j));
                skinnedBones.putIfAbsent(aiBone.mName().dataString(), aiBone);
            }
        }

        BOBJArmature globalArmature = new BOBJArmature("Armature");
        armatures.put(globalArmature.name, globalArmature);

        float[] globalScale = {FBX_UNIT_SCALE};
        Set<String> neededNodes = new HashSet<>();

        if (!skinnedBones.isEmpty())
        {
            markNeededNodes(rootNode, skinnedBones.keySet(), neededNodes);

            // Skinned vertices are already in bind (meter) space and never
            // receive the node's baked 100x cm scale, so the 0.01 unit-cancel
            // must NOT apply. Set to 1.0 = true scale (fixes the ~150x shrink).
            globalScale[0] = 1.0f;
        }
        else
        {
            // One bone per object, named after the object, anchored at its
            // Blender origin so each mesh pivots around its own point.
            for (int i = 0; i < numMeshes; i++)
            {
                String objectName = meshNodeNames.getOrDefault(i, "object_" + i);
                if (globalArmature.bones.containsKey(objectName)) continue;

                Matrix4f nodeWorld = meshTransforms.get(i);
                Matrix4f boneRest = nodeWorld == null
                        ? new Matrix4f(rootCorrection)
                        : new Matrix4f(rootCorrection).mul(nodeWorld);

                boneRest.m30(boneRest.m30() * globalScale[0]);
                boneRest.m31(boneRest.m31() * globalScale[0]);
                boneRest.m32(boneRest.m32() * globalScale[0]);
                boneRest.normalize3x3();

                globalArmature.addBone(new BOBJBone(globalArmature.bones.size(), objectName, "", boneRest));
            }
        }

        // Respect Blender's coordinates exactly: no centering/grounding/normalization.
        float offsetX = 0;
        float offsetY = 0;
        float offsetZ = 0;

        if (!skinnedBones.isEmpty())
        {
            Matrix4f initialGlobal = new Matrix4f().translate(offsetX, offsetY, offsetZ);
            processNodes(rootNode, "", initialGlobal, globalArmature, skinnedBones, neededNodes, globalScale, rootCorrection, offsetX, offsetY, offsetZ);
        }

        for (int i = 0; i < numMeshes; i++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            String objectBoneName = meshNodeNames.getOrDefault(i, "object_" + i);
            processMesh(scene, aiMesh, i, vertices, textures, normals, meshes, globalArmature, globalScale[0], rootCorrection, offsetX, offsetY, offsetZ, meshTransforms, objectBoneName);
        }

        for (Vertex vertex : vertices)
        {
            if (vertex.weights.isEmpty())
            {
                if (!globalArmature.orderedBones.isEmpty())
                {
                    vertex.weights.add(new Weight(globalArmature.orderedBones.get(0).name, 1.0f));
                }
            }
            else
            {
                vertex.eliminateTinyWeights();

                float sum = 0;
                for (Weight w : vertex.weights)
                {
                    sum += w.factor;
                }

                if (sum > 0 && Math.abs(sum - 1.0f) > 0.001f)
                {
                    for (Weight w : vertex.weights)
                    {
                        w.factor /= sum;
                    }
                }
            }
        }

        return new BOBJData(vertices, textures, normals, meshes, actions, armatures);
    }

    private static float findFirstScale(AINode node, Set<String> neededNodes, Map<String, AIBone> skinnedBones)
    {
        String name = node.mName().dataString();
        if (neededNodes.contains(name) && skinnedBones.containsKey(name))
        {
            Matrix4f ibm = toMatrix4f(skinnedBones.get(name).mOffsetMatrix());
            Matrix4f bindMatrix = ibm.invert();
            Vector3f scale = new Vector3f();
            bindMatrix.getScale(scale);
            return scale.x;
        }

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            float scale = findFirstScale(AINode.create(children.get(i)), neededNodes, skinnedBones);
            if (scale != -1) return scale;
        }

        return -1;
    }

    /**
     * Recursively marks nodes that belong to the armature (a node is needed if
     * it, or any descendant, is a skinned bone).
     */
    private static boolean markNeededNodes(AINode node, Set<String> skinnedBones, Set<String> neededNodes)
    {
        String name = node.mName().dataString();
        boolean needed = skinnedBones.contains(name);

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            AINode child = AINode.create(children.get(i));
            if (markNeededNodes(child, skinnedBones, neededNodes))
            {
                needed = true;
            }
        }

        if (needed)
        {
            neededNodes.add(name);
        }

        return needed;
    }

    /**
     * Recursively processes nodes to build the bone hierarchy (skinned path).
     */
    private static void processNodes(AINode node, String parentName, Matrix4f parentGlobal, BOBJArmature armature, Map<String, AIBone> skinnedBones, Set<String> neededNodes, float[] globalScale, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ)
    {
        String name = node.mName().dataString();
        Matrix4f local = toMatrix4f(node.mTransformation());
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);

        String nextParent = parentName;
        boolean skip = name.equals("RootNode") || name.equals("Armature");

        if (neededNodes.contains(name) && !skip)
        {
            Matrix4f boneMat;
            if (skinnedBones.containsKey(name))
            {
                Matrix4f offset = toMatrix4f(skinnedBones.get(name).mOffsetMatrix());
                Matrix4f invCorrection = new Matrix4f(rootCorrection).invert();
                offset.mul(invCorrection);
                boneMat = offset.invert();

                boneMat.m30(boneMat.m30() * globalScale[0]);
                boneMat.m31(boneMat.m31() * globalScale[0]);
                boneMat.m32(boneMat.m32() * globalScale[0]);

                boneMat.m30(boneMat.m30() + offsetX);
                boneMat.m31(boneMat.m31() + offsetY);
                boneMat.m32(boneMat.m32() + offsetZ);
            }
            else
            {
                boneMat = new Matrix4f(global);
            }

            if (armature.bones.isEmpty())
            {
                if (!skinnedBones.containsKey(name))
                {
                    boneMat.mul(rootCorrection);
                }
            }

            boneMat.normalize3x3();

            BOBJBone bone = new BOBJBone(armature.bones.size(), name, parentName, boneMat);
            armature.addBone(bone);
            nextParent = name;
        }

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            AINode child = AINode.create(children.get(i));
            processNodes(child, nextParent, global, armature, skinnedBones, neededNodes, globalScale, rootCorrection, offsetX, offsetY, offsetZ);
        }
    }

    /**
     * Converts an Assimp mesh to a BOBJMesh, transforming vertices/normals into
     * BBS space and extracting bone weights.
     *
     * <p>For non-skinned meshes the full node transform (including translation)
     * is applied, and every vertex is weighted to the object's own bone
     * ({@code objectBoneName}) so it pivots at its Blender origin.
     */
    private static void processMesh(AIScene scene, AIMesh aiMesh, int meshIndex, List<Vertex> vertices, List<Vector2d> textures, List<Vector3f> normals, List<BOBJMesh> meshes, BOBJArmature armature, float scaleFactor, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ, Map<Integer, Matrix4f> meshTransforms, String objectBoneName)
    {
        FBXMesh mesh = new FBXMesh(aiMesh.mName().dataString());
        mesh.armatureName = armature.name;
        mesh.armature = armature;

        Matrix4f meshTransform = meshTransforms.get(meshIndex);
        boolean skinned = aiMesh.mNumBones() > 0;
        boolean applyNodeTransform = !skinned && meshTransform != null;

        int vertexBaseIndex = vertices.size();
        int textureBaseIndex = textures.size();
        int normalBaseIndex = normals.size();

        Vector3f pos = new Vector3f();

        AIVector3D.Buffer aiVertices = aiMesh.mVertices();
        while (aiVertices.remaining() > 0)
        {
            AIVector3D aiVertex = aiVertices.get();

            pos.set(aiVertex.x(), aiVertex.y(), aiVertex.z());
            if (applyNodeTransform)
            {
                // Full node transform: rotation, scale AND translation (the
                // object's Blender position/pivot).
                meshTransform.transformPosition(pos);
            }

            pos.mul(scaleFactor);
            rootCorrection.transformPosition(pos);

            pos.x += offsetX;
            pos.y += offsetY;
            pos.z += offsetZ;

            vertices.add(new Vertex(pos.x, pos.y, pos.z));
        }

        AIVector3D.Buffer aiNormals = aiMesh.mNormals();
        if (aiNormals != null)
        {
            while (aiNormals.remaining() > 0)
            {
                AIVector3D aiNormal = aiNormals.get();
                Vector3f norm = new Vector3f(aiNormal.x(), aiNormal.y(), aiNormal.z());
                if (applyNodeTransform)
                {
                    meshTransform.transformDirection(norm);
                }

                rootCorrection.transformDirection(norm);
                norm.normalize();

                normals.add(norm);
            }
        }
        else
        {
            int count = aiMesh.mNumVertices();
            for (int i = 0; i < count; i++)
            {
                normals.add(new Vector3f(0, 1, 0));
            }
        }

        AIVector3D.Buffer aiTextureCoords = aiMesh.mTextureCoords(0);
        if (aiTextureCoords != null)
        {
            while (aiTextureCoords.remaining() > 0)
            {
                AIVector3D aiTexCoord = aiTextureCoords.get();
                textures.add(new Vector2d(aiTexCoord.x(), aiTexCoord.y()));
            }
        }
        else
        {
            int count = aiMesh.mNumVertices();
            for (int i = 0; i < count; i++)
            {
                textures.add(new Vector2d(0, 0));
            }
        }

        int numFaces = aiMesh.mNumFaces();
        for (int i = 0; i < numFaces; i++)
        {
            IntBuffer faceIndices = aiMesh.mFaces().get(i).mIndices();
            if (faceIndices.remaining() == 3)
            {
                Face face = new Face();
                for (int j = 0; j < 3; j++)
                {
                    int index = faceIndices.get(j);
                    IndexGroup group = new IndexGroup();
                    group.idxPos = vertexBaseIndex + index;
                    group.idxVecNormal = normalBaseIndex + index;
                    group.idxTextCoord = textureBaseIndex + index;
                    face.idxGroups[j] = group;
                }
                mesh.faces.add(face);
            }
        }

        if (skinned)
        {
            int numBones = aiMesh.mNumBones();
            for (int i = 0; i < numBones; i++)
            {
                AIBone aiBone = AIBone.create(aiMesh.mBones().get(i));
                String boneName = aiBone.mName().dataString();

                AIVertexWeight.Buffer aiWeights = aiBone.mWeights();
                while (aiWeights.remaining() > 0)
                {
                    AIVertexWeight aiWeight = aiWeights.get();
                    int vertexId = aiWeight.mVertexId();
                    float weight = aiWeight.mWeight();

                    if (vertexId + vertexBaseIndex < vertices.size())
                    {
                        vertices.get(vertexBaseIndex + vertexId).weights.add(new Weight(boneName, weight));
                    }
                }
            }
        }
        else if (objectBoneName != null)
        {
            // Weight every vertex of this object to its own bone.
            for (int v = vertexBaseIndex; v < vertices.size(); v++)
            {
                vertices.get(v).weights.add(new Weight(objectBoneName, 1.0f));
            }
        }

        int materialIndex = aiMesh.mMaterialIndex();
        if (materialIndex >= 0 && materialIndex < scene.mNumMaterials())
        {
            AIMaterial material = AIMaterial.create(scene.mMaterials().get(materialIndex));
            AIString path = AIString.calloc();

            if (Assimp.aiGetMaterialTexture(material, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null) == Assimp.aiReturn_SUCCESS)
            {
                String texturePath = path.dataString();

                if (!texturePath.isEmpty())
                {
                    texturePath = texturePath.replace('\\', '/');
                    int lastSlash = texturePath.lastIndexOf('/');

                    if (lastSlash >= 0)
                    {
                        texturePath = texturePath.substring(lastSlash + 1);
                    }

                    mesh.texture = texturePath;
                }
            }

            path.free();
        }

        meshes.add(mesh);
    }

    private static Matrix4f toMatrix4f(AIMatrix4x4 m)
    {
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4()
        );
    }

    private static Matrix4f buildRootCorrection(FBXMetadata metadata)
    {
        Matrix4f correction = new Matrix4f();

        if (metadata.upAxis == 2)
        {
            correction.rotateX((float) Math.toRadians(-90));
        }
        else if (metadata.upAxis == 0)
        {
            correction.rotateZ((float) Math.toRadians(90));
        }

        correction.rotateY((float) Math.PI);

        return correction;
    }

    private static Map<Integer, Matrix4f> collectMeshTransforms(AINode rootNode, Map<Integer, String> meshNodeNames)
    {
        Map<Integer, Matrix4f> meshTransforms = new HashMap<>();
        collectMeshTransforms(rootNode, new Matrix4f(), meshTransforms, meshNodeNames);
        return meshTransforms;
    }

    private static void collectMeshTransforms(AINode node, Matrix4f parentGlobal, Map<Integer, Matrix4f> meshTransforms, Map<Integer, String> meshNodeNames)
    {
        Matrix4f local = toMatrix4f(node.mTransformation());
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);

        String nodeName = node.mName().dataString();
        IntBuffer meshIndices = node.mMeshes();
        int numMeshes = node.mNumMeshes();
        for (int i = 0; i < numMeshes; i++)
        {
            int meshIndex = meshIndices.get(i);
            meshTransforms.putIfAbsent(meshIndex, new Matrix4f(global));
            meshNodeNames.putIfAbsent(meshIndex, nodeName);
        }

        PointerBuffer children = node.mChildren();
        int numChildren = node.mNumChildren();
        for (int i = 0; i < numChildren; i++)
        {
            collectMeshTransforms(AINode.create(children.get(i)), global, meshTransforms, meshNodeNames);
        }
    }

    /**
     * An extension of BOBJMesh to store the texture filename.
     */
    public static class FBXMesh extends BOBJMesh
    {
        public String texture;

        public FBXMesh(String name)
        {
            super(name);
        }
    }
}