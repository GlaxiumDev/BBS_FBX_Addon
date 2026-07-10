package elgatopro300.bbsfbx.model.fbx;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJChannel;
import mchorse.bbs_mod.bobj.BOBJGroup;
import mchorse.bbs_mod.bobj.BOBJKeyframe;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.Face;
import mchorse.bbs_mod.bobj.BOBJLoader.IndexGroup;
import mchorse.bbs_mod.bobj.BOBJLoader.Vertex;
import mchorse.bbs_mod.bobj.BOBJLoader.Weight;

import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2d;
import org.joml.Vector3f;

import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIAnimation;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AINodeAnim;
import org.lwjgl.assimp.AIQuatKey;
import org.lwjgl.assimp.AIQuaternion;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.AIVectorKey;
import org.lwjgl.assimp.AIVertexWeight;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;


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

    /** Playback rate BBS animations run at (frames per second). */
    private static final int FRAMES_PER_SECOND = 20;

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
        dumpNodeTransforms(rootNode, "");

        Matrix4f rootCorrection = buildRootCorrection(metadata);

        Map<Integer, String> meshNodeNames = new HashMap<>();
        Map<Integer, Matrix4f> meshTransforms = collectMeshTransforms(rootNode, meshNodeNames);

        Map<String, AIBone> skinnedBones = new HashMap<>();
        Map<String, Integer> skinnedBoneMeshIndex = new HashMap<>();
        int numMeshes = scene.mNumMeshes();
        for (int i = 0; i < numMeshes; i++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            int numBones = aiMesh.mNumBones();
            for (int j = 0; j < numBones; j++)
            {
                AIBone aiBone = AIBone.create(aiMesh.mBones().get(j));
                String boneName = aiBone.mName().dataString();
                skinnedBones.putIfAbsent(boneName, aiBone);
                skinnedBoneMeshIndex.putIfAbsent(boneName, i);
            }
        }

        Map<String, Matrix4f> boneMeshRotations = new HashMap<>();
        for (Map.Entry<String, Integer> entry : skinnedBoneMeshIndex.entrySet())
        {
            Matrix4f meshWorld = meshTransforms.get(entry.getValue());
            if (meshWorld != null)
            {
                Quaternionf rot = new Quaternionf();
                meshWorld.getUnnormalizedRotation(rot);
                boneMeshRotations.put(entry.getKey(), new Matrix4f().rotation(rot));
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
            processNodes(rootNode, "", initialGlobal, globalArmature, skinnedBones, boneMeshRotations, neededNodes, globalScale, rootCorrection, offsetX, offsetY, offsetZ);
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

        /* Extract skeletal animations for armature (FBX with armature) models,
         * mirroring how BOBJ models carry actions. Only meaningful when the
         * model is actually skinned, since animation groups are keyed by the
         * armature's bone names. */
        if (scene.mNumAnimations() > 0 && !skinnedBones.isEmpty())
        {
            Map<String, Matrix4f> nodeLocals = new HashMap<>();
            collectNodeLocals(rootNode, nodeLocals);
            processAnimations(scene, actions, globalArmature, nodeLocals, globalScale[0]);
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
    private static void processNodes(AINode node, String parentName, Matrix4f parentGlobal, BOBJArmature armature, Map<String, AIBone> skinnedBones, Map<String, Matrix4f> boneMeshRotations, Set<String> neededNodes, float[] globalScale, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ)
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
                Matrix4f boneWorld = offset.invert();

                Matrix4f meshRotation = boneMeshRotations.get(name);
                if (meshRotation != null)
                {
                    boneWorld = new Matrix4f(meshRotation).mul(boneWorld);
                }

                boneMat = new Matrix4f(rootCorrection).mul(boneWorld);

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
            processNodes(child, nextParent, global, armature, skinnedBones, boneMeshRotations, neededNodes, globalScale, rootCorrection, offsetX, offsetY, offsetZ);
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
        Matrix4f meshRotationOnly = null;
        if (skinned && meshTransform != null)
        {
            Quaternionf rot = new Quaternionf();
            meshTransform.getUnnormalizedRotation(rot);
            meshRotationOnly = new Matrix4f().rotation(rot);
        }

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
            else if (meshRotationOnly != null)
            {
                meshRotationOnly.transformPosition(pos);
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

            AIString nameStr = AIString.calloc();
            if (Assimp.aiGetMaterialString(material, Assimp.AI_MATKEY_NAME, 0, 0, nameStr) == Assimp.aiReturn_SUCCESS)
            {
                String materialName = nameStr.dataString();
                if (!materialName.isEmpty())
                {
                    mesh.name = materialName;
                }
            }
            nameStr.free();

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

    /**
     * Collects every node's local (relative-to-parent) transform, keyed by node
     * name. The animation extractor uses these as the rest pose against which
     * per-frame local transforms are diffed.
     */
    private static void collectNodeLocals(AINode node, Map<String, Matrix4f> map)
    {
        map.put(node.mName().dataString(), toMatrix4f(node.mTransformation()));

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            collectNodeLocals(AINode.create(children.get(i)), map);
        }
    }

    /**
     * Converts every Assimp animation clip in the scene into a BOBJAction,
     * mirroring the channel layout that BOBJ models use (location.[xyz],
     * rotation.[xyz], scale.[xyz] per bone group). Each group is keyed by its
     * bone name so the BBS animator can match it to a BOBJBone.
     */
    private static void processAnimations(AIScene scene, Map<String, BOBJAction> actions, BOBJArmature armature, Map<String, Matrix4f> nodeLocals, float globalScale)
    {
        int numAnimations = scene.mNumAnimations();

        for (int a = 0; a < numAnimations; a++)
        {
            AIAnimation aiAnimation = AIAnimation.create(scene.mAnimations().get(a));

            String name = aiAnimation.mName().dataString();
            if (name.isEmpty())
            {
                name = "animation_" + a;
            }

            double ticksPerSecond = aiAnimation.mTicksPerSecond();
            if (ticksPerSecond == 0)
            {
                ticksPerSecond = 24.0;
            }

            BOBJAction action = new BOBJAction(name);

            int numChannels = aiAnimation.mNumChannels();
            for (int c = 0; c < numChannels; c++)
            {
                AINodeAnim nodeAnim = AINodeAnim.create(aiAnimation.mChannels().get(c));
                String nodeName = nodeAnim.mNodeName().dataString();

                /* Only animate nodes that ended up as bones in the armature. */
                if (!armature.bones.containsKey(nodeName))
                {
                    continue;
                }

                Matrix4f rest = nodeLocals.get(nodeName);
                if (rest == null)
                {
                    rest = new Matrix4f();
                }

                processNodeAnimation(nodeAnim, nodeName, rest, action, ticksPerSecond, globalScale);
            }

            if (!action.groups.isEmpty())
            {
                actions.put(name, action);
            }
        }
    }

    /*
     * Bakes a single node's animation channel into a BOBJGroup.
     *
     * BBS applies a bone's animation Transform as a delta on top of its rest
     * pose (relBoneMat * T * Rz * Ry * Rx * S), so the values stored here are
     * the node's animated LOCAL transform expressed relative to its rest local
     * transform: delta = restLocal^-1 * animatedLocal.
     *
     * Rotation is emitted as ZYX euler angles in RADIANS (matching the
     * rotateZ/rotateY/rotateX order in BOBJBone#applyTransformations), and
     * translation is multiplied by globalScale so it matches the scale the bind
     * pose bone positions were built at.
     */
    private static void processNodeAnimation(AINodeAnim nodeAnim, String nodeName, Matrix4f rest, BOBJAction action, double ticksPerSecond, float globalScale)
    {
        int numPos = nodeAnim.mNumPositionKeys();
        int numRot = nodeAnim.mNumRotationKeys();
        int numScale = nodeAnim.mNumScalingKeys();

        double[] posTimes = new double[numPos];
        Vector3f[] posVals = new Vector3f[numPos];
        AIVectorKey.Buffer posKeys = nodeAnim.mPositionKeys();
        for (int i = 0; i < numPos; i++)
        {
            AIVectorKey key = posKeys.get(i);
            posTimes[i] = key.mTime();
            AIVector3D v = key.mValue();
            posVals[i] = new Vector3f(v.x(), v.y(), v.z());
        }

        double[] rotTimes = new double[numRot];
        Quaternionf[] rotVals = new Quaternionf[numRot];
        AIQuatKey.Buffer rotKeys = nodeAnim.mRotationKeys();
        for (int i = 0; i < numRot; i++)
        {
            AIQuatKey key = rotKeys.get(i);
            rotTimes[i] = key.mTime();
            AIQuaternion q = key.mValue();
            rotVals[i] = new Quaternionf(q.x(), q.y(), q.z(), q.w());
        }

        double[] scaleTimes = new double[numScale];
        Vector3f[] scaleVals = new Vector3f[numScale];
        AIVectorKey.Buffer scaleKeys = nodeAnim.mScalingKeys();
        for (int i = 0; i < numScale; i++)
        {
            AIVectorKey key = scaleKeys.get(i);
            scaleTimes[i] = key.mTime();
            AIVector3D v = key.mValue();
            scaleVals[i] = new Vector3f(v.x(), v.y(), v.z());
        }

        /* Rest decomposition, used as defaults for any missing channel type so
         * that a missing component yields an identity delta. */
        Vector3f restT = new Vector3f();
        rest.getTranslation(restT);
        Vector3f restS = new Vector3f();
        rest.getScale(restS);
        Quaternionf restR = new Quaternionf();
        rest.getUnnormalizedRotation(restR);
        restR.normalize();

        TreeSet<Double> timeSet = new TreeSet<>();
        for (double t : posTimes) timeSet.add(t);
        for (double t : rotTimes) timeSet.add(t);
        for (double t : scaleTimes) timeSet.add(t);

        if (timeSet.isEmpty())
        {
            return;
        }

        BOBJGroup group = new BOBJGroup(nodeName);

        BOBJChannel tx = new BOBJChannel("location.x", 0);
        BOBJChannel ty = new BOBJChannel("location.y", 1);
        BOBJChannel tz = new BOBJChannel("location.z", 2);

        BOBJChannel rx = new BOBJChannel("rotation.x", 3);
        BOBJChannel ry = new BOBJChannel("rotation.y", 4);
        BOBJChannel rz = new BOBJChannel("rotation.z", 5);

        BOBJChannel sx = new BOBJChannel("scale.x", 6);
        BOBJChannel sy = new BOBJChannel("scale.y", 7);
        BOBJChannel sz = new BOBJChannel("scale.z", 8);

        Matrix4f restInv = new Matrix4f(rest).invert();
        Matrix4f animLocal = new Matrix4f();
        Matrix4f delta = new Matrix4f();
        Vector3f dt = new Vector3f();
        Vector3f ds = new Vector3f();
        Quaternionf dq = new Quaternionf();
        Vector3f euler = new Vector3f();

        boolean scaleVaries = false;

        for (double time : timeSet)
        {
            Vector3f t = (numPos > 0) ? interpolateVector(posTimes, posVals, time) : new Vector3f(restT);
            Quaternionf r = (numRot > 0) ? interpolateQuat(rotTimes, rotVals, time) : new Quaternionf(restR);
            Vector3f s = (numScale > 0) ? interpolateVector(scaleTimes, scaleVals, time) : new Vector3f(restS);

            animLocal.translationRotateScale(t.x, t.y, t.z, r.x, r.y, r.z, r.w, s.x, s.y, s.z);
            restInv.mul(animLocal, delta);

            delta.getTranslation(dt);
            delta.getScale(ds);
            delta.getUnnormalizedRotation(dq);
            dq.normalize();
            quatToEulerZYX(dq, euler);

            float frame = (float) (time / ticksPerSecond * FRAMES_PER_SECOND);

            tx.keyframes.add(new BOBJKeyframe(frame, dt.x * globalScale));
            ty.keyframes.add(new BOBJKeyframe(frame, dt.y * globalScale));
            tz.keyframes.add(new BOBJKeyframe(frame, dt.z * globalScale));

            rx.keyframes.add(new BOBJKeyframe(frame, euler.x));
            ry.keyframes.add(new BOBJKeyframe(frame, euler.y));
            rz.keyframes.add(new BOBJKeyframe(frame, euler.z));

            sx.keyframes.add(new BOBJKeyframe(frame, ds.x));
            sy.keyframes.add(new BOBJKeyframe(frame, ds.y));
            sz.keyframes.add(new BOBJKeyframe(frame, ds.z));

            if (Math.abs(ds.x - 1f) > 1e-4f || Math.abs(ds.y - 1f) > 1e-4f || Math.abs(ds.z - 1f) > 1e-4f)
            {
                scaleVaries = true;
            }
        }

        group.channels.add(tx);
        group.channels.add(ty);
        group.channels.add(tz);

        group.channels.add(rx);
        group.channels.add(ry);
        group.channels.add(rz);

        /* Only emit scale channels when scale actually deviates from rest; BBS
         * defaults missing scale channels to 1, so this keeps clips lean. */
        if (scaleVaries)
        {
            group.channels.add(sx);
            group.channels.add(sy);
            group.channels.add(sz);
        }

        action.groups.put(nodeName, group);
    }

    /**
     * Linearly interpolates a vector channel at the given (tick) time.
     */
    private static Vector3f interpolateVector(double[] times, Vector3f[] values, double time)
    {
        int n = times.length;

        if (n == 0) return new Vector3f();
        if (time <= times[0]) return new Vector3f(values[0]);
        if (time >= times[n - 1]) return new Vector3f(values[n - 1]);

        for (int i = 0; i < n - 1; i++)
        {
            if (time >= times[i] && time <= times[i + 1])
            {
                double span = times[i + 1] - times[i];
                float factor = span <= 0 ? 0f : (float) ((time - times[i]) / span);
                Vector3f result = new Vector3f(values[i]);
                result.lerp(values[i + 1], factor);
                return result;
            }
        }

        return new Vector3f(values[n - 1]);
    }

    /**
     * Spherically interpolates a quaternion channel at the given (tick) time.
     */
    private static Quaternionf interpolateQuat(double[] times, Quaternionf[] values, double time)
    {
        int n = times.length;

        if (n == 0) return new Quaternionf();
        if (time <= times[0]) return new Quaternionf(values[0]);
        if (time >= times[n - 1]) return new Quaternionf(values[n - 1]);

        for (int i = 0; i < n - 1; i++)
        {
            if (time >= times[i] && time <= times[i + 1])
            {
                double span = times[i + 1] - times[i];
                float factor = span <= 0 ? 0f : (float) ((time - times[i]) / span);
                Quaternionf result = new Quaternionf(values[i]);
                result.slerp(values[i + 1], factor);
                return result.normalize();
            }
        }

        return new Quaternionf(values[n - 1]);
    }

    /**
     * Converts a quaternion (x, y, z, w) to ZYX euler angles (radians),
     * matching the rotateZ -> rotateY -> rotateX application order used by
     * BOBJBone#applyTransformations. dest = (rotX, rotY, rotZ).
     */
    private static void quatToEulerZYX(Quaternionf q, Vector3f dest)
    {
        float x = q.x;
        float y = q.y;
        float z = q.z;
        float w = q.w;

        double sinrCosp = 2.0 * (w * x + y * z);
        double cosrCosp = 1.0 - 2.0 * (x * x + y * y);
        double roll = Math.atan2(sinrCosp, cosrCosp);

        double sinp = 2.0 * (w * y - z * x);
        double pitch;
        if (Math.abs(sinp) >= 1.0)
        {
            pitch = Math.copySign(Math.PI / 2.0, sinp);
        }
        else
        {
            pitch = Math.asin(sinp);
        }

        double sinyCosp = 2.0 * (w * z + x * y);
        double cosyCosp = 1.0 - 2.0 * (y * y + z * z);
        double yaw = Math.atan2(sinyCosp, cosyCosp);

        dest.set((float) roll, (float) pitch, (float) yaw);
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
        if (meshIndices != null)
        {
            for (int i = 0; i < numMeshes; i++)
            {
                int meshIndex = meshIndices.get(i);
                meshTransforms.putIfAbsent(meshIndex, new Matrix4f(global));
                meshNodeNames.putIfAbsent(meshIndex, nodeName);
            }
        }

        PointerBuffer children = node.mChildren();
        int numChildren = node.mNumChildren();
        for (int i = 0; i < numChildren; i++)
        {
            collectMeshTransforms(AINode.create(children.get(i)), global, meshTransforms, meshNodeNames);
        }
    }

    private static void dumpNodeTransforms(AINode node, String indent)
    {
        Matrix4f local = toMatrix4f(node.mTransformation());
        Vector3f scale = new Vector3f();
        local.getScale(scale);
        Quaternionf rot = new Quaternionf();
        local.getUnnormalizedRotation(rot);
        Vector3f euler = new Vector3f();
        rot.getEulerAnglesXYZ(euler);
        System.out.println(indent + node.mName().dataString() + " rotXYZ(deg)=("
                + Math.toDegrees(euler.x) + ", " + Math.toDegrees(euler.y) + ", " + Math.toDegrees(euler.z) + ")");

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();
        for (int i = 0; i < numChildren; i++)
        {
            dumpNodeTransforms(AINode.create(children.get(i)), indent + "  ");
        }
    }

    /**
     * Extracts embedded FBX textures (Blender's "Embed Textures" export option)
     * into {@code <model>/textures/<material>/default.png}, matching where
     * {@link mchorse.bbs_mod.cubic.model.loaders.IModelLoader#findMaterialTexture}
     * looks. Skips materials whose texture is a plain external file reference
     * (nothing to extract) and never overwrites a texture that's already there,
     * so a user-supplied PNG always wins.
     */
    public static void extractEmbeddedTextures(AIScene scene, AssetProvider provider, Link model)
    {
        int numMaterials = scene.mNumMaterials();
        int numTextures = scene.mNumTextures();

        System.out.println("[FBXConverter] extractEmbeddedTextures: " + numMaterials + " materials, " + numTextures + " embedded texture(s) in scene");

        for (int i = 0; i < numMaterials; i++)
        {
            AIMaterial material = AIMaterial.create(scene.mMaterials().get(i));

            AIString nameStr = AIString.calloc();
            String materialName = null;
            if (Assimp.aiGetMaterialString(material, Assimp.AI_MATKEY_NAME, 0, 0, nameStr) == Assimp.aiReturn_SUCCESS)
            {
                materialName = nameStr.dataString();
            }
            nameStr.free();

            if (materialName == null || materialName.isEmpty())
            {
                continue;
            }

            AIString path = AIString.calloc();
            int result = Assimp.aiGetMaterialTexture(material, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null);
            String texturePath = result == Assimp.aiReturn_SUCCESS ? path.dataString() : null;
            path.free();

            System.out.println("[FBXConverter]   material \"" + materialName + "\" diffuse texture path = " + texturePath);

            if (texturePath == null || texturePath.isEmpty())
            {
                continue;
            }

            AITexture aiTexture = resolveEmbeddedTexture(scene, texturePath);

            if (aiTexture == null)
            {
                continue;
            }

            File folder = provider.getFile(model.combine("textures/" + materialName));
            if (folder == null)
            {
                continue;
            }

            File targetFile = new File(folder, "default.png");
            if (targetFile.exists())
            {
                continue;
            }

            try
            {
                BufferedImage image = decodeEmbeddedTexture(aiTexture);

                if (image != null)
                {
                    folder.mkdirs();
                    ImageIO.write(image, "png", targetFile);
                    System.out.println("[FBXConverter] Extracted embedded texture for material \"" + materialName + "\" -> " + targetFile.getPath());
                }
            }
            catch (Exception e)
            {
                System.err.println("Failed to extract embedded texture for material \"" + materialName + "\": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Resolves the {@link AITexture} embedded in the scene that corresponds to
     * a material's reported diffuse texture path. Handles both conventions
     * Assimp can use for FBX: the standard "*N" index into
     * {@code scene.mTextures()}, and (seen in practice with some FBX exports)
     * the original filename being reported even though the data is embedded -
     * in that case we match by filename against each embedded texture's hint.
     */
    private static AITexture resolveEmbeddedTexture(AIScene scene, String texturePath)
    {
        int numTextures = scene.mNumTextures();
        if (numTextures == 0)
        {
            return null;
        }

        if (texturePath.startsWith("*"))
        {
            try
            {
                int index = Integer.parseInt(texturePath.substring(1));
                if (index >= 0 && index < numTextures)
                {
                    return AITexture.create(scene.mTextures().get(index));
                }
            }
            catch (NumberFormatException ignored)
            {
            }
            return null;
        }

        String targetName = baseName(texturePath);

        for (int i = 0; i < numTextures; i++)
        {
            AITexture candidate = AITexture.create(scene.mTextures().get(i));
            AIString filenameHint = candidate.mFilename();
            String hint = filenameHint.dataString();

            if (!hint.isEmpty() && baseName(hint).equalsIgnoreCase(targetName))
            {
                return candidate;
            }
        }

        /* Only one embedded texture and only one unmatched candidate left to try -
         * fall back to it rather than silently skipping a texture we know exists. */
        if (numTextures == 1)
        {
            return AITexture.create(scene.mTextures().get(0));
        }

        return null;
    }

    private static String baseName(String path)
    {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static BufferedImage decodeEmbeddedTexture(AITexture aiTexture) throws IOException
    {
        int width = aiTexture.mWidth();
        int height = aiTexture.mHeight();

        long pcDataAddress = MemoryUtil.memGetAddress(aiTexture.address() + AITexture.PCDATA);

        if (pcDataAddress == MemoryUtil.NULL)
        {
            return null;
        }

        if (height == 0)
        {
            /* Compressed image (PNG/JPG/etc.) stored verbatim; mWidth is the byte
             * length of the compressed data, not a pixel count. Read it directly
             * as raw bytes rather than through the AITexel struct wrapper, whose
             * generated buffer is sized off mWidth*mHeight (= 0 here) and is
             * unusable for this case. */
            ByteBuffer raw = MemoryUtil.memByteBuffer(pcDataAddress, width);
            byte[] bytes = new byte[width];
            raw.get(bytes);

            return ImageIO.read(new ByteArrayInputStream(bytes));
        }
        else
        {
            /* Raw uncompressed BGRA8888 texel data: width*height texels, 4 bytes each. */
            int texelCount = width * height;
            ByteBuffer raw = MemoryUtil.memByteBuffer(pcDataAddress, texelCount * 4);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            for (int i = 0; i < texelCount; i++)
            {
                int b = raw.get(i * 4) & 0xFF;
                int g = raw.get(i * 4 + 1) & 0xFF;
                int r = raw.get(i * 4 + 2) & 0xFF;
                int a = raw.get(i * 4 + 3) & 0xFF;

                image.setRGB(i % width, i / width, (a << 24) | (r << 16) | (g << 8) | b);
            }

            return image;
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