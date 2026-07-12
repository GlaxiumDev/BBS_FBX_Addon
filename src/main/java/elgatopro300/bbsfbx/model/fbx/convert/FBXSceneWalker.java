package elgatopro300.bbsfbx.model.fbx.convert;

import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AINode;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the raw Assimp node tree in a single traversal: per-mesh world
 * transforms, which node each mesh belongs to, each node's parent name, and
 * every node's local (relative-to-parent) transform. Does no BOBJ
 * construction — see {@link FBXArmatureBuilder} for turning this into bones.
 */
public final class FBXSceneWalker
{
    private FBXSceneWalker() {}

    /**
     * @param nodeLocals populated with every node's local transform as a
     *                   side effect of this same walk (the local matrix is
     *                   already computed per node regardless, so this costs
     *                   one HashMap put per node). Used by
     *                   {@link FBXAnimationBaker} as a rest-pose fallback for
     *                   animated-but-unskinned nodes; harmlessly unused when
     *                   the scene has no animations. This used to be a
     *                   second, separate full tree walk.
     */
    public static Map<Integer, Matrix4f> collectMeshTransforms(AINode rootNode, Map<Integer, String> meshNodeNames, Map<String, String> nodeParents, Map<String, Matrix4f> nodeLocals)
    {
        Map<Integer, Matrix4f> meshTransforms = new HashMap<>();
        collectMeshTransforms(rootNode, new Matrix4f(), meshTransforms, meshNodeNames, nodeParents, nodeLocals);
        return meshTransforms;
    }

    private static void collectMeshTransforms(AINode node, Matrix4f parentGlobal, Map<Integer, Matrix4f> meshTransforms, Map<Integer, String> meshNodeNames, Map<String, String> nodeParents, Map<String, Matrix4f> nodeLocals)
    {
        Matrix4f local = FBXMath.toMatrix4f(node.mTransformation());
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);

        String nodeName = node.mName().dataString();
        nodeLocals.put(nodeName, local);

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
            AINode child = AINode.create(children.get(i));
            String childName = child.mName().dataString();
            // Skip synthetic roots so top-level objects stay parentless.
            String parentForChild = (nodeName.equals("RootNode") || nodeName.equals("Armature")) ? "" : nodeName;
            nodeParents.putIfAbsent(childName, parentForChild);
            collectMeshTransforms(child, global, meshTransforms, meshNodeNames, nodeParents, nodeLocals);
        }
    }
}